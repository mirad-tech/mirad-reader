package com.mirad.reader.preview

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.mirad.reader.core.model.ReaderConfig
import com.mirad.reader.core.model.ThemeKey
import com.mirad.reader.core.model.ThemePaletteResolver
import com.mirad.reader.core.reader.ReaderCss
import com.mirad.reader.reader.ReaderWebView
import com.mirad.reader.ui.common.*
import com.mirad.reader.ui.reader.ReaderBackdropHost
import com.mirad.reader.ui.reader.WebViewGlassBackdrop
import com.mirad.reader.ui.theme.MiradTheme
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Isolated, release-optimized device gate, compiled only by the gpuPreview variant. */
class ReaderGpuPreviewActivity : ComponentActivity() {
    private var mode by mutableStateOf("gpu")
    private var dark by mutableStateOf(false)
    private var raw by mutableStateOf(false)
    private var liquidEnabled by mutableStateOf(true)
    private var panel by mutableStateOf(true)
    private var percent by mutableIntStateOf(100)
    private var ready by mutableStateOf(false)
    private var recording by mutableStateOf(false)
    private var chapter = 0
    private var requestedScroll = 0
    private lateinit var host: ReaderBackdropHost
    private lateinit var web: ReaderWebView
    private var oldSource: GlassBackdropState? = null
    private val handler = Handler(Looper.getMainLooper())
    private val frames = ArrayList<JSONObject>()
    private val frameLock = Any()
    @Volatile private var collectingFrames = false
    private var collectingFromNanos = 0L
    private var metricsThread: HandlerThread? = null
    private var startCaptures = 0L
    private var startRecords = 0L
    private var droppedCallbacks = 0
    private var lastReport = "{}"
    private var lastRunReport: String? = null
    private var lastRunEnded = 0L
    private var activeRun: ProbeRun? = null
    private val panelBounds = android.graphics.RectF()
    private val frameListener = Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
        if (collectingFrames && metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 0L) {
            val intended = metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)
            synchronized(frameLock) {
                if (collectingFrames && intended >= collectingFromNanos) {
                    droppedCallbacks += dropped
                    frames += JSONObject().put("intendedVsyncNs", intended)
                        .put("vsyncNs", metrics.getMetric(FrameMetrics.VSYNC_TIMESTAMP))
                        .put("totalNs", metrics.getMetric(FrameMetrics.TOTAL_DURATION))
                        .put("deadlineNs", metrics.getMetric(FrameMetrics.DEADLINE))
                }
            }
        }
    }
    private val finishRecording = Runnable { finishRun(true) }
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(lastRunReport ?: lastReport) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT < 31) {
            setContentView(TextView(this).apply { text = "GPU 玻璃验证需要 Android 12 或更新版本" })
            return
        }
        readOptions(intent)
        enableEdgeToEdge()
        window.attributes = window.attributes.apply { preferredRefreshRate = 120f }
        host = ReaderBackdropHost(this)
        web = ReaderWebView(this).apply {
            settings.javaScriptEnabled = true
            settings.blockNetworkLoads = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    view.evaluateJavascript("window.scrollTo(0,$requestedScroll)", null)
                    view.postVisualStateCallback(1L, object : WebView.VisualStateCallback() {
                        override fun onComplete(requestId: Long) {
                            ready = true
                            Log.i(TAG, "ready mode=$mode raw=$raw chapter=$chapter scroll=$requestedScroll")
                        }
                    })
                }
            }
        }
        val overlay = ComposeView(this).apply { setContent { ProbeUi() } }
        host.setPlanes(web, overlay)
        setContentView(host)
        loadChapter()
        metricsThread = HandlerThread("MiradProbeFrameMetrics").also {
            it.start()
            window.addOnFrameMetricsAvailableListener(frameListener, Handler(it.looper))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!::web.isInitialized) return
        setIntent(intent)
        val previousDark = dark
        val previousMode = mode
        readOptions(intent)
        if (previousMode != mode) stopAnimation()
        if (previousDark != dark) applyTheme()
        when (intent.getStringExtra("action")) {
            "report" -> report(false, requestId = intent.getStringExtra("requestId"))
            "scroll" -> web.evaluateJavascript("window.scrollTo(0,$requestedScroll)", null)
            "chapter" -> { chapter++; loadChapter() }
            "image" -> insertImage()
            "record" -> startRun()
            "animate" -> animateScroll()
        }
    }

    private fun readOptions(options: Intent) {
        if (!recording) {
            options.getStringExtra("mode")?.takeIf { it in listOf("off", "old", "gpu") }?.let { mode = it }
            if (options.hasExtra("dark")) dark = options.getBooleanExtra("dark", false)
            if (options.hasExtra("raw")) raw = options.getBooleanExtra("raw", false)
            if (options.hasExtra("liquid")) liquidEnabled = options.getBooleanExtra("liquid", true)
            if (options.hasExtra("panel")) panel = options.getBooleanExtra("panel", true)
            if (options.hasExtra("percent")) percent = options.getIntExtra("percent", 100).coerceIn(0, 100)
        }
        if (options.hasExtra("scroll")) requestedScroll = options.getIntExtra("scroll", 0).coerceAtLeast(0)
    }

    @RequiresApi(31)
    @Composable
    private fun ProbeUi() {
        MiradTheme(darkTheme = dark) {
            CompositionLocalProvider(
                // 探针保留历史百分比语义：0 关闭，其余值迁移为开启时的清晰度。
                LocalGlassAppearance provides GlassAppearance.fromLegacyPercent(percent.toString()),
                LocalContentColor provides MaterialTheme.colorScheme.onSurface
            ) {
                GlassBackdropHost {
                    val source = LocalGlassBackdrop.current
                    SideEffect {
                        oldSource = source
                        host.gpuEnabled = mode == "gpu" && panel && percent > 0 && source?.enabled == true
                        host.backdropColor = palette().background
                        WindowCompat.getInsetsController(window, host).apply {
                            isAppearanceLightStatusBars = !dark
                            isAppearanceLightNavigationBars = !dark
                        }
                    }
                    WebViewGlassBackdrop(web, visible = mode == "old" && panel, revision = listOf(chapter, dark, ready))
                    Box(Modifier.fillMaxSize()) {
                        Surface(Modifier.align(Alignment.TopCenter).fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
                            Column(Modifier.statusBarsPadding().padding(horizontal = 8.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                    listOf("off" to "关闭玻璃", "old" to "旧玻璃", "gpu" to "GPU 玻璃").forEach { (key, label) ->
                                        TextButton(onClick = { stopAnimation(); mode = key }, enabled = !recording) { Text(if (mode == key) "● $label" else label) }
                                    }
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                    TextButton(onClick = { dark = !dark; applyTheme() }, enabled = !recording) { Text(if (dark) "夜间" else "日间") }
                                    TextButton(onClick = { chapter++; loadChapter() }, enabled = !recording) { Text("换章") }
                                    TextButton(onClick = { insertImage() }, enabled = !recording) { Text("加载图片") }
                                    TextButton(onClick = { panel = !panel }, enabled = !recording) { Text(if (panel) "收起" else "展开") }
                                }
                                Text(if (ready) "READY · ${Build.MODEL}${if (source?.enabled != true) " · 简化模式" else ""}" else "加载中", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        if (panel) ProbePanel()
                        Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
                            Row(Modifier.navigationBarsPadding(), horizontalArrangement = Arrangement.Center) {
                                TextButton(onClick = { startRun() }, enabled = ready && !recording) { Text(if (recording) "采样中" else "记录 30 秒") }
                                TextButton(onClick = {
                                    if (lastRunReport != null) export.launch("mirad-gpu-probe.json")
                                    else report(false) { export.launch("mirad-gpu-probe.json") }
                                }, enabled = !recording) { Text("保存结果") }
                            }
                        }
                    }
                }
            }
        }
    }

    @RequiresApi(31)
    @Composable
    private fun BoxScope.ProbePanel() {
        var position by remember { mutableStateOf(Offset.Zero) }
        var dragY by remember { mutableFloatStateOf(0f) }
        val renderer = remember { ProbeGlassRenderer() }
        DisposableEffect(renderer) { onDispose { renderer.release() } }
        val tint = MaterialTheme.colorScheme.surface.toArgb()
        val capable = LocalGlassBackdrop.current?.enabled == true
        val shape = RoundedCornerShape(24.dp)
        val modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 18.dp).padding(bottom = 90.dp)
            .offset { IntOffset(0, dragY.roundToInt()) }.fillMaxWidth().height(250.dp)
            .onGloballyPositioned {
                position = it.positionInWindow()
                val screen = it.positionOnScreen()
                panelBounds.set(screen.x, screen.y, screen.x + it.size.width, screen.y + it.size.height)
            }
            .pointerInput(Unit) { detectDragGestures { change, delta -> change.consume(); dragY = (dragY + delta.y).coerceIn(-600f, 120f) } }
        val content: @Composable () -> Unit = {
            if (!raw) Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("阅读设置", style = MaterialTheme.typography.titleMedium)
                Text("玻璃透明度：$percent%", style = MaterialTheme.typography.bodyMedium)
                Slider(value = percent.toFloat(), onValueChange = { percent = it.roundToInt() }, valueRange = 0f..100f, enabled = !recording)
                Text("日间／夜间", style = MaterialTheme.typography.bodyMedium)
                Text("字体 · 排版 · 背景", style = MaterialTheme.typography.bodyMedium)
            }
        }
        when {
            mode == "gpu" && capable -> Box(modifier.clip(shape).drawWithContent {
                drawIntoCanvas { canvas ->
                    val location = renderer.hostLocation
                    host.getLocationInWindow(location)
                    renderer.draw(canvas.nativeCanvas, host.contentLayer, position.x - location[0], position.y - location[1], size.width, size.height, density, tint, percent, raw, liquidEnabled)
                }
                drawContent()
            }) { content() }
            // raw 先于 old 判定：否则 old 模式会把 raw 对照组也套上真实玻璃材质，基线测量被污染。
            raw -> Box(modifier) // Raw comparison deliberately adds no material or foreground.
            mode == "old" -> GlassSurface(modifier = modifier, style = GlassStyle.Panel, shape = shape) { content() }
            else -> Surface(modifier, shape = shape, color = MaterialTheme.colorScheme.surface) { content() }
        }
    }

    private fun config() = ReaderConfig.DEFAULT.copy(theme = if (dark) ThemeKey.DARK else ThemeKey.PAPER, fontSizeSp = 20f)
    private fun palette() = ThemePaletteResolver.resolve(config(), dark)

    private fun loadChapter() {
        stopAnimation()
        ready = false
        val structural = assets.open("reader/reader.css").bufferedReader().use { it.readText() }
        val javascript = assets.open("reader/reader.js").bufferedReader().use { it.readText() }
        val css = ReaderCss.themeCss(config(), palette())
        // The same text fixture used by the existing native reader verification.
        val paragraphs = listOf("雪下了整整一夜，官道上的车辙被填得平平整整，连马蹄印都寻不见半点痕迹。", "沈砚把行囊往肩上一甩，回头望了一眼城门上那盏昏黄的灯笼，没再停留。", "他怀里揣着半块冷透的胡饼，那是母亲昨夜塞给他的，说是路上压压饥。")
        val body = (0..79).joinToString("") { index ->
            val marker = if (index % 4 == 0) "<div style='height:12px;background:${if ((index / 4 + chapter) % 2 == 0) "#237bd4" else "#e5ac36"}'></div>" else ""
            "$marker<p>${paragraphs[(index + chapter) % 3]}</p>"
        }
        val html = "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'><style>$structural</style><style id='mirad-theme'>$css</style></head><body><div id='mirad-content' data-chapter='$chapter'><h2>第${chapter + 1}章 雪夜出城</h2>$body</div><script>window.MiradBridge={onReady(){},onState(){},onMetrics(){},onSelection(){}};$javascript;MiradReader.init({mode:'SCROLL',anim:'NONE',pad:24,padTop:130,padBottom:70,windowedScroll:false,chapterCount:1});</script></body></html>"
        web.loadDataWithBaseURL("https://appassets.androidplatform.net/gpu-probe/", html, "text/html", "utf-8", null)
    }

    private fun applyTheme() {
        val css = ReaderCss.themeCss(config(), palette())
        web.evaluateJavascript("MiradReader.setThemeCssOnly(${JSONObject.quote(css)},{mode:'SCROLL',anim:'NONE',pad:24,padTop:130,padBottom:70,windowedScroll:false,chapterCount:1})", null)
    }

    private fun insertImage() {
        val svg = "<svg xmlns='http://www.w3.org/2000/svg' width='600' height='180'><rect width='300' height='180' fill='#237bd4'/><rect x='300' width='300' height='180' fill='#e5ac36'/></svg>"
        web.evaluateJavascript("setTimeout(()=>{let i=document.createElement('img');i.src='data:image/svg+xml,'+encodeURIComponent(${JSONObject.quote(svg)});document.querySelector('p').after(i)},700)", null)
    }

    private fun animateScroll() {
        stopAnimation()
        web.evaluateJavascript("{let token=window.__probeGeneration,start=performance.now(),base=scrollY;function tick(t){let age=t-start;if(token!==window.__probeGeneration||age>30000)return;window.scrollTo(0,base+Math.round((1-Math.cos(age/900))*600));window.__probeRaf=requestAnimationFrame(tick)}window.__probeRaf=requestAnimationFrame(tick)}", null)
    }

    private fun stopAnimation() {
        if (::web.isInitialized) web.evaluateJavascript("window.__probeGeneration=(window.__probeGeneration||0)+1;cancelAnimationFrame(window.__probeRaf||0);window.__probeRaf=0", null)
    }

    private fun startRun() {
        if (recording || !ready) return
        startCaptures = oldSource?.captureAttempts ?: 0L
        startRecords = host.contentRecords
        activeRun = ProbeRun(mode, dark, raw, liquidEnabled, percent, System.nanoTime(), web.display?.refreshRate ?: 0f)
        synchronized(frameLock) {
            frames.clear()
            droppedCallbacks = 0
            collectingFromNanos = activeRun!!.startedNs
            collectingFrames = true
        }
        recording = true
        handler.postDelayed(finishRecording, 30_000)
    }

    private fun finishRun(complete: Boolean) {
        if (!recording) return
        recording = false
        synchronized(frameLock) { collectingFrames = false }
        handler.removeCallbacks(finishRecording)
        val completed = activeRun
        activeRun = null
        report(complete, completed)
    }

    private fun report(complete: Boolean, run: ProbeRun? = null, requestId: String? = null, after: () -> Unit = {}) {
        if (!::web.isInitialized) return
        // Freeze all run metadata before evaluateJavascript's asynchronous callback.
        val ended = System.nanoTime()
        val totalCaptures = oldSource?.captureAttempts ?: 0L
        val totalRecords = host.contentRecords
        val frozenFrames = synchronized(frameLock) {
            (if (run != null) frames.toList() else emptyList<JSONObject>()) to (if (run != null) droppedCallbacks else 0)
        }
        val result = JSONObject().put("schema", 1).put("model", Build.MODEL).put("sdk", Build.VERSION.SDK_INT)
            .put("requestId", requestId ?: JSONObject.NULL)
            .put("ready", ready).put("requestedRefreshRateHz", 120)
            .put("panelBounds", JSONArray(listOf(panelBounds.left, panelBounds.top, panelBounds.right, panelBounds.bottom)))
            .put("density", resources.displayMetrics.density)
            .put("mode", run?.mode ?: mode).put("dark", run?.dark ?: dark).put("raw", run?.raw ?: raw)
            .put("liquid", run?.liquid ?: liquidEnabled)
            .put("percent", run?.percent ?: percent).put("refreshRateHz", run?.refreshRate ?: (web.display?.refreshRate ?: 0f))
            .put("kind", if (run != null) "timed-run" else "instant-diagnostic")
            .put("startedNs", run?.startedNs ?: ended).put("endedNs", ended)
            .put("complete30SecondRun", complete && run != null)
            .put("hardwareCanvas", host.lastHardwareCanvas).put("contentRecords", totalRecords)
            .put("gpuActive", host.gpuEnabled)
            .put("totalCpuDisplayCaptures", totalCaptures)
            .put("runContentRecords", if (run != null) totalRecords - startRecords else JSONObject.NULL)
            .put("runCpuDisplayCaptures", if (run != null) totalCaptures - startCaptures else JSONObject.NULL)
            .put("oldBackdrop", oldSource?.statsSummary())
            .put("frameMetrics", JSONArray(frozenFrames.first))
            .put("droppedFrameMetricCallbacks", frozenFrames.second)
            .put("acceptance", "FrameMetrics and host recording counts are diagnostic only; target approval requires visual freshness and FrameTimeline evidence.")
        web.evaluateJavascript("JSON.stringify({scrollY:scrollY,text:document.body.innerText.length,chapter:document.getElementById('mirad-content').dataset.chapter,imageCount:document.images.length,loadedImages:Array.from(document.images).filter(i=>i.complete&&i.naturalWidth>0).length})") { state ->
            result.put("webStateAfterRun", state)
            lastReport = result.toString(2)
            File(getExternalFilesDir(null), "gpu-probe.json").writeText(lastReport)
            if (run != null) {
                File(getExternalFilesDir(null), "gpu-run-${run.startedNs}.json").writeText(lastReport)
                if (ended >= lastRunEnded) { lastRunEnded = ended; lastRunReport = lastReport }
            }
            Log.i(TAG, "report mode=${result.getString("mode")} hardware=${result.getBoolean("hardwareCanvas")} records=$totalRecords totalCpuCaptures=$totalCaptures kind=${result.getString("kind")} web=$state")
            after()
        }
    }

    override fun onPause() {
        stopAnimation()
        finishRun(false)
        if (::web.isInitialized) web.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::web.isInitialized) web.onResume()
    }

    override fun onStop() {
        stopAnimation()
        finishRun(false)
        super.onStop()
    }

    override fun onDestroy() {
        collectingFrames = false
        if (metricsThread != null) window.removeOnFrameMetricsAvailableListener(frameListener)
        metricsThread?.quitSafely()
        metricsThread = null
        handler.removeCallbacksAndMessages(null)
        if (::host.isInitialized) host.removeAllViews()
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }

    companion object { private const val TAG = "ReaderGpuProbe" }
}

