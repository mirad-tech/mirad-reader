package com.mirad.reader.ui.common

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Build
import android.os.PowerManager
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.max

/** Preview overrides only: neither value is persisted or exposed in the product. */
val LocalGlassLegacy = staticCompositionLocalOf { false }
val LocalGlassForceFallback = staticCompositionLocalOf { false }

val LocalGlassBackdrop = staticCompositionLocalOf<GlassBackdropState?> { null }

enum class GlassStyle { Toolbar, Panel }

internal data class SyntheticSample(
    val width: Int,
    val height: Int,
    val fingerprint: Long
)

@Stable
class GlassBackdropState @JvmOverloads internal constructor(
    private val nowNanos: () -> Long = System::nanoTime,
    private val minIntervalNanos: Long = MIN_SNAPSHOT_INTERVAL_NANOS,
    internal var sampleProvider: (() -> Bitmap?)? = null,
    internal var syntheticSampleProvider: (suspend () -> SyntheticSample?)? = null
) {
    internal var layer: GraphicsLayer? = null
    internal var sampleLayer: GraphicsLayer? = null
    internal var sampleLayerReady: Boolean = false
    internal var owner: android.view.View? = null
    internal var hostScope: CoroutineScope? = null

    internal var origin by mutableStateOf(Offset.Zero)
    internal var size by mutableStateOf(IntSize.Zero)
    internal var recorded by mutableStateOf(false)
    internal var image by mutableStateOf<ImageBitmap?>(null)
    // 初始保守为 false：能力判定（API/省电/降级）由宿主 SideEffect 在首帧后写入，
    // 避免首帧就绕过闸门录制采样层并发起一次无谓采样。
    internal var enabled by mutableStateOf(false)
    internal var activeTransparencyPercent: Int = GlassAppearance.DEFAULT_PERCENT

    // 事件版本驱动：仅在实际采样或发布新内容时递增，不在 drawWithContent 阶段自增（R1、F2）
    internal var drawVersion by mutableLongStateOf(0L)

    // WebView 采集统计
    internal var captureAttempts = 0L
    internal var publishedCaptures = 0L
    internal var captureFailures = 0L
    internal var captureTimeNanos = 0L

    // Compose 离屏采样与分析统计（R1、R3、R4、S1、T1）
    internal var sourceRecordCount = 0L
    internal var snapshotCount = 0L
    internal var analysisCount = 0L
    internal var lastSourceWidth = 0
    internal var lastSourceHeight = 0
    internal var lastReadbackWidth = 0
    internal var lastReadbackHeight = 0
    internal var lastSampleThreadName = "none"
    internal var lastSampleDurationNanos = 0L
    internal var inFlightSnapshotCount = 0
    internal var maxObservedInFlightCount = 0
    internal var lastComputedAmbientOpacity = 1f
    internal var lastComputedPeakOpacity = 1f
    internal var lastComputedBlurDp = 0f
    internal var lastComputedHighlightAlpha = 0f
    internal var lastProtectedCellCount = 0

    // 跨窗口共享快照与 CPU 安全局部采样分析副本（均为有界 <=25% 且 <=640px 的 ARGB_8888 位图，R3）
    internal var sharedSnapshot by mutableStateOf<ImageBitmap?>(null)
    internal var analysisBitmap by mutableStateOf<Bitmap?>(null)

    private var generation = 0L
    private var snapshotInFlight = false
    private var lastSnapshotNanos = 0L
    private var pendingSnapshot = false
    private var activeCaptureJob: Job? = null
    private var trailingJob: Job? = null
    internal var lastSampleFingerprint = 0L
        private set

    internal val activeSnapshotCount: Int
        get() = if (sharedSnapshot != null || analysisBitmap != null || image != null || lastSampleFingerprint != 0L) 1 else 0

    internal fun statsSummary(): String =
        "sourceRecords=$sourceRecordCount snapshots=$snapshotCount analyses=$analysisCount " +
            "webAttempts=$captureAttempts webPublished=$publishedCaptures " +
            "sourceSize=${lastSourceWidth}x${lastSourceHeight} readbackSize=${lastReadbackWidth}x${lastReadbackHeight} " +
            "inFlight=$inFlightSnapshotCount maxInFlight=$maxObservedInFlightCount activeSnapshots=$activeSnapshotCount " +
            "ambientOpacity=${"%.3f".format(java.util.Locale.US, lastComputedAmbientOpacity)} " +
            "peakOpacity=${"%.3f".format(java.util.Locale.US, lastComputedPeakOpacity)} " +
            "protectedCells=$lastProtectedCellCount blurDp=${"%.1f".format(java.util.Locale.US, lastComputedBlurDp)} " +
            "highlightAlpha=${"%.3f".format(java.util.Locale.US, lastComputedHighlightAlpha)} " +
            "thread=$lastSampleThreadName durationMs=${lastSampleDurationNanos / 1_000_000.0}"

    fun setImage(image: ImageBitmap?, size: IntSize = this.size, origin: Offset = this.origin) {
        if (image != null && (!enabled || activeTransparencyPercent <= 0)) {
            clearAndStop("setImage_while_disabled_or_zero")
            return
        }
        if (image == null) {
            this.image = null
            this.sharedSnapshot = null
            this.analysisBitmap = null
            this.lastSampleFingerprint = 0L
            drawVersion++
            return
        }
        val bmp = image.asAndroidBitmap()
        this.image = image
        this.sharedSnapshot = image
        this.analysisBitmap = bmp
        this.size = size
        this.origin = origin
        this.lastSourceWidth = size.width
        this.lastSourceHeight = size.height
        this.lastReadbackWidth = bmp.width
        this.lastReadbackHeight = bmp.height
        this.lastSampleThreadName = Thread.currentThread().name
        drawVersion++
    }

    /**
     * R4：当透明度切到 0%、组件失活、进入后台或能力降级时，
     * 取消所有排队采样、使在途代次失效，并立即清空缓存位图与状态。
     */
    fun clearAndStop(reason: String = "stopped") {
        generation++
        pendingSnapshot = false
        snapshotInFlight = false
        inFlightSnapshotCount = 0
        activeCaptureJob?.cancel()
        activeCaptureJob = null
        trailingJob?.cancel()
        trailingJob = null
        val hadData = image != null || sharedSnapshot != null || analysisBitmap != null
        image = null
        sharedSnapshot = null
        analysisBitmap = null
        lastSampleFingerprint = 0L
        if (hadData) {
            runCatching {
                android.util.Log.i("GlassPreview", "glassCleared reason=$reason ${statsSummary()}")
            }
        }
    }

    /**
     * R1：由 glassBackdropSource 在录制完有界 sampleLayer 后调用。
     * 严禁在此方法内直接修改任何 Compose MutableState，避免 draw -> state -> draw 死循环。
     */
    internal fun onSourceLayerRecorded(width: Int, height: Int, transparencyPercent: Int) {
        if (!enabled || transparencyPercent <= 0) return
        sourceRecordCount++
        lastSourceWidth = width
        lastSourceHeight = height
        sampleLayerReady = true
        notifySourceChanged(transparencyPercent)
    }

    internal fun notifySourceChanged(
        transparencyPercent: Int = activeTransparencyPercent,
        force: Boolean = false
    ) {
        activeTransparencyPercent = transparencyPercent
        if (!enabled || transparencyPercent <= 0) {
            clearAndStop("notify_zero_or_disabled")
            return
        }
        val scope = hostScope ?: return
        if (snapshotInFlight) {
            pendingSnapshot = true
            return
        }
        val now = nowNanos()
        val elapsed = now - lastSnapshotNanos
        if (!force && lastSnapshotNanos != 0L && elapsed < minIntervalNanos) {
            pendingSnapshot = true
            scheduleTrailingOnScope(scope, minIntervalNanos - elapsed, transparencyPercent)
            return
        }
        trailingJob?.cancel()
        trailingJob = null
        activeCaptureJob = scope.launch {
            requestSnapshot(transparencyPercent, force = force)
        }
    }

    private fun scheduleTrailingOnScope(
        scope: CoroutineScope,
        remainingNanos: Long,
        transparencyPercent: Int
    ) {
        if (trailingJob?.isActive == true) return
        val token = generation
        val delayMs = max(1L, (remainingNanos + 999_999L) / 1_000_000L)
        trailingJob = scope.launch {
            delay(delayMs)
            trailingJob = null
            if (generation == token && pendingSnapshot && enabled && activeTransparencyPercent > 0) {
                requestSnapshot(activeTransparencyPercent, fromTrailingTimer = true)
            }
        }
    }

    @JvmOverloads
    suspend fun requestSnapshot(
        transparencyPercent: Int = activeTransparencyPercent,
        force: Boolean = false,
        fromTrailingTimer: Boolean = false
    ) {
        activeTransparencyPercent = transparencyPercent
        if (transparencyPercent <= 0 || !enabled) {
            clearAndStop("request_zero_or_disabled")
            return
        }
        if (image != null) {
            sharedSnapshot = image
            analysisBitmap = image?.asAndroidBitmap()
            return
        }
        val synthProvider = syntheticSampleProvider
        val provider = sampleProvider
        val currentSampleLayer = sampleLayer
        if (synthProvider == null && provider == null && (currentSampleLayer == null || !sampleLayerReady)) return

        if (snapshotInFlight) {
            pendingSnapshot = true
            return
        }

        val now = nowNanos()
        val elapsed = now - lastSnapshotNanos
        if (!force && !fromTrailingTimer && lastSnapshotNanos != 0L && elapsed < minIntervalNanos) {
            pendingSnapshot = true
            val scope = hostScope
            if (scope != null) {
                scheduleTrailingOnScope(scope, minIntervalNanos - elapsed, transparencyPercent)
                return
            } else {
                val delayMs = max(1L, (minIntervalNanos - elapsed + 999_999L) / 1_000_000L)
                val waitToken = generation
                delay(delayMs)
                if (generation != waitToken || !pendingSnapshot || !enabled || activeTransparencyPercent <= 0) {
                    return
                }
            }
        }

        val token = generation
        snapshotInFlight = true
        inFlightSnapshotCount = 1
        maxObservedInFlightCount = maxOf(maxObservedInFlightCount, inFlightSnapshotCount)
        pendingSnapshot = false
        val startNanos = nowNanos()
        try {
            if (synthProvider != null) {
                val sample = runCatching { synthProvider.invoke() }.getOrNull() ?: return
                if (generation != token || !enabled || activeTransparencyPercent <= 0) return
                val (dw, dh) = glassSnapshotDimensions(sample.width, sample.height)?.takeIf {
                    sample.width > 640 || sample.height > 640
                } ?: (sample.width to sample.height)
                lastReadbackWidth = dw
                lastReadbackHeight = dh
                lastSampleThreadName = Thread.currentThread().name
                val finishNanos = nowNanos()
                lastSnapshotNanos = finishNanos
                lastSampleDurationNanos = finishNanos - startNanos
                snapshotCount++
                if (!force && sample.fingerprint == lastSampleFingerprint && lastSampleFingerprint != 0L) {
                    return
                }
                lastSampleFingerprint = sample.fingerprint
                recorded = true
                drawVersion++
                return
            }

            val rawBmp: Bitmap? = if (provider != null) {
                runCatching { provider.invoke() }.getOrNull()
            } else {
                runCatching { currentSampleLayer?.toImageBitmap()?.asAndroidBitmap() }.getOrNull()
            }

            if (rawBmp == null) return
            if (generation != token || !enabled || activeTransparencyPercent <= 0) {
                if (provider == null && !rawBmp.isRecycled) {
                    runCatching { rawBmp.recycle() }
                }
                return
            }

            // R3：记录实际读回尺寸（由于 sampleLayer 录制前已按 glassSnapshotDimensions 缩放，此处读回即有界小图）
            lastReadbackWidth = rawBmp.width
            lastReadbackHeight = rawBmp.height
            lastSampleThreadName = Thread.currentThread().name

            val isHw = try { rawBmp.config?.name == "HARDWARE" } catch (_: Throwable) { false }
            val swBmp = if (isHw) {
                val copied = runCatching { rawBmp.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
                runCatching { rawBmp.recycle() }
                copied
            } else {
                rawBmp
            } ?: return

            val (dw, dh) = glassSnapshotDimensions(swBmp.width, swBmp.height)?.takeIf {
                swBmp.width > 640 || swBmp.height > 640
            } ?: (swBmp.width to swBmp.height)

            val cpuSafe = if (dw == swBmp.width && dh == swBmp.height && swBmp.config == Bitmap.Config.ARGB_8888) {
                swBmp
            } else {
                val scaled = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(scaled)
                val src = android.graphics.Rect(0, 0, swBmp.width, swBmp.height)
                val dst = android.graphics.Rect(0, 0, dw, dh)
                val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
                canvas.drawBitmap(swBmp, src, dst, paint)
                if (swBmp !== rawBmp && !swBmp.isRecycled) {
                    runCatching { swBmp.recycle() }
                }
                scaled
            }

            // GraphicsLayer 读回路径（provider == null）的位图归本函数所有：
            // 回收判定按所有权而非对象恒等，cpuSafe === rawBmp 时同样需要回收，
            // 否则静态内容反复重绘会按节流间隔持续泄漏采样位图。
            val ownsReadback = provider == null

            if (generation != token || !enabled || activeTransparencyPercent <= 0) {
                if ((ownsReadback || cpuSafe !== rawBmp) && cpuSafe !== analysisBitmap && !cpuSafe.isRecycled) {
                    runCatching { cpuSafe.recycle() }
                }
                return
            }

            val finishNanos = nowNanos()
            lastSnapshotNanos = finishNanos
            lastSampleDurationNanos = finishNanos - startNanos
            snapshotCount++

            // 指纹与全像素比较是纯 CPU 计算（cpuSafe 已保证为软件 ARGB_8888 位图，
            // 可安全跨线程 getPixel），放到 Default 线程避免拖慢主线程帧。
            val fingerprint = withContext(Dispatchers.Default) { computeBitmapFingerprint(cpuSafe) }
            if (generation != token || !enabled || activeTransparencyPercent <= 0) {
                if ((ownsReadback || cpuSafe !== rawBmp) && cpuSafe !== analysisBitmap && !cpuSafe.isRecycled) {
                    runCatching { cpuSafe.recycle() }
                }
                return
            }
            if (
                !force &&
                fingerprint == lastSampleFingerprint &&
                analysisBitmap != null &&
                sharedSnapshot != null &&
                withContext(Dispatchers.Default) { areBoundedBitmapsIdentical(cpuSafe, analysisBitmap) }
            ) {
                // R1 & S2：全量像素完全一致时跳过状态更新，阻断静态重绘环且绝不漏检局部变化
                if ((ownsReadback || cpuSafe !== rawBmp) && cpuSafe !== analysisBitmap && !cpuSafe.isRecycled) {
                    runCatching { cpuSafe.recycle() }
                }
                return
            }

            lastSampleFingerprint = fingerprint
            val boundedImage = cpuSafe.asImageBitmap()
            sharedSnapshot = boundedImage
            analysisBitmap = cpuSafe
            if (!recorded) {
                recorded = true
            }
            drawVersion++
            runCatching {
                android.util.Log.i("GlassPreview", "glassSampleReadback ${statsSummary()}")
            }
        } finally {
            if (generation == token) {
                snapshotInFlight = false
                inFlightSnapshotCount = 0
                if (pendingSnapshot && enabled && activeTransparencyPercent > 0) {
                    val scope = hostScope
                    if (scope != null) {
                        val elapsedAfter = max(0L, nowNanos() - lastSnapshotNanos)
                        val remaining = max(1_000_000L, minIntervalNanos - elapsedAfter)
                        scheduleTrailingOnScope(scope, remaining, activeTransparencyPercent)
                    } else {
                        val delayMs = max(1L, (minIntervalNanos + 999_999L) / 1_000_000L)
                        delay(delayMs)
                        if (generation == token && pendingSnapshot && enabled && activeTransparencyPercent > 0) {
                            requestSnapshot(activeTransparencyPercent, fromTrailingTimer = true)
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val MIN_SNAPSHOT_INTERVAL_NANOS = 100_000_000L
    }
}

/**
 * S2：对有界采样位图（<=25% 尺寸且 <=640px）进行全像素遍历指纹计算，
 * 确保任意横带、竖带、角落小块或单字符像素变化都能被检测到。
 */
internal fun computeBitmapFingerprint(bitmap: Bitmap): Long {
    if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return 0L
    return computeBitmapFingerprint(bitmap.width, bitmap.height) { x, y -> bitmap.getPixel(x, y) }
}

internal fun computeBitmapFingerprint(width: Int, height: Int, getPixel: (Int, Int) -> Int): Long {
    if (width <= 0 || height <= 0) return 0L
    var hash = -0x340d631b7bdddcdbL xor (width.toLong() shl 32) xor height.toLong()
    for (y in 0 until height) {
        for (x in 0 until width) {
            val pixel = getPixel(x, y).toLong() and 0xFFFFFFFFL
            val coordMix = (y * width + x + 1).toLong() * -0x61c8864680b583ebL
            hash = (hash xor (pixel + coordMix)) * 0x100000001b3L
        }
    }
    return if (hash == 0L) 1L else hash
}

internal fun areBoundedBitmapsIdentical(a: Bitmap?, b: Bitmap?): Boolean {
    if (a === b) return a != null
    if (a == null || b == null || a.isRecycled || b.isRecycled) return false
    return areBoundedPixelsIdentical(
        widthA = a.width,
        heightA = a.height,
        getPixelA = { x, y -> a.getPixel(x, y) },
        widthB = b.width,
        heightB = b.height,
        getPixelB = { x, y -> b.getPixel(x, y) }
    )
}

internal fun areBoundedPixelsIdentical(
    widthA: Int,
    heightA: Int,
    getPixelA: (Int, Int) -> Int,
    widthB: Int,
    heightB: Int,
    getPixelB: (Int, Int) -> Int
): Boolean {
    if (widthA <= 0 || heightA <= 0 || widthA != widthB || heightA != heightB) return false
    for (y in 0 until heightA) {
        for (x in 0 until widthA) {
            if (getPixelA(x, y) != getPixelB(x, y)) return false
        }
    }
    return true
}

@Composable
fun GlassBackdropHost(content: @Composable () -> Unit) {
    val source = remember { GlassBackdropState() }
    val hostScope = rememberCoroutineScope()
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val appearance = LocalGlassAppearance.current
    val legacy = LocalGlassLegacy.current
    val power = remember(context) { context.getSystemService(Context.POWER_SERVICE) as PowerManager }
    var powerSave by remember { mutableStateOf(power.isPowerSaveMode) }
    val forceFallback = LocalGlassForceFallback.current

    DisposableEffect(context, power) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                powerSave = power.isPowerSaveMode
            }
        }
        context.registerReceiver(receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        onDispose { context.unregisterReceiver(receiver) }
    }

    val blurAllowed = glassBlurAllowed(Build.VERSION.SDK_INT, powerSave, forceFallback) && !legacy
    val activePercent = appearance.transparencyPercent

    SideEffect {
        source.owner = view
        source.hostScope = hostScope
        source.enabled = blurAllowed
        source.activeTransparencyPercent = activePercent
    }

    // R4：当透明度切为 0% 或能力降级时立即停止并清空；当从 0% 恢复为非零时重新捕获最新背景
    LaunchedEffect(blurAllowed, activePercent) {
        if (!blurAllowed || activePercent <= 0) {
            source.clearAndStop(if (activePercent <= 0) "transparency_zero" else "blur_disabled")
        } else {
            view.invalidate()
            source.notifySourceChanged(activePercent, force = true)
        }
    }

    // R4：生命周期进入后台 (ON_STOP) 时主动停止并释放快照，回到前台 (ON_START) 时按需恢复
    DisposableEffect(lifecycle, source) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> source.clearAndStop("lifecycle_on_stop")
                Lifecycle.Event.ON_START -> {
                    if (source.enabled && source.activeTransparencyPercent > 0) {
                        view.invalidate()
                        source.notifySourceChanged(source.activeTransparencyPercent, force = true)
                    }
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            source.clearAndStop("host_disposed")
        }
    }

    CompositionLocalProvider(LocalGlassBackdrop provides source, content = content)
}

/** Apply only to the content plane, never to a parent containing glass controls. */
fun Modifier.glassBackdropSource(): Modifier = composed {
    val source = LocalGlassBackdrop.current ?: return@composed this
    val appearance = LocalGlassAppearance.current
    val legacy = LocalGlassLegacy.current
    val contentLayer = rememberGraphicsLayer()
    val boundedSampleLayer = rememberGraphicsLayer()
    val baseColor = MaterialTheme.colorScheme.background

    DisposableEffect(source, contentLayer, boundedSampleLayer) {
        source.layer = contentLayer
        source.sampleLayer = boundedSampleLayer
        onDispose {
            if (source.layer === contentLayer) {
                source.layer = null
                source.sampleLayer = null
                source.sampleLayerReady = false
                source.recorded = false
                source.clearAndStop("source_disposed")
            }
        }
    }

    this.onGloballyPositioned { coordinates ->
        val newOrigin = coordinates.positionOnScreen()
        val newSize = coordinates.size
        if (source.origin != newOrigin) source.origin = newOrigin
        if (source.size != newSize) source.size = newSize
    }.drawWithContent {
        val percent = appearance.transparencyPercent
        val active = source.enabled && !legacy && percent > 0
        if (!active) {
            drawContent()
            return@drawWithContent
        }

        val w = size.width.toInt()
        val h = size.height.toInt()
        if (w <= 0 || h <= 0) {
            drawContent()
            return@drawWithContent
        }

        contentLayer.record {
            drawRect(baseColor)
            this@drawWithContent.drawContent()
        }

        // R3：在读回前直接录制到已限制尺寸（<=25% 且最长边 <=640px）的离屏 GraphicsLayer
        val dims = glassSnapshotDimensions(w, h)
        if (dims != null) {
            val (dw, dh) = dims
            boundedSampleLayer.record(size = IntSize(dw, dh)) {
                scale(
                    scaleX = dw.toFloat() / w.toFloat(),
                    scaleY = dh.toFloat() / h.toFloat(),
                    pivot = Offset.Zero
                ) {
                    drawLayer(contentLayer)
                }
            }
            source.onSourceLayerRecorded(w, h, percent)
        }

        drawLayer(contentLayer)
    }
}

/** The effect belongs exclusively to a background layer; children are drawn afterwards. */
fun Modifier.glassBackground(
    style: GlassStyle = GlassStyle.Toolbar,
    shape: Shape = RoundedCornerShape(22.dp)
): Modifier = composed {
    val hardware = LocalHardwareGlassBackdrop.current
    if (hardware != null && Build.VERSION.SDK_INT >= 31) {
        return@composed this.hardwareGlassBackground(hardware, style, shape)
    }
    val source = LocalGlassBackdrop.current
    val view = LocalView.current
    val legacy = LocalGlassLegacy.current
    val appearance = LocalGlassAppearance.current
    val color = MaterialTheme.colorScheme.surface
    val dark = color.luminance() < .5f
    val background = rememberGraphicsLayer()
    var origin by remember { mutableStateOf(Offset.Zero) }
    var glassSize by remember { mutableStateOf(IntSize.Zero) }
    var separateWindow by remember { mutableStateOf(false) }
    val enabled = source?.enabled == true && !legacy
    val edgeTexture = LocalGlassEdgeTexture.current
    val percent = appearance.transparencyPercent

    // 局部背景特征分析：最多 5 次/秒（200ms 节流），仅在采样位图或组件几何实际变化后执行并缓存
    var cachedMetrics by remember { mutableStateOf<BackdropMetrics?>(null) }
    var lastAnalysisNanos by remember { mutableLongStateOf(0L) }
    var lastAnalyzedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var lastAnalyzedOrigin by remember { mutableStateOf(Offset.Unspecified) }
    var lastAnalyzedSize by remember { mutableStateOf(IntSize.Zero) }

    val currentAnalysisBitmap = if (enabled && percent > 0) source?.analysisBitmap else null

    LaunchedEffect(currentAnalysisBitmap, origin, glassSize, percent, enabled) {
        if (!enabled || percent <= 0) {
            cachedMetrics = BackdropMetrics.NEUTRAL
            lastAnalyzedBitmap = null
            return@LaunchedEffect
        }
        val bmp = currentAnalysisBitmap
        if (bmp != null && !bmp.isRecycled && source.size.width > 0 && source.size.height > 0 && glassSize.width > 0 && glassSize.height > 0) {
            if (bmp === lastAnalyzedBitmap && origin == lastAnalyzedOrigin && glassSize == lastAnalyzedSize && cachedMetrics != null) {
                return@LaunchedEffect
            }
            val now = System.nanoTime()
            val elapsed = now - lastAnalysisNanos
            if (lastAnalysisNanos != 0L && elapsed < 200_000_000L) {
                delay(max(1L, (200_000_000L - elapsed + 999_999L) / 1_000_000L))
            }
            if (bmp.isRecycled || source.size.width <= 0 || source.size.height <= 0) return@LaunchedEffect
            val delta = origin - source.origin
            val scaleX = bmp.width.toFloat() / source.size.width
            val scaleY = bmp.height.toFloat() / source.size.height
            val rect = Rect(
                delta.x * scaleX,
                delta.y * scaleY,
                (delta.x + glassSize.width) * scaleX,
                (delta.y + glassSize.height) * scaleY
            )
            cachedMetrics = analyzeLocalBackdrop(bmp, rect)
            source.analysisCount++
            lastAnalysisNanos = System.nanoTime()
            lastAnalyzedBitmap = bmp
            lastAnalyzedOrigin = origin
            lastAnalyzedSize = glassSize
        }
    }

    val canBlur = enabled && view.isHardwareAccelerated && percent > 0
    val clarity = appearance.clarityPercent
    val rawParams = remember(percent, clarity, style, dark, canBlur, cachedMetrics) {
        computeGlassParams(
            transparencyPercent = percent,
            style = style,
            isDark = dark,
            canBlur = canBlur,
            metrics = cachedMetrics,
            clarityPercent = clarity
        )
    }

    // R2 & T1 保护时序：当需要增强对比度保护时立即生效，不迟滞；
    // 仅当保护释放或微小抖动时应用迟滞与 180ms 平滑。
    var stableTargetOpacity by remember { mutableFloatStateOf(rawParams.opacity) }
    var stableTargetAmbientOpacity by remember { mutableFloatStateOf(rawParams.ambientOpacity) }
    var stableTargetBlur by remember { mutableStateOf(rawParams.blurRadius) }

    val boostingProtection =
        rawParams.opacity > stableTargetOpacity + 0.001f ||
            rawParams.ambientOpacity > stableTargetAmbientOpacity + 0.001f ||
            percent <= 0 || !canBlur
    if (
        boostingProtection ||
        !shouldApplyHysteresis(
            stableTargetAmbientOpacity,
            rawParams.ambientOpacity,
            stableTargetBlur,
            rawParams.blurRadius
        )
    ) {
        stableTargetOpacity = rawParams.opacity
        stableTargetAmbientOpacity = rawParams.ambientOpacity
        stableTargetBlur = rawParams.blurRadius
    }

    val animatedPeakOpacity by animateFloatAsState(
        targetValue = if (legacy) 1f else stableTargetOpacity,
        animationSpec = if (boostingProtection) snap() else tween(180),
        label = "glassOpacity"
    )
    val animatedAmbientOpacity by animateFloatAsState(
        targetValue = if (legacy) 1f else stableTargetAmbientOpacity,
        animationSpec = if (boostingProtection) snap() else tween(180),
        label = "glassAmbientOpacity"
    )
    val animatedBlur by animateDpAsState(
        targetValue = if (legacy) 0.dp else stableTargetBlur,
        animationSpec = if (percent <= 0 || !canBlur) snap() else tween(180),
        label = "glassBlur"
    )

    // R2 & T1：确保实际生效的主体与峰值不透明度绝不低于安全目标下限
    val effectivePeakOpacity = if (legacy) 1f else maxOf(stableTargetOpacity, animatedPeakOpacity)
    val effectiveAmbientOpacity = if (legacy) 1f else maxOf(stableTargetAmbientOpacity, animatedAmbientOpacity)

    val sourceCells = rawParams.rawCellOpacities ?: rawParams.cellProtectedOpacities
    val minCell = sourceCells?.minOrNull() ?: effectiveAmbientOpacity
    val maxCell = sourceCells?.maxOrNull() ?: effectivePeakOpacity
    val hasLocalVariation = !legacy && sourceCells != null &&
        rawParams.cellCols > 0 && rawParams.cellRows > 0 &&
        maxCell - minCell > 0.015f &&
        effectivePeakOpacity > effectiveAmbientOpacity + 0.005f

    val continuousField = remember(sourceCells, rawParams.cellCols, rawParams.cellRows, rawParams.ambientOpacity, hasLocalVariation) {
        if (hasLocalVariation) {
            buildContinuousProtectionField(
                rawCellOpacities = sourceCells!!,
                cols = rawParams.cellCols,
                rows = rawParams.cellRows,
                ambientOpacity = rawParams.ambientOpacity
            )
        } else {
            null
        }
    }

    val protectionMaskImage = remember(continuousField, stableTargetAmbientOpacity, stableTargetOpacity, color, hasLocalVariation) {
        if (!hasLocalVariation || continuousField == null) {
            null
        } else {
            val rInt = (color.red * 255f + 0.5f).toInt().coerceIn(0, 255)
            val gInt = (color.green * 255f + 0.5f).toInt().coerceIn(0, 255)
            val bInt = (color.blue * 255f + 0.5f).toInt().coerceIn(0, 255)
            val rawMin = rawParams.ambientOpacity
            // 归一化上限取保护场实际峰值与目标 peak 的较大者：cell 级精确求解所需的
            // 保护强度可能超过 peak 启发值，钳回 peak 会把该处对比度压到承诺以下。
            val rawTop = maxOf(rawParams.opacity, continuousField.maxOrNull() ?: rawParams.opacity)
            val rawSpan = (rawTop - rawMin).coerceAtLeast(0.001f)
            val pixels = IntArray(continuousField.size)
            for (i in continuousField.indices) {
                val norm = ((continuousField[i] - rawMin) / rawSpan).coerceIn(0f, 1f)
                // 端点用稳定目标值：mask 是静态位图，用动画值做 key/内容会在
                // 保护释放的 180ms 内每帧重建一张 64×64 Bitmap。
                val alphaF = (stableTargetAmbientOpacity + norm * (stableTargetOpacity - stableTargetAmbientOpacity))
                    .coerceIn(stableTargetAmbientOpacity, 1f)
                val aInt = ceil(alphaF * 255f).toInt().coerceIn(0, 255)
                pixels[i] = (aInt shl 24) or (rInt shl 16) or (gInt shl 8) or bInt
            }
            Bitmap.createBitmap(
                pixels,
                PROTECTION_FIELD_SIZE,
                PROTECTION_FIELD_SIZE,
                Bitmap.Config.ARGB_8888
            ).asImageBitmap()
        }
    }

    LaunchedEffect(
        percent,
        style,
        dark,
        stableTargetAmbientOpacity,
        stableTargetOpacity,
        rawParams.blurRadius,
        rawParams.highlightAlpha,
        rawParams.cellProtectedOpacities
    ) {
        if (source != null) {
            val protectedCells = (rawParams.rawCellOpacities ?: rawParams.cellProtectedOpacities)
                ?.count { it > stableTargetAmbientOpacity + 0.015f } ?: 0
            source.lastComputedAmbientOpacity = stableTargetAmbientOpacity
            source.lastComputedPeakOpacity = stableTargetOpacity
            source.lastComputedBlurDp = rawParams.blurRadius.value
            source.lastComputedHighlightAlpha = rawParams.highlightAlpha
            source.lastProtectedCellCount = protectedCells
            if (com.mirad.reader.BuildConfig.DEBUG) {
                runCatching {
                    android.util.Log.i(
                        "GlassPreview",
                        "glassParams style=$style percent=$percent dark=$dark " +
                            "ambientOpacity=${"%.3f".format(java.util.Locale.US, stableTargetAmbientOpacity)} " +
                            "peakOpacity=${"%.3f".format(java.util.Locale.US, stableTargetOpacity)} " +
                            "protectedCells=$protectedCells " +
                            "blurDp=${"%.1f".format(java.util.Locale.US, rawParams.blurRadius.value)} " +
                            "highlightAlpha=${"%.3f".format(java.util.Locale.US, rawParams.highlightAlpha)}"
                    )
                }
            }
        }
    }

    this.shadow(if (legacy) 0.dp else if (style == GlassStyle.Panel) 8.dp else 6.dp, shape, clip = false)
        .onGloballyPositioned { coordinates ->
            val newOrigin = coordinates.positionOnScreen()
            val newSize = coordinates.size
            val isSeparate = source?.owner?.rootView !== view.rootView
            if (origin != newOrigin) origin = newOrigin
            if (glassSize != newSize) glassSize = newSize
            if (separateWindow != isSeparate) separateWindow = isSeparate
        }.clip(shape).drawWithContent {
            val bitmap = if (separateWindow) (source?.image ?: source?.sharedSnapshot) else source?.image
            val live = source?.layer?.takeIf { !separateWindow && source.sampleLayerReady }
            val blurPx = animatedBlur.toPx()
            val shouldBlur = canBlur && blurPx > 0f && (bitmap != null || live != null)

            if (shouldBlur && source.size.width > 0 && source.size.height > 0) {
                val padding = ceil(blurPx * 2).toInt()
                val delta = origin - source.origin
                background.renderEffect = BlurEffect(blurPx, blurPx, TileMode.Clamp)
                background.record(size = IntSize(size.width.toInt() + padding * 2, size.height.toInt() + padding * 2)) {
                    translate(padding - delta.x, padding - delta.y) {
                        if (bitmap != null) {
                            drawImage(bitmap, dstOffset = IntOffset.Zero, dstSize = source.size)
                        } else if (live != null) {
                            drawLayer(live)
                        }
                    }
                }
                translate(-padding.toFloat(), -padding.toFloat()) { drawLayer(background) }
            }

            // T1 & T1.1 & T1.2：
            // - 无局部实心差异时（如普通文本/卡片页面或同色背景），直接单次 drawRect 绘制均匀玻璃底色，彻底杜绝网格或条带；
            // - 存在混色差异时（如白底蓝块），通过单张连续平滑的 Box SDF + 高斯保护场位图单次 drawImage 合成，
            //   在受保护区域内部及边界保持 100% 平坦达标，在外围无痕平滑过渡，消除径向衰减凹陷与矩形裁切重叠伪影。
            if (protectionMaskImage != null) {
                drawImage(
                    image = protectionMaskImage,
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(
                        ceil(size.width).toInt().coerceAtLeast(1),
                        ceil(size.height).toInt().coerceAtLeast(1)
                    ),
                    filterQuality = FilterQuality.Medium
                )
            } else {
                val uniformOpacity = if (legacy) 1f else maxOf(effectiveAmbientOpacity, effectivePeakOpacity).coerceIn(0f, 1f)
                drawRect(color.copy(alpha = uniformOpacity))
            }

            // T1：将高光收敛到顶部边缘渐变，避免在整块面板表面铺上白雾
            if (!legacy && percent > 0 && rawParams.highlightAlpha > 0f) {
                drawRect(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to Color.White.copy(alpha = rawParams.highlightAlpha),
                            0.28f to Color.White.copy(alpha = rawParams.highlightAlpha * 0.18f),
                            0.55f to Color.Transparent,
                            1.0f to Color.Transparent
                        )
                    )
                )
            }

            drawContent()

            // 边缘质感：方向性高光 + 内侧衰减带（见 GlassEdgeTexture），
            // 与 GPU 路径共用同一份实现，两条路径的边界观感保持一致。
            // 顶部柔光由上面的 highlightAlpha 负责，这里不再重复叠加。
            if (!legacy && edgeTexture) {
                drawGlassEdgeTexture(
                    outline = shape.createOutline(size, layoutDirection, this),
                    surface = color,
                    clarity = clarity,
                    tintAlpha = 1f,
                    topSheen = false
                )
            }
        }
}

@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    style: GlassStyle = GlassStyle.Toolbar,
    shape: Shape = MaterialTheme.shapes.large,
    content: @Composable BoxScope.() -> Unit
) {
    Surface(
        modifier = modifier.glassBackground(style, shape),
        shape = shape,
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Box(content = content)
    }
}

@Composable
fun GlassDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.glassBackground(GlassStyle.Panel, MaterialTheme.shapes.large),
        shape = MaterialTheme.shapes.large,
        containerColor = Color.Transparent,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        content = content
    )
}
