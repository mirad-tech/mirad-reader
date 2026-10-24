package com.mirad.reader.preview

import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.webkit.WebViewAssetLoader
import com.mirad.reader.core.model.*
import com.mirad.reader.core.reader.ReaderCss
import com.mirad.reader.data.db.entity.ChapterEntity
import com.mirad.reader.reader.AutoReadState
import com.mirad.reader.reader.ReaderJsBridge
import com.mirad.reader.reader.ReaderKeys
import com.mirad.reader.reader.ReaderWebView
import com.mirad.reader.reader.SelectionAction
import com.mirad.reader.ui.common.*
import com.mirad.reader.ui.reader.*
import com.mirad.reader.ui.settings.ReadingSettingsPage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import kotlin.math.roundToInt

/** Memory-only acceptance scene. It shares the production native planes and modal portal. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HardwareReaderPreview(
    config: ReaderConfig, systemDark: Boolean, isEpub: Boolean, settings: Boolean,
    settingsPage: ReadingSettingsPage?, navigationGeneration: Int, chrome: Boolean, dialog: Boolean,
    probe: ImmersionReaderProbe, onChange: ((ReaderConfig) -> ReaderConfig) -> Unit,
    onChrome: (Boolean) -> Unit, onDialog: (Boolean) -> Unit, onSettings: () -> Unit,
    onDismissSettings: () -> Unit, onBack: () -> Unit
) {
    val activity = LocalContext.current as ComponentActivity
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val appearanceController = LocalAppAppearanceController.current
    val palette = ThemePaletteResolver.resolve(config, systemDark)
    val policy = ReaderWindowPolicy(config.fullScreen, palette.isDark)
    ApplyReaderWindowPolicy(activity.window, policy)

    val controller = remember { ReaderHardwareSceneController() }
    var ready by remember { mutableStateOf(false) }
    var chapter by remember { mutableIntStateOf(0) }
    var page by remember { mutableIntStateOf(0) }
    var pages by remember { mutableIntStateOf(1) }
    var offset by remember { mutableIntStateOf(0) }
    var textLength by remember { mutableIntStateOf(1) }
    var insetLeft by remember { mutableIntStateOf(-1) }
    var panel by remember { mutableStateOf(if (settings) "settings" else if (dialog) "note" else "none") }
    var modalVisible by remember { mutableStateOf(false) }
    var noteDraft by remember { mutableStateOf("") }
    var selectedText by remember { mutableStateOf("雪下了整整一夜，官道上的车辙被填得平平整整。") }
    var query by remember { mutableStateOf("") }
    var searchResult by remember { mutableStateOf<Int?>(null) }
    val notes = remember { mutableStateListOf(1, 2) }
    var notesTarget by remember { mutableStateOf<Int?>(null) }
    var noteDetails by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf(false) }
    var notesDeleteCount by remember { mutableIntStateOf(0) }
    var lastDeletedTarget by remember { mutableStateOf<Int?>(null) }
    var noteSaveCount by remember { mutableIntStateOf(0) }
    var tapCount by remember { mutableIntStateOf(0) }
    var volumeTurns by remember { mutableIntStateOf(0) }
    var autoState by remember { mutableStateOf(AutoReadState.OFF) }
    var animationRunning by remember { mutableStateOf(false) }
    var animationToken by remember { mutableIntStateOf(0) }
    var animationJob by remember { mutableStateOf<Job?>(null) }
    var coverJob by remember { mutableStateOf<Job?>(null) }
    var coverGeneration by remember { mutableIntStateOf(0) }
    var coverBitmap by remember { mutableStateOf<Bitmap?>(null) }
    val coverX = remember { Animatable(0f) }
    val ownedBitmaps = remember { mutableSetOf<Bitmap>() }
    var snapshotConfig by remember { mutableStateOf<String?>(null) }
    var snapshotWidth by remember { mutableIntStateOf(0) }
    var snapshotHeight by remember { mutableIntStateOf(0) }
    var snapshotSerial by remember { mutableIntStateOf(0) }
    var snapshotStatus by remember { mutableStateOf("idle") }
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    val chapters = remember {
        List(6) { ChapterEntity(bookId = 1, index = it, title = "第${it + 1}章 雪夜出城", charCount = 6000) }
    }
    val percent = ((chapter + offset.toFloat() / textLength.coerceAtLeast(1)) / chapters.size).coerceIn(0f, 1f)

    val safe = readerSafeInsets().asPaddingValues()
    val direction = LocalLayoutDirection.current
    val top = if (config.showHeader) 24 else 0
    val bottom = safe.calculateBottomPadding().value.roundToInt() + if (config.showFooter) 24 else 0
    val horizontal = maxOf(config.horizontalPaddingDp,
        readerHorizontalInsetDp(safe.calculateLeftPadding(direction)),
        readerHorizontalInsetDp(safe.calculateRightPadding(direction)))
    val clipTop = 0
    val clipBottom = 0
    val layout = JSONObject().put("mode", config.pageMode).put("anim", config.pageAnim)
        .put("pad", horizontal).put("padTop", top).put("padBottom", bottom)
        .put("clipTop", clipTop).put("clipBottom", clipBottom)
        .put("colMax", (config.fontSizeSp * config.maxColumnWidthEm).roundToInt())
        .put("windowedScroll", false).put("chapterCount", chapters.size)
    val themeCss = ReaderCss.themeCss(config, palette,
        backgroundImageUrl = BuiltinBackgrounds.fromUri(config.backgroundImageUri)?.assetName
            ?.let { "https://appassets.androidplatform.net/assets/$it" })
    val latestLayout by rememberUpdatedState(layout.toString())
    val latestCss by rememberUpdatedState(themeCss)
    val latestConfig by rememberUpdatedState(config)
    val structural = remember(context) { context.assets.open("reader/reader.css").bufferedReader().use { it.readText() } }
    val script = remember(context) { context.assets.open("reader/reader.js").bufferedReader().use { it.readText() } }

    fun html(index: Int): String {
        val paragraphs = listOf(
            "雪下了整整一夜，官道上的车辙被填得平平整整，连马蹄印都寻不见半点痕迹。",
            "沈砚把行囊往肩上一甩，回头望了一眼城门上那盏昏黄的灯笼，没再停留。",
            "他怀里揣着半块冷透的胡饼，那是母亲昨夜塞给他的，说是路上压压饥。")
        val body = (0..65).joinToString("") { "<p>${paragraphs[it % 3]}</p>" }
        return """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><style>$structural</style><style id="mirad-theme">$latestCss</style></head><body><div id="mirad-content" data-chapter="$index"><h2>第${index + 1}章 雪夜出城</h2>$body</div><script>$script;MiradReader.init($latestLayout);</script></body></html>"""
    }
    val latestHtml by rememberUpdatedState<(Int) -> String>({ html(it) })

    fun evaluate(js: String) { probe.view?.evaluateJavascript(js, null) }
    fun stopAnimation(paused: Boolean = true) {
        animationToken++
        animationJob?.cancel()
        animationJob = null
        animationRunning = false
        if (autoState == AutoReadState.RUNNING) autoState = if (paused) AutoReadState.PAUSED else AutoReadState.OFF
        evaluate("window.__hardwareProbeToken=(window.__hardwareProbeToken||0)+1;if(window.__hardwareProbeRaf)cancelAnimationFrame(window.__hardwareProbeRaf);")
    }
    fun startAnimation(durationMs: Int) {
        stopAnimation(false)
        val duration = durationMs.coerceIn(1, 30000)
        val generation = ++animationToken
        animationRunning = true
        evaluate("""(function(){
            const token=window.__hardwareProbeToken=(window.__hardwareProbeToken||0)+1;
            let start=null,lastPage=0;
            function frame(now){
                if(token!==window.__hardwareProbeToken)return;
                if(start===null)start=now;
                const elapsed=now-start;
                if(elapsed>=$duration){window.__hardwareProbeRaf=0;return;}
                const s=JSON.parse(MiradReader.getState());
                if(s.mode==='SCROLL'){
                    const max=Math.max(0,document.documentElement.scrollHeight-innerHeight);
                    const phase=(elapsed%12000)/6000;
                    scrollTo(0,max*(phase<=1?phase:2-phase));
                }else if(elapsed-lastPage>=800){
                    MiradReader.gotoPage((s.page+1)%s.pages,false);lastPage=elapsed;
                }
                window.__hardwareProbeRaf=requestAnimationFrame(frame);
            }
            window.__hardwareProbeRaf=requestAnimationFrame(frame);
        })();""")
        animationJob = scope.launch { delay(duration.toLong() + 100); if (animationToken == generation) animationRunning = false }
    }
    fun releaseBitmap(bitmap: Bitmap?) {
        if (bitmap == null) return
        if (coverBitmap === bitmap) coverBitmap = null
        val view = probe.view
        if (view?.isAttachedToWindow == true) view.postOnAnimation {
            view.postOnAnimation { if (!bitmap.isRecycled) bitmap.recycle(); ownedBitmaps.remove(bitmap) }
        } else { if (!bitmap.isRecycled) bitmap.recycle(); ownedBitmaps.remove(bitmap) }
    }
    fun hideCover() {
        coverGeneration++
        coverJob?.cancel()
        coverJob = null
        releaseBitmap(coverBitmap)
        snapshotStatus = "hidden"
    }
    fun cover(direction: Int = 1, durationMs: Int = 260, hold: Boolean = false) {
        hideCover()
        val generation = coverGeneration
        coverJob = scope.launch {
            snapshotStatus = "capturing"
            val bitmap = controller.capturePage()
            if (bitmap == null) { snapshotStatus = "failed"; coverJob = null; return@launch }
            if (generation != coverGeneration) { releaseBitmap(bitmap); return@launch }
            ownedBitmaps += bitmap
            coverBitmap = bitmap
            snapshotConfig = bitmap.config?.name
            snapshotWidth = bitmap.width
            snapshotHeight = bitmap.height
            snapshotSerial++
            coverX.snapTo(0f)
            evaluate(if (direction >= 0) "MiradReader.nextPage();" else "MiradReader.prevPage();")
            snapshotStatus = if (hold) "held" else "animating"
            if (hold) { coverJob = null; return@launch }
            try {
                val distance = (if (direction >= 0) -1 else 1) * (probe.view?.width ?: bitmap.width).toFloat()
                coverX.animateTo(distance, tween(durationMs.coerceIn(1, 3000)))
                if (generation == coverGeneration) snapshotStatus = "finished"
            } finally {
                releaseBitmap(bitmap)
                if (generation == coverGeneration) coverJob = null
            }
        }
    }
    fun loadChapter(index: Int) {
        stopAnimation()
        hideCover()
        chapter = index.coerceIn(chapters.indices)
        ready = false
        probe.view?.loadDataWithBaseURL("https://appassets.androidplatform.net/hardware/chapter/$chapter",
            latestHtml(chapter), "text/html", "utf-8", null)
    }
    fun closePanel() {
        panel = "none"; noteDetails = false; deleteConfirm = false; notesTarget = null
        if (settings) onDismissSettings()
        if (dialog) onDialog(false)
    }
    fun openPanel(name: String) {
        require(name in setOf("none", "toc", "progress", "search", "note", "notes", "autoread", "settings"))
        stopAnimation()
        if (name == "none") { closePanel(); return }
        noteDetails = false; deleteConfirm = false; notesTarget = null
        if (name == "note") noteDraft = ""
        panel = name
        if (name == "settings") onSettings()
        else if (settings) onDismissSettings()
        if (name != "note" && dialog) onDialog(false)
    }

    val latestTap by rememberUpdatedState<(Float, Float) -> Unit>({ _, _ ->
        if (!modalVisible) { tapCount++; onChrome(!chrome) }
    })
    val latestCommand by rememberUpdatedState<(Intent) -> Unit>({ command ->
        when (command.getStringExtra("operation")) {
            "panel" -> openPanel(command.getStringExtra("name") ?: "none")
            "cover" -> cover(command.getIntExtra("direction", 1), command.getIntExtra("durationMs", 260), command.getBooleanExtra("hold", false))
            "hide-cover" -> hideCover()
            "chapter" -> loadChapter(command.getIntExtra("index", (chapter + 1) % chapters.size))
            "image" -> {
                val svg = "<svg xmlns='http://www.w3.org/2000/svg' width='320' height='180'><rect width='320' height='180' fill='#719aae'/></svg>"
                evaluate("""setTimeout(function(){let img=document.createElement('img');img.src='data:image/svg+xml,'+encodeURIComponent(${JSONObject.quote(svg)});document.querySelector('#mirad-content p').after(img)},120);""")
            }
            "animate" -> startAnimation(command.getIntExtra("durationMs", 30000))
            "stop-animation" -> stopAnimation()
        }
    })
    SideEffect {
        probe.hardwareCommand = { latestCommand(it) }
        probe.hardwareState = {
            val stats = controller.statsSummary()
            val presentation = controller.presentation
            JSONObject().put("sceneStats", stats).put("metrics", hardwareMetrics(stats))
                .put("panel", panel).put("modalVisible", modalVisible)
                .put("pageSnapshotConfig", snapshotConfig ?: JSONObject.NULL).put("pageSnapshotWidth", snapshotWidth)
                .put("pageSnapshotHeight", snapshotHeight).put("pageSnapshotSerial", snapshotSerial)
                .put("pageSnapshotStatus", snapshotStatus).put("coverRunning", coverJob?.isActive == true)
                .put("notesDeleteCount", notesDeleteCount).put("notesTarget", notesTarget ?: JSONObject.NULL)
                .put("lastDeletedTarget", lastDeletedTarget ?: JSONObject.NULL).put("noteCount", notes.size)
                .put("noteDetails", noteDetails).put("deleteConfirm", deleteConfirm).put("noteSaveCount", noteSaveCount)
                .put("noteDraft", noteDraft).put("query", query).put("currentChapter", chapter)
                .put("animationRunning", animationRunning).put("animationToken", animationToken)
                .put("tapCount", tapCount).put("volumeTurns", volumeTurns).put("imeVisible", imeVisible)
                .put("hostWindowMatchesActivity", presentation != null && presentation.host.windowToken != null &&
                    presentation.host.windowToken == activity.window.decorView.windowToken)
        }
    }
    LaunchedEffect(navigationGeneration) {
        panel = if (settings) "settings" else "none"
        noteDetails = false; deleteConfirm = false; notesTarget = null
    }
    LaunchedEffect(settings) { if (settings) panel = "settings" else if (panel == "settings") panel = "none" }
    LaunchedEffect(dialog) { if (dialog) openPanel("note") else if (panel == "note") panel = "none" }
    ReaderAppearanceEffect(config, systemDark, ready) { _, _, _ ->
        evaluate("if(window.MiradReader)MiradReader.setThemeCssOnly(${JSONObject.quote(latestCss)},$latestLayout);")
    }
    LaunchedEffect(top, bottom, horizontal, clipTop, clipBottom) {
        if (ready) evaluate("MiradReader.setThemeCssOnly(${JSONObject.quote(latestCss)},$latestLayout);")
    }
    DisposableEffect(probe, lifecycle) {
        val oldPager = ReaderKeys.pager
        ReaderKeys.pager = { delta ->
            if (!ready) false else {
                volumeTurns++
                evaluate(if (latestConfig.pageMode == PageMode.SCROLL) "MiradReader.scrollStep($delta);"
                    else if (delta > 0) "MiradReader.nextPage();" else "MiradReader.prevPage();")
                true
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { stopAnimation(); hideCover() }
        }
        lifecycle.addObserver(observer)
        onDispose {
            stopAnimation(); hideCover()
            lifecycle.removeObserver(observer)
            ReaderKeys.pager = oldPager
            ReaderKeys.blockedByOverlay = false
            probe.hardwareCommand = null
            probe.hardwareState = null
        }
    }
    DisposableEffect(activity) {
        onDispose { WindowInsetsControllerCompat(activity.window, activity.window.decorView).show(WindowInsetsCompat.Type.systemBars()) }
    }

    // Match ReaderScreen: ordinary reader navigation belongs to the Activity dispatcher,
    // while only sheet-internal category navigation belongs to the overlay portal.
    BackHandler(enabled = !modalVisible) {
        if (chrome) onChrome(false) else onBack()
    }
    CompositionLocalProvider(LocalReaderWindowPolicy provides policy) {
        ReaderHardwareScene(
            controller = controller, backgroundColor = palette.background,
            revision = listOf(config, ready, chapter, systemDark), modifier = Modifier.fillMaxSize(),
            webViewFactory = { ctx ->
                ReaderWebView(ctx).also { web ->
                    probe.view = web
                    web.settings.javaScriptEnabled = true
                    web.settings.blockNetworkLoads = true
                    web.settings.allowFileAccess = false
                    web.settings.allowContentAccess = false
                    web.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    web.onTap = { x, y -> latestTap(x, y) }
                    web.onTouchDown = { if (animationRunning) stopAnimation() }
                    web.onSelectionAction = { action -> when (action) {
                        SelectionAction.NOTE -> openPanel("note")
                        SelectionAction.SEARCH -> openPanel("search")
                        SelectionAction.HIGHLIGHT -> Unit
                    } }
                    web.addJavascriptInterface(ReaderJsBridge(
                        onReady = { count, length -> pages = count; textLength = length; ready = true },
                        onState = { current, count, position, length -> page = current; pages = count; offset = position; textLength = length },
                        onSelection = { _, _, text -> selectedText = text },
                        onMetrics = { left, _ -> insetLeft = left },
                        onChapterChanged = { chapter = it.coerceIn(chapters.indices) },
                        onVisualChanged = web::notifyBackdropContentChanged
                    ), "MiradBridge")
                    val assets = WebViewAssetLoader.Builder()
                        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(ctx)).build()
                    web.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                            assets.shouldInterceptRequest(request.url)?.let { return it }
                            val index = request.url.lastPathSegment?.toIntOrNull()
                            if (request.url.path?.contains("/chapter/") == true && index != null && index in chapters.indices) {
                                return WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(latestHtml(index).toByteArray()))
                            }
                            return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                        }
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                        override fun onPageFinished(view: WebView, url: String) {
                            if (probe.view === view) { probe.loads++; Log.i("ImmersionPreview", "readerReady hardware=true loads=${probe.loads}") }
                        }
                    }
                    web.loadDataWithBaseURL("https://appassets.androidplatform.net/hardware/chapter/$chapter",
                        latestHtml(chapter), "text/html", "utf-8", null)
                }
            },
            onWebViewReleased = { web ->
                ownedBitmaps.toList().forEach { if (!it.isRecycled) it.recycle() }
                ownedBitmaps.clear()
                web.removeJavascriptInterface("MiradBridge")
                web.stopLoading()
                web.destroy()
                if (probe.view === web) probe.view = null
            },
            content = {
                Box(Modifier.fillMaxSize()) {
                    coverBitmap?.let { bitmap ->
                        if (!bitmap.isRecycled) Image(bitmap.asImageBitmap(), null,
                            Modifier.fillMaxSize().graphicsLayer { translationX = coverX.value })
                    }
                    HeaderFooter(config, palette, "风雪归人", page, pages, percent, insetLeft)
                    ReaderSystemBarProtection(Color(palette.background))
                }
            },
            overlays = {
                ReaderOverlayHost(onModalVisibilityChanged = {
                    modalVisible = it; controller.setModalVisible(it)
                    if (it && autoState == AutoReadState.RUNNING) stopAnimation()
                }) {
                    Box(Modifier.fillMaxSize()) {
                        if (chrome) {
                            Box(Modifier.align(Alignment.TopCenter)) {
                                ReaderTopBar(chapters[chapter].title, "风雪归人", false, false,
                                    onBack, {}, { openPanel("notes") }, { openPanel("search") }, { openPanel("toc") },
                                    { openPanel("autoread") }, { openPanel("settings") }, { openPanel("autoread") })
                            }
                            Box(Modifier.align(Alignment.BottomCenter)) {
                                ReaderBottomBar(percent, page, pages, chapter, chapters.size,
                                    { openPanel("toc") }, { openPanel("progress") }, { openPanel("settings") },
                                    { openPanel("notes") }, { openPanel("search") }, { openPanel("autoread") }, false,
                                    { value -> hideCover(); evaluate("MiradReader.gotoOffset((MiradReader.getText().length*$value)|0,false);") })
                            }
                        }
                        when (panel) {
                            "settings" -> key(settingsPage, navigationGeneration) {
                                ReaderSettingsPanel(config = config, isEpub = isEpub, onChange = onChange,
                                    onOpenAutoRead = { panel = "autoread"; if (settings) onDismissSettings() },
                                    onResetGlobal = {
                                        if (appearanceController != null) {
                                            appearanceController.resetReadingConfig(
                                                setOf(
                                                    com.mirad.reader.ui.common.ResetScope.LAYOUT_INTERACTION,
                                                    com.mirad.reader.ui.common.ResetScope.DAY_STYLE,
                                                    com.mirad.reader.ui.common.ResetScope.NIGHT_STYLE
                                                )
                                            )
                                        } else onChange { ReaderConfig.DEFAULT }
                                    },
                                    onDismiss = ::closePanel, initialPage = settingsPage)
                            }
                            "toc" -> TocSheet(chapters, chapter, ::closePanel, { loadChapter(it); closePanel() })
                            "progress" -> ProgressSheet(percent, chapter, chapters.size, chapters[chapter].title, page, pages,
                                { value -> hideCover(); evaluate("MiradReader.gotoOffset((MiradReader.getText().length*$value)|0,false);") },
                                { loadChapter(it) }, ::closePanel)
                            "autoread" -> AutoReadSheet(config, autoState, onChange,
                                { panel = "none"; onChrome(false); startAnimation(30000); autoState = AutoReadState.RUNNING },
                                { stopAnimation(false); autoState = AutoReadState.OFF }, ::closePanel)
                            "search" -> SheetScaffold("全书搜索", ::closePanel, fillHeight = true,
                                actions = { SheetActionButton("搜索", {
                                    probe.view?.evaluateJavascript("MiradReader.getText().indexOf(${JSONObject.quote(query)})") {
                                        searchResult = it.toIntOrNull() ?: -1
                                    }
                                }, enabled = query.isNotBlank()) }) {
                                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                                    OutlinedTextField(query, { query = it; searchResult = null },
                                        placeholder = { Text("输入要查找的内容") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                    searchResult?.let { found ->
                                        if (found >= 0) ListRow("雪夜出城", subtitle = query,
                                            modifier = Modifier.clickable { evaluate("MiradReader.gotoOffset($found,false);"); closePanel() })
                                        else SheetEmpty("没有找到「$query」")
                                    }
                                }
                            }
                            "note" -> MiradDialog(onDismissRequest = ::closePanel, title = { Text("写笔记") },
                                text = { Column {
                                    Text(selectedText.take(80))
                                    OutlinedTextField(noteDraft, { noteDraft = it }, label = { Text("笔记内容") },
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp), minLines = 3, maxLines = 7)
                                } },
                                confirmButton = { MiradTextButton({ noteSaveCount++; closePanel() }) { Text("保存") } },
                                dismissButton = { MiradTextButton(::closePanel) { Text("取消") } })
                            "notes" -> SheetScaffold("笔记与书签", ::closePanel, fillHeight = true) {
                                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                                    notes.forEach { id -> ListRow(
                                        title = if (id == 1) "雪下了整整一夜" else "沈砚把行囊往肩上一甩",
                                        meta = "笔记 $id",
                                        modifier = Modifier.clickable { notesTarget = id; noteDetails = true })
                                    }
                                    if (notes.isEmpty()) SheetEmpty("还没有笔记")
                                }
                            }
                        }
                        if (noteDetails) MiradDialog(
                            onDismissRequest = { noteDetails = false; notesTarget = null },
                            title = { Text("笔记详情") },
                            text = { Text("笔记 $notesTarget") },
                            confirmButton = { MiradTextButton({ deleteConfirm = true }) { Text("删除") } },
                            dismissButton = { MiradTextButton({ noteDetails = false; notesTarget = null }) { Text("关闭") } })
                        if (deleteConfirm) {
                            val deleting = notesTarget
                            MiradDialog(onDismissRequest = { deleteConfirm = false }, title = { Text("删除笔记？") },
                                text = { Text("笔记 $deleting") },
                                confirmButton = { MiradTextButton({
                                    if (deleting != null && notes.remove(deleting)) { notesDeleteCount++; lastDeletedTarget = deleting }
                                    deleteConfirm = false; noteDetails = false; notesTarget = null
                                }) { Text("删除") } },
                                dismissButton = { MiradTextButton({ deleteConfirm = false }) { Text("取消") } })
                        }
                    }
                }
            }
        )
    }
}

private fun hardwareMetrics(stats: String): JSONObject = JSONObject().also { result ->
    Regex("([A-Za-z][A-Za-z0-9]*)=([^ ]+)").findAll(stats).forEach { match ->
        val (name, raw) = match.destructured
        val value: Any = when (raw) {
            "true" -> true
            "false" -> false
            else -> raw.toLongOrNull() ?: raw.toDoubleOrNull() ?: raw
        }
        result.put(name, value)
        if (name == "analysisSize") {
            val dimensions = raw.split('x')
            result.put("analysisWidth", dimensions.getOrNull(0)?.toIntOrNull() ?: 0)
            result.put("analysisHeight", dimensions.getOrNull(1)?.toIntOrNull() ?: 0)
        }
    }
}

