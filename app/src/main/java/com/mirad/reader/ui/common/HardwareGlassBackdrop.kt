package com.mirad.reader.ui.common

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.RenderNode
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.View
import androidx.annotation.RequiresApi
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal val LocalHardwareGlassBackdrop = staticCompositionLocalOf<HardwareGlassBackdrop?> { null }

/** Owns only the content RenderNode. Analysis snapshots are never published as display images. */
@Stable
@RequiresApi(31)
internal class HardwareGlassBackdrop(
    private val scope: CoroutineScope
) {
    internal var host: View? by mutableStateOf(null)
        private set
    internal var node: RenderNode? = null
        private set
    var enabled by mutableStateOf(false)
        private set
    private var foregroundState by mutableStateOf(false)
    val foregroundActive: Boolean get() = foregroundState
    private var consumers by mutableIntStateOf(0)
    private var attached by mutableStateOf(false)
    private var percent by mutableIntStateOf(0)
    /** Published after native dispatchDraw, never from its recording stack. */
    var contentReady by mutableStateOf(false)
        private set
    val renderingActive: Boolean get() = enabled && foregroundActive && attached && percent > 0 && consumers > 0
    internal var metrics by mutableStateOf<LiquidBackdropMetrics?>(null)
        private set

    private val gate = LiquidAnalysisGate()
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var wakePosted = false
    private val wake = Runnable {
        wakePosted = false
        if (!closed) {
            checkDimensions()
            contentReady = attached && node?.hasDisplayList() == true
            if (renderingActive && contentReady) requests.trySend(Unit)
        }
    }
    private var dimensions = IntSize.Zero
    private var revision: Any? = null
    private var closed = false
    private var samples = 0L
    private var staleSamples = 0L
    private var failures = 0L
    private var readbackWidth = 0
    private var readbackHeight = 0
    private var lastWorker = "none"
    private var lastDurationNanos = 0L
    private var maxInFlight = 0
    private var lastBaseOpacity = 1f
    private var lastProtectionOpacity = 0f
    private var lastBlurDp = 0f
    private var refractionActive = false
    private var previousSampleStarted = 0L
    private var minimumSampleInterval = Long.MAX_VALUE
    private val materialStates = mutableMapOf<Any, String>()
    private var materialDraws = 0L
    private var materialFallbacks = 0L
    private var fallbackReason = "not-drawn"
    private var capturedActive = false
    private var currentActive = false
    private var coordinatesReady = false
    private var nodeReady = false
    private var materialCanvasHardware: Boolean? = null
    private var dirtyWeb = 0L
    private var dirtyRecord = 0L
    private var dirtyConfig = 0L
    private var dirtyDimensions = 0L
    private var dirtyConsumer = 0L
    private var dirtyAttachment = 0L
    private var dirtyLifecycle = 0L
    private var dirtyOther = 0L
    private var dirtyWebDuringAnalysis = 0L
    private var lastDirtyOrigin = "none"
    private var lastDirtyAfterSampleNanos = -1L
    private var identicalAnalysisResults = 0L
    private var changedAnalysisResults = 0L
    private var materialNodeReady = false
    private var materialNodeRecords = 0L
    private var materialNodeRecoveries = 0L
    private val attachment = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) {
            attached = view.isHardwareAccelerated
            invalidateScene("attachment")
        }
        override fun onViewDetachedFromWindow(view: View) {
            attached = false
            contentReady = false
            invalidateScene("attachment")
        }
    }
    private val layout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        checkDimensions()
    }
    private val sampling: Job = scope.launch {
        for (request in requests) {
            if (!renderingActive || !contentReady || !gate.dirty) continue
            val wait = gate.waitNanos(System.nanoTime())
            if (wait > 0) delay((wait + 999_999) / 1_000_000)
            if (!renderingActive || !contentReady || !gate.dirty) continue
            val sampled = sample()
            if (sampled && renderingActive && gate.dirty) requests.trySend(Unit)
        }
    }

    fun attach(host: View, node: RenderNode) {
        if (this.host === host && this.node === node) return
        this.host?.let(::detach)
        this.host = host
        this.node = node
        contentReady = false
        attached = host.isAttachedToWindow && host.isHardwareAccelerated
        host.addOnAttachStateChangeListener(attachment)
        host.addOnLayoutChangeListener(layout)
        invalidateScene("attachment")
    }

    fun detach(host: View) {
        if (this.host !== host) return
        host.removeOnAttachStateChangeListener(attachment)
        host.removeOnLayoutChangeListener(layout)
        this.host = null
        node = null
        attached = false
        contentReady = false
        invalidateScene("attachment")
    }

    /** Safe in a draw callback: marks ordinary state and sends one conflated wakeup only. */
    fun contentChanged() = contentChanged("other")

    fun contentChanged(origin: String) {
        if (closed) return
        countDirty(origin)
        gate.markDirty()
        // Resume sampling after the native dispatchDraw stack has completed, never recursively
        // from a view invalidation callback while its content RenderNode is still recording.
        if (!wakePosted) { wakePosted = true; mainHandler.post(wake) }
    }

    /** Lifecycle callbacks must stop work synchronously, even when composition frames are paused. */
    internal fun setForegroundActive(active: Boolean) {
        if (foregroundActive == active) return
        foregroundState = active
        invalidateScene("lifecycle")
    }

    internal fun configure(enabled: Boolean, percent: Int, revision: Any?) {
        val stopped = this.enabled != enabled || (this.percent == 0) != (percent == 0)
        this.enabled = enabled
        this.percent = percent
        if (stopped || this.revision != revision) {
            this.revision = revision
            invalidateScene("config")
        }
    }

    internal fun addConsumer(key: Any) {
        materialStates[key] = "not-drawn"
        consumers++
        contentChanged("consumer")
    }
    internal fun removeConsumer(key: Any) {
        materialStates.remove(key)
        consumers = (consumers - 1).coerceAtLeast(0)
        if (consumers == 0) invalidateScene("consumer")
    }

    private fun invalidateScene(origin: String) {
        countDirty(origin)
        gate.invalidate()
        metrics = null
        if (!closed && !wakePosted) { wakePosted = true; mainHandler.post(wake) }
    }

    private fun checkDimensions() {
        val current = node?.let { IntSize(it.width, it.height) } ?: IntSize.Zero
        if (current != dimensions) { dimensions = current; invalidateScene("dimensions") }
    }

    private fun countDirty(origin: String) {
        lastDirtyOrigin = when (origin) {
            "web" -> { dirtyWeb++; if (gate.inFlight) dirtyWebDuringAnalysis++; "web" }
            "record" -> { dirtyRecord++; "record" }
            "config" -> { dirtyConfig++; "config" }
            "dimensions" -> { dirtyDimensions++; "dimensions" }
            "consumer" -> { dirtyConsumer++; "consumer" }
            "attachment" -> { dirtyAttachment++; "attachment" }
            "lifecycle" -> { dirtyLifecycle++; "lifecycle" }
            else -> { dirtyOther++; "other" }
        }
        lastDirtyAfterSampleNanos = if (previousSampleStarted == 0L) -1L else System.nanoTime() - previousSampleStarted
    }

    private suspend fun sample(): Boolean {
        val content = node ?: return false
        if (!content.hasDisplayList()) return false
        checkDimensions()
        val size = liquidAnalysisDimensions(content.width, content.height) ?: return false
        val start = System.nanoTime()
        val token = gate.begin(start) ?: return false
        if (previousSampleStarted != 0L) minimumSampleInterval = minOf(minimumSampleInterval, start - previousSampleStarted)
        previousSampleStarted = start
        maxInFlight = maxOf(maxInFlight, if (gate.inFlight) 1 else 0)
        var sampleNode: RenderNode? = null
        var result: LiquidBackdropMetrics? = null
        try {
            // This node is not owned by a visible View/Compose layer. Record on Main while the
            // document node is stable; rendering and the bounded pixel readback happen off Main.
            val isolated = RenderNode("MiradReaderAnalysis").apply { setPosition(0, 0, size.first, size.second) }
            sampleNode = isolated
            val canvas = isolated.beginRecording(size.first, size.second)
            try {
                canvas.scale(size.first.toFloat() / content.width, size.second.toFloat() / content.height)
                canvas.drawRenderNode(content)
            } finally { isolated.endRecording() }
            result = withContext(Dispatchers.Default) {
                lastWorker = Thread.currentThread().name
                val image = captureHardwareNode(isolated) ?: return@withContext null
                try {
                    readbackWidth = image.width
                    readbackHeight = image.height
                    check(image.width <= LIQUID_ANALYSIS_MAX_EDGE && image.height <= LIQUID_ANALYSIS_MAX_EDGE)
                    val cpu = image.copy(Bitmap.Config.ARGB_8888, false) ?: return@withContext null
                    try {
                        val pixels = IntArray(cpu.width * cpu.height)
                        cpu.getPixels(pixels, 0, cpu.width, 0, 0, cpu.width, cpu.height)
                        analyzeLiquidPixels(pixels)
                    } finally { cpu.recycle() }
                } finally { image.recycle() }
            }
            if (result != null) samples++ else failures++
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            failures++
        } finally {
            sampleNode?.discardDisplayList()
            lastDurationNanos = System.nanoTime() - start
            // A layout can finish while the worker is rendering. Reject its old-sized result even
            // if the post-draw readiness message has not run yet.
            checkDimensions()
            val current = gate.finish(token)
            if (current && renderingActive && contentReady && result != null) {
                if (metrics?.fingerprint == result.fingerprint) identicalAnalysisResults++ else changedAnalysisResults++
                metrics = result
            }
            else if (!current) staleSamples++
        }
        return true
    }

    internal fun recordMaterial(base: Float, protection: Float, blur: Float, refraction: Boolean,
        nodeReady: Boolean, records: Long, recoveries: Long) {
        lastBaseOpacity = base; lastProtectionOpacity = protection; lastBlurDp = blur; refractionActive = refraction
        materialNodeReady = nodeReady; materialNodeRecords = records; materialNodeRecoveries = recoveries
    }

    /** Plain diagnostic fields only: this method is safe inside drawWithContent. */
    internal fun recordGuard(key: Any, reason: String, composedActive: Boolean, drawActive: Boolean,
        hasCoordinates: Boolean, hasNode: Boolean, hardwareCanvas: Boolean?) {
        materialStates[key] = reason
        fallbackReason = reason
        capturedActive = composedActive
        currentActive = drawActive
        coordinatesReady = hasCoordinates
        nodeReady = hasNode
        materialCanvasHardware = hardwareCanvas
        if (reason == "none") {
            materialDraws++
        } else {
            materialFallbacks++
            lastBaseOpacity = 1f
            lastProtectionOpacity = 0f
            lastBlurDp = 0f
            refractionActive = false
            materialNodeReady = false
        }
    }

    fun statsSummary(): String = "gpuActive=$renderingActive foregroundActive=$foregroundActive contentReady=$contentReady consumers=$consumers analysisSamples=$samples " +
        "analysisFailures=$failures staleSamples=$staleSamples analysisSize=${readbackWidth}x$readbackHeight analysisBackend=independent-node " +
        "analysisInFlight=${if (gate.inFlight) 1 else 0} maxAnalysisInFlight=$maxInFlight " +
        "analysisThread=$lastWorker analysisDurationMs=${lastDurationNanos / 1_000_000.0} " +
        "analysisMinIntervalMs=${if (minimumSampleInterval == Long.MAX_VALUE) 0.0 else minimumSampleInterval / 1_000_000.0} " +
        "baseOpacity=$lastBaseOpacity protectionOpacity=$lastProtectionOpacity blurDp=$lastBlurDp " +
        "refractionActive=$refractionActive cpuDisplayCaptures=0 " +
        "materialDraws=$materialDraws materialFallbacks=$materialFallbacks " +
        "readyMaterials=${materialStates.values.count { it == "none" }} " +
        "fallbackMaterials=${materialStates.values.count { it != "none" }} " +
        "fallbackReason=$fallbackReason capturedActive=$capturedActive currentActive=$currentActive " +
        "coordinatesReady=$coordinatesReady nodeReady=$nodeReady materialCanvasHardware=${materialCanvasHardware ?: "unknown"} " +
        "dirtyWeb=$dirtyWeb dirtyRecord=$dirtyRecord dirtyConfig=$dirtyConfig dirtyDimensions=$dirtyDimensions " +
        "dirtyConsumer=$dirtyConsumer dirtyAttachment=$dirtyAttachment dirtyLifecycle=$dirtyLifecycle dirtyOther=$dirtyOther " +
        "dirtyWebDuringAnalysis=$dirtyWebDuringAnalysis lastDirtyOrigin=$lastDirtyOrigin " +
        "lastDirtyAfterSampleMs=${lastDirtyAfterSampleNanos / 1_000_000.0} " +
        "identicalAnalysisResults=$identicalAnalysisResults changedAnalysisResults=$changedAnalysisResults " +
        "materialNodeReady=$materialNodeReady materialNodeRecords=$materialNodeRecords materialNodeRecoveries=$materialNodeRecoveries"

    internal fun close() {
        closed = true
        host?.let(::detach)
        sampling.cancel()
        mainHandler.removeCallbacks(wake)
        requests.close()
    }
}