private data class ProbeRun(val mode: String, val dark: Boolean, val raw: Boolean, val liquid: Boolean, val percent: Int, val startedNs: Long, val refreshRate: Float)

/** Prototype consumer: records the hardware node, never calls View.draw into a Bitmap. */
@RequiresApi(31)
private class ProbeGlassRenderer {
    val hostLocation = IntArray(2)
    private val layer = RenderNode("MiradProbeGlass")
    private val clip = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var lastBlur = -1f
    private var blur: RenderEffect? = null
    private val liquid = if (Build.VERSION.SDK_INT >= 33) ProbeLiquidEffect() else null

    fun draw(canvas: Canvas, content: RenderNode, x: Float, y: Float, width: Float, height: Float, density: Float, tint: Int, percent: Int, raw: Boolean, liquidEnabled: Boolean) {
        val radius = 24 * density
        val blurRadius = if (raw || percent == 0) 0f else 14 * density
        val padding = ceil(blurRadius * 2).toInt()
        if (canvas.isHardwareAccelerated && content.hasDisplayList() && percent > 0) {
            if (blurRadius != lastBlur) {
                lastBlur = blurRadius
                blur = if (blurRadius > 0) RenderEffect.createBlurEffect(blurRadius, blurRadius, Shader.TileMode.CLAMP) else null
            }
            val recording = layer.beginRecording(ceil(width).toInt() + padding * 2, ceil(height).toInt() + padding * 2)
            try {
                recording.translate(padding - x, padding - y)
                recording.drawRenderNode(content)
            } finally { layer.endRecording() }
            layer.setPosition(0, 0, ceil(width).toInt() + padding * 2, ceil(height).toInt() + padding * 2)
            layer.setRenderEffect(if (!raw && liquidEnabled && Build.VERSION.SDK_INT >= 33) liquid?.effect(width, height, radius, padding.toFloat(), density, blur) else blur)
            val save = canvas.save()
            clip.reset()
            clip.addRoundRect(0f, 0f, width, height, radius, radius, Path.Direction.CW)
            canvas.clipPath(clip)
            canvas.translate(-padding.toFloat(), -padding.toFloat())
            canvas.drawRenderNode(layer)
            canvas.restoreToCount(save)
        }
        if (!raw) {
            val alpha = if (percent == 0) 1f else (1f - percent / 100f).coerceAtLeast(.02f)
            paint.color = (tint and 0x00ffffff) or ((alpha * 255).roundToInt() shl 24)
            paint.style = Paint.Style.FILL
            canvas.drawRoundRect(0f, 0f, width, height, radius, radius, paint)
            paint.color = 0x55ffffff
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = density
            canvas.drawRoundRect(density / 2, density / 2, width - density / 2, height - density / 2, radius, radius, paint)
        }
    }