@Composable
internal fun rememberHardwareGlassBackdrop(revision: Any?): HardwareGlassBackdrop? {
    if (Build.VERSION.SDK_INT < 31) return null
    val context = LocalContext.current
    val power = remember(context) { context.getSystemService(Context.POWER_SERVICE) as PowerManager }
    var powerSave by remember { mutableStateOf(power.isPowerSaveMode) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    // key 含 lifecycle：DisposableEffect 的 onDispose 会 close() source 且 close 不可恢复，
    // 若宿主 lifecycle 变化而 source 因 scope 未变被复用，玻璃会从此静默失效。
    val source = remember(scope, lifecycle) { HardwareGlassBackdrop(scope) }
    val legacy = LocalGlassLegacy.current
    val fallback = LocalGlassForceFallback.current
    val percent = LocalGlassAppearance.current.transparencyPercent
    DisposableEffect(context, power, lifecycle, source) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { powerSave = power.isPowerSaveMode }
        }
        context.registerReceiver(receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        val observer = LifecycleEventObserver { _, _ ->
            val started = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            source.setForegroundActive(started)
            if (started) powerSave = power.isPowerSaveMode
        }
        source.setForegroundActive(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        lifecycle.addObserver(observer)
        onDispose {
            context.unregisterReceiver(receiver)
            lifecycle.removeObserver(observer)
            source.close()
        }
    }
    SideEffect { source.configure(!legacy && !fallback && !powerSave, percent, revision) }
    return source
}