    fun release() { layer.discardDisplayList() }
}

@RequiresApi(33)
private class ProbeLiquidEffect {
    private val shader = RuntimeShader("""
        uniform shader content;
        uniform float2 size;
        uniform float radius;
        uniform float padding;
        uniform float rim;
        uniform float strength;
        float sd(float2 p) {
            float2 q = abs(p - size * 0.5) - (size * 0.5 - radius);
            return length(max(q, 0.0)) + min(max(q.x,q.y),0.0) - radius;
        }
        half4 main(float2 coord) {
            float2 p = coord - padding;
            float distance = sd(p);
            float weight = 1.0 - smoothstep(0.0, rim, max(-distance, 0.0));
            float2 gradient = float2(sd(p+float2(0.5,0.0))-sd(p-float2(0.5,0.0)),sd(p+float2(0.0,0.5))-sd(p-float2(0.0,0.5)));
            float2 normal = gradient / max(length(gradient),0.001);
            return content.eval(coord + normal * strength * weight * weight);
        }
    """.trimIndent())
    private var lastBlur: RenderEffect? = null
    private var lastWidth = -1f
    private var lastHeight = -1f
    private var lastRadius = -1f
    private var lastPadding = -1f
    private var lastDensity = -1f
    private var chain: RenderEffect? = null

    fun effect(width: Float, height: Float, radius: Float, padding: Float, density: Float, blur: RenderEffect?): RenderEffect {
        if (width == lastWidth && height == lastHeight && radius == lastRadius && padding == lastPadding && density == lastDensity && lastBlur === blur) {
            chain?.let { return it }
        }
        shader.setFloatUniform("size", width, height)
        shader.setFloatUniform("radius", radius)
        shader.setFloatUniform("padding", padding)
        shader.setFloatUniform("rim", 8 * density)
        shader.setFloatUniform("strength", 1.5f * density)
        // RenderEffect snapshots the RuntimeShader builder; update uniforms before creating it.
        val shaderEffect = RenderEffect.createRuntimeShaderEffect(shader, "content")
        lastWidth = width
        lastHeight = height
        lastRadius = radius
        lastPadding = padding
        lastDensity = density
        lastBlur = blur
        return (if (blur == null) shaderEffect else RenderEffect.createChainEffect(shaderEffect, blur)).also { chain = it }
    }
}
