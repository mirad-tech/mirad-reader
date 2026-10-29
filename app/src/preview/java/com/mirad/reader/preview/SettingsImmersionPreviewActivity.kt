package com.mirad.reader.preview

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.webkit.WebViewAssetLoader
import com.mirad.reader.core.model.*
import com.mirad.reader.core.reader.ReaderCss
import com.mirad.reader.data.repo.SettingsRepository
import com.mirad.reader.reader.AutoReadState
import com.mirad.reader.reader.ReaderWebView
import com.mirad.reader.ui.common.*
import com.mirad.reader.ui.reader.*
import com.mirad.reader.ui.settings.ReadingSettingsPage
import com.mirad.reader.ui.settings.SettingsContent
import com.mirad.reader.ui.settings.SettingsRoute
import com.mirad.reader.ui.theme.MiradTheme
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.roundToInt

private const val TAG = "ImmersionPreview"
private const val ACTION = "com.mirad.reader.preview.IMMERSION_CHECK"
private val scenes = setOf("settings", "reader", "reader-settings")

/** A memory-only fixture using the production settings, inset protection and theme effect. */
class SettingsImmersionPreviewActivity : ComponentActivity() {
    private var hardwarePreview = false

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (hardwarePreview) {
            val delta = when (keyCode) {
                android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> 1
                android.view.KeyEvent.KEYCODE_VOLUME_UP -> -1
                else -> 0
            }
            if (delta != 0 && com.mirad.reader.reader.ReaderKeys.dispatch(delta)) return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hardwarePreview = intent.getBooleanExtra("hardware", false)
        if (hardwarePreview) window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val initialScene = intent.getStringExtra("scene") ?: "reader"
        val unifiedAppearance = initialScene == "appearance"
        require(initialScene in scenes || unifiedAppearance) { "Unsupported settings/immersion scene: $initialScene" }
        val initialEntry = if (unifiedAppearance) intent.getStringExtra("entry") ?: "settings" else initialScene
        require(initialEntry in scenes)
        val initialRoute = SettingsRoute.parse(intent.getStringExtra("category"))
        val initialIsEpub = intent.getBooleanExtra("isEpub", false)
        val transparency = intent.getIntExtra("transparency", 60)
        // 新增：磨砂玻璃开关的预览开关（仅预览包），默认开启。
        val glassEnabled = intent.getBooleanExtra("glassEnabled", true)
        val initialGlass = GlassAppearance(glassEnabled, transparency).normalized()
        val initialConfig = ReaderConfig.DEFAULT.copy(
            theme = intent.getStringExtra("theme") ?: ThemeKey.PAPER,
            pageMode = intent.getStringExtra("pageMode") ?: PageMode.SCROLL,
            pageAnim = PageAnim.COVER,
            tapZone = TapZoneKey.TWO,
            fullScreen = intent.getBooleanExtra("fullScreen", true),
            showHeader = intent.getBooleanExtra("showHeader", true),
            showFooter = intent.getBooleanExtra("showFooter", true),
            showClock = intent.getBooleanExtra("showClock", true),
            showBattery = true,
            epubLayoutMode = intent.getStringExtra("epubLayout") ?: EpubLayoutMode.UNIFIED,
            backgroundType = if (intent.hasExtra("background")) BackgroundType.IMAGE else BackgroundType.COLOR,
            backgroundImageUri = intent.getStringExtra("background")?.let { BuiltinBackgrounds.SCHEME + it },
            backgroundImageAlpha = intent.getFloatExtra("imageAlpha", 1f),
            autoPageSeconds = intent.getIntExtra("autoPageSeconds", ReaderConfig.DEFAULT.autoPageSeconds),
            autoScrollSecondsPerScreen = intent.getIntExtra("autoScrollSecondsPerScreen", ReaderConfig.DEFAULT.autoScrollSecondsPerScreen)
        )
        val initialChrome = intent.getBooleanExtra("chrome", false)
        setContent {
            val systemDark = isSystemInDarkTheme()
            val scope = rememberCoroutineScope()
            val bounceProbe = remember { PreviewBounceDiagnostics() }
            val dao = remember { PreviewInMemoryPreferenceDao(initialGlass) }
            var repositoryGeneration by remember { mutableIntStateOf(0) }
            val repo = remember(dao, repositoryGeneration) { SettingsRepository(dao, scope) }
            var initialized by remember { mutableStateOf(false) }
            LaunchedEffect(dao) {
                repo.saveGlobal(initialConfig)
                if (unifiedAppearance && intent.hasExtra("mode")) {
                    repo.saveAppAppearance(repo.currentAppAppearance().copy(mode = AppThemeMode.valueOf(intent.getStringExtra("mode")!!)))
                }
                initialized = true
            }
            if (!initialized) return@setContent
            val rawConfig by repo.global.collectAsState(initialConfig)
            val appearance by if (unifiedAppearance) repo.appAppearance.collectAsState(initial = null)
                else remember { mutableStateOf<AppAppearance?>(null) }
            if (unifiedAppearance && appearance == null) return@setContent
            val appDark = appearance?.effectiveDark(systemDark) ?: systemDark
            val config = appearance?.resolveReaderConfig(rawConfig, systemDark) ?: rawConfig
            val savedConfig by dao.observe(SettingsRepository.KEY_GLOBAL).collectAsState(null)
            val savedAppearance by dao.observe(SettingsRepository.KEY_APP_APPEARANCE).collectAsState(null)
            val appearanceSaveFailed by repo.appAppearanceSaveFailed.collectAsState(false)
            val glass by repo.glassAppearance.collectAsState(initialGlass)
            var scene by remember { mutableStateOf(initialEntry) }
            var category by remember { mutableStateOf(initialRoute) }
            var navigationGeneration by remember { mutableIntStateOf(0) }
            var isEpub by remember { mutableStateOf(initialIsEpub) }
            var chrome by remember { mutableStateOf(initialChrome) }
            // 仅预览：边缘质感开关，用于在同一进程内做"边缘临时禁用"的前后对照。
            var edgeTexture by remember { mutableStateOf(intent.getBooleanExtra("edge", true)) }
            var dialog by remember { mutableStateOf(false) }
            val probe = remember {
                ImmersionReaderProbe().apply {
                    if (hardwarePreview) outputFile = java.io.File(requireNotNull(getExternalFilesDir(null)), "hardware-reader-state.json")
                }
            }
            val configEditor = remember(repo) {
                ReadingConfigEditor(repo, scope, onFailure = { Log.e(TAG, "readingConfigUpdateFailed", it) })
            }
            val appearanceController = remember(repo, configEditor) {
                object : AppAppearanceController {
                    override fun setMode(mode: AppThemeMode) = repo.queueAppThemeMode(mode, scope)
                    override fun commit() = repo.flushAppAppearance(scope)
                    override fun retrySave() = repo.retryAppAppearance(scope)
                    override fun updateReadingConfig(isDark: Boolean, transform: (ReaderConfig) -> ReaderConfig,
                        onApplied: (ReaderConfig, AppAppearance) -> Unit) { configEditor.update(isDark, transform, onApplied) }
                    override fun resetReadingConfig(
                        scopes: Set<com.mirad.reader.ui.common.ResetScope>,
                        onApplied: (ReaderConfig, AppAppearance) -> Unit
                    ) {
                        configEditor.resetReading(scopes, onApplied)
                    }
                    override fun commitReadingConfig() { configEditor.flush() }
                }
            }
            val change: ((ReaderConfig) -> ReaderConfig) -> Unit = { transform ->
                if (unifiedAppearance) appearanceController.updateReadingConfig(appDark, transform)
                else repo.queueGlobalConfig(transform(config), scope)
            }
            val lifecycle = LocalLifecycleOwner.current.lifecycle
            DisposableEffect(repo, lifecycle) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_STOP && unifiedAppearance) configEditor.flush()
                }
                lifecycle.addObserver(observer)
                onDispose { lifecycle.removeObserver(observer) }
            }
            val glassController = remember(repo) {
                object : GlassAppearanceController {
                    override fun update(appearance: GlassAppearance) { repo.queueGlassAppearance(appearance, scope) }
                    override fun commit() { repo.flushGlassAppearance(scope) }
                    override fun reset() { repo.resetGlassAppearance(scope) }
                }
            }
            val palette = ThemePaletteResolver.resolve(config, systemDark)
            MiradTheme(darkTheme = if (unifiedAppearance) appDark else if (scene == "settings") systemDark else palette.isDark) {
                CompositionLocalProvider(
                    LocalAppAppearance provides appearance,
                    LocalAppDarkTheme provides if (unifiedAppearance) appDark else null,
                    LocalAppSystemDark provides systemDark,
                    LocalAppAppearanceController provides if (unifiedAppearance) appearanceController else null,
                    LocalAppAppearanceSaveFailed provides appearanceSaveFailed,
                    LocalGlassAppearance provides glass,
                    LocalGlassForceFallback provides intent.getBooleanExtra("fallback", false),
                    LocalGlassEdgeTexture provides edgeTexture,
                    LocalEdgeBounceObserver provides bounceProbe::record,
                    LocalGlassAppearanceController provides glassController,
                    LocalSettingsCommit provides { if (unifiedAppearance) appearanceController.commitReadingConfig() else repo.flushGlobalConfig(scope) }
                ) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    GlassBackdropHost {
                        ImmersionCommands { command ->
                            when (command.getStringExtra("operation")) {
                                "navigate" -> {
                                    val requested = command.getStringExtra("scene") ?: error("scene required")
                                    require(requested in scenes) { "Unsupported scene: $requested" }
                                    category = SettingsRoute.parse(command.getStringExtra("category"))
                                    scene = requested
                                    navigationGeneration++
                                }
                                "config" -> change { old ->
                                    old.copy(
                                        theme = command.getStringExtra("theme") ?: old.theme,
                                        backgroundType = if (command.hasExtra("background")) BackgroundType.IMAGE else old.backgroundType,
                                        backgroundImageUri = command.getStringExtra("background")?.let { BuiltinBackgrounds.SCHEME + it } ?: old.backgroundImageUri,
                                        backgroundImageAlpha = command.getFloatExtra("imageAlpha", old.backgroundImageAlpha),
                                        pageMode = command.getStringExtra("pageMode") ?: old.pageMode,
                                         pageAnim = command.getStringExtra("pageAnim") ?: old.pageAnim,
                                         fontSizeSp = command.getFloatExtra("fontSizeSp", old.fontSizeSp),
                                        epubLayoutMode = command.getStringExtra("epubLayout") ?: old.epubLayoutMode,
                                        fullScreen = command.getBooleanExtra("fullScreen", old.fullScreen),
                                        showHeader = command.getBooleanExtra("showHeader", old.showHeader),
                                        showFooter = command.getBooleanExtra("showFooter", old.showFooter)
                                    )
                                }
                                "mode" -> if (unifiedAppearance) {
                                    repo.queueAppThemeMode(AppThemeMode.valueOf(command.getStringExtra("mode")!!), scope)
                                    repo.flushAppAppearance(scope)
                                }
                                "reopen-store" -> if (unifiedAppearance) scope.launch {
                                    configEditor.flush().join()
                                    val currentAppearance = repo.currentAppAppearance()
                                    repo.saveGlobal(repo.currentGlobal())
                                    repo.saveAppAppearance(currentAppearance)
                                    repositoryGeneration++
                                }
                                "format" -> isEpub = command.getBooleanExtra("isEpub", isEpub)
                                "chrome" -> chrome = command.getBooleanExtra("visible", chrome)
                                "dialog" -> dialog = command.getBooleanExtra("visible", dialog)
                                "scroll" -> probe.view?.evaluateJavascript("window.scrollTo(0, ${command.getIntExtra("y", 360)});", null)
                                 "panel", "cover", "hide-cover", "chapter", "image", "animate", "stop-animation" ->
                                     if (hardwarePreview) probe.hardwareCommand?.invoke(command)
                                 "edge" -> {
                                    edgeTexture = command.getBooleanExtra("enabled", true)
                                }
                                "transparency" -> {
                                     repo.queueGlassAppearance(GlassAppearance(enabled = command.getBooleanExtra("enabled", glass.enabled), clarityPercent = command.getIntExtra("percent", glass.clarityPercent).coerceIn(0, 100)), scope)
                                     repo.flushGlassAppearance(scope)
                                 }
                                "dump" -> {
                                    val insets = WindowInsetsCompat.toWindowInsetsCompat(window.decorView.rootWindowInsets)
                                    val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
                                    val cutoutRects = org.json.JSONArray()
                                    insets.displayCutout?.boundingRects?.forEach { rect ->
                                        cutoutRects.put(org.json.JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom)))
                                    }
                                    val state = JSONObject().put("label", command.getStringExtra("label"))
                                         .put("glass", JSONObject().put("enabled", glass.enabled).put("clarityPercent", glass.clarityPercent))
                                         .put("requestId", command.getStringExtra("requestId"))
                                         .put("hardwarePreview", hardwarePreview).put("chrome", chrome)
                                         .put("bounce", bounceProbe.snapshot())
                                         .put("imeVisible", insets.isVisible(WindowInsetsCompat.Type.ime()))
                                         .put("imeBottom", insets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
                                         .put("statusInsetTop", insets.getInsets(WindowInsetsCompat.Type.statusBars()).top)
                                         .put("navigationInsetBottom", insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom)
                                        .put("scene", scene).put("category", category.routeName())
                                        .put("config", JSONObject(ReaderConfigJson.encode(config)))
                                        .put("savedConfig", savedConfig?.let(::JSONObject))
                                        .put("unifiedAppearance", unifiedAppearance)
                                        .put("appearance", appearance?.let { JSONObject(AppAppearanceJson.encode(it)) })
                                        .put("savedAppearance", savedAppearance?.let(::JSONObject))
                                        .put("repositoryGeneration", repositoryGeneration)
                                        .put("systemDark", systemDark).put("effectiveDark", palette.isDark)
                                        .put("uiDark", if (unifiedAppearance) appDark else if (scene == "settings") systemDark else palette.isDark)
                                        .put("isEpub", isEpub).put("loads", probe.loads)
                                        .put("fontScale", resources.configuration.fontScale)
                                        .put("screenWidthDp", resources.configuration.screenWidthDp)
                                        .put("statusVisible", insets.isVisible(WindowInsetsCompat.Type.statusBars()))
                                        .put("navigationVisible", insets.isVisible(WindowInsetsCompat.Type.navigationBars()))
                                        .put("cutout", "$cutout")
                                        .put("cutoutLeft", cutout.left).put("cutoutTop", cutout.top)
                                        .put("cutoutRight", cutout.right).put("cutoutBottom", cutout.bottom)
                                        .put("cutoutRects", cutoutRects)
                                    probe.dump(state)
                                }
                            }
                        }
                        if (scene == "settings") {
                            if (unifiedAppearance) ApplyReaderWindowPolicy(window, ReaderWindowPolicy(false, appDark))
                            PageSurface("settings:$category:$navigationGeneration", bounded = true, animate = false) {
                            SettingsContent(config = config, route = category, onChange = change,
                                onOpenRoute = { category = it },
                                onBack = { if (category != null) category = null else scene = "reader" })
                            }
                        } else {
                             if (hardwarePreview) {
                                 HardwareReaderPreview(config, if (unifiedAppearance) appDark else systemDark, isEpub,
                                     scene == "reader-settings", category.readerPage(), navigationGeneration, chrome, dialog, probe, change,
                                     onChrome = { chrome = it }, onDialog = { dialog = it },
                                     onSettings = { category = null; scene = "reader-settings" },
                                     onDismissSettings = { scene = "reader" }, onBack = { scene = "settings" })
                             } else {
                            ImmersionReader(config, if (unifiedAppearance) appDark else systemDark, isEpub, scene == "reader-settings", category.readerPage(), navigationGeneration,
                                chrome, dialog, probe, change,
                                onChrome = { chrome = it }, onDialog = { dialog = it },
                                onSettings = { category = null; scene = "reader-settings" },
                                onDismissSettings = { scene = "reader" }, onBack = { scene = "settings" }, unifiedAppearance = unifiedAppearance)
                             }
                        }
                    }
                    }
                }
            }
            LaunchedEffect(config, savedConfig, scene, category) {
                Log.i(TAG, "state scene=$scene category=$category config=${ReaderConfigJson.encode(config)} saved=${savedConfig != null}")
            }
        }
    }
}

/** dump/日志仍输出页面枚举名，验证脚本按旧契约读取。 */
private fun SettingsRoute?.routeName(): String? = when (this) {
    null -> null
    is SettingsRoute.Reading -> page.name
    is SettingsRoute.App -> page.name
}

/** 阅读面板只承载阅读级页面；应用级路由打开面板时落在首页。 */
private fun SettingsRoute?.readerPage(): ReadingSettingsPage? = (this as? SettingsRoute.Reading)?.page

@Composable
private fun ImmersionCommands(onCommand: (Intent) -> Unit) {
    val context = LocalContext.current
    val current by rememberUpdatedState(onCommand)
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { if (intent != null) current(intent) }
        }
        androidx.core.content.ContextCompat.registerReceiver(context, receiver, IntentFilter(ACTION), androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
}

internal class ImmersionReaderProbe {
    var view: ReaderWebView? = null
    var loads: Int = 0
    var hardwareCommand: ((Intent) -> Unit)? = null
    var hardwareState: (() -> JSONObject)? = null
    var outputFile: java.io.File? = null
    private fun emit(state: JSONObject) {
        outputFile?.let { file -> file.parentFile?.mkdirs(); file.writeText(state.toString()) }
        Log.i(TAG, "dump $state")
    }
    fun dump(state: JSONObject) {
        hardwareState?.invoke()?.let { state.put("hardware", it) }
        val reader = view
        if (reader == null) { emit(state); return }
        val position = IntArray(2).also(reader::getLocationOnScreen)
        state.put("readerBounds", JSONObject().put("left", position[0]).put("top", position[1])
            .put("width", reader.width).put("height", reader.height))
        val cutouts = state.optJSONArray("cutoutRects") ?: org.json.JSONArray()
        reader.evaluateJavascript("""(function(){
            const cutouts=$cutouts, hits=[];
            const sx=${reader.width}/innerWidth, sy=${reader.height}/innerHeight;
            const walker=document.createTreeWalker(document.getElementById('mirad-content'),NodeFilter.SHOW_TEXT);
            let node, overlaps=0;
            if(cutouts.length) while(node=walker.nextNode()) {
                for(let i=0;i<node.length;i++) {
                    if(!node.textContent[i].trim()) continue;
                    const range=document.createRange();range.setStart(node,i);range.setEnd(node,i+1);
                    const r=range.getBoundingClientRect();
                    if(r.bottom<=0||r.top>=innerHeight||r.right<=0||r.left>=innerWidth) continue;
                    const rect=[r.left*sx+${position[0]},r.top*sy+${position[1]},r.right*sx+${position[0]},r.bottom*sy+${position[1]}];
                    if(cutouts.some(c=>{
                        if(!(rect[0]<c[2]&&rect[2]>c[0]&&rect[1]<c[3]&&rect[3]>c[1])) return false;
                        const x=(Math.max(rect[0],c[0])+Math.min(rect[2],c[2]))/2;
                        const y=(Math.max(rect[1],c[1])+Math.min(rect[3],c[3]))/2;
                        const painted=document.elementFromPoint((x-${position[0]})/sx,(y-${position[1]})/sy);
                        return painted&&document.getElementById('mirad-content').contains(painted);
                    })) { overlaps++;if(hits.length<3) hits.push(rect); }
                }
            }
            const anchor=document.caretRangeFromPoint(40,120);
            if(anchor && anchor.startContainer.nodeType===Node.TEXT_NODE && anchor.startOffset<anchor.startContainer.length)
                anchor.setEnd(anchor.startContainer,anchor.startOffset+1);
            const anchorRect=anchor&&anchor.getBoundingClientRect();
            return JSON.stringify({
            ready: window.MiradReader && MiradReader.isReady(),
            background: getComputedStyle(document.documentElement).backgroundColor,
            image: getComputedStyle(document.getElementById('mirad-bg')).backgroundImage,
            scrollY: window.scrollY, width: innerWidth, height: innerHeight,
             loadedImages: Array.from(document.images).filter(i=>i.complete&&i.naturalWidth>0).length,
             state: window.MiradReader?JSON.parse(MiradReader.getState()):null,
            snapshot: window.MiradReader && MiradReader.getSnapshot(),cutoutTextOverlaps:overlaps,overlapSamples:hits,
            glyph:anchorRect?{left:anchorRect.left,top:anchorRect.top,offset:anchor.startOffset,text:anchor.toString()}:null
        });})()""") { result ->
            val decoded = org.json.JSONTokener(result).nextValue() as? String
            if (decoded != null) state.put("web", JSONObject(decoded))
            emit(state)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ImmersionReader(
    config: ReaderConfig, systemDark: Boolean, isEpub: Boolean, settings: Boolean,
    page: ReadingSettingsPage?, navigationGeneration: Int, chrome: Boolean, dialog: Boolean, probe: ImmersionReaderProbe,
    onChange: ((ReaderConfig) -> ReaderConfig) -> Unit, onChrome: (Boolean) -> Unit,
    onDialog: (Boolean) -> Unit, onSettings: () -> Unit, onDismissSettings: () -> Unit,
    onBack: () -> Unit, unifiedAppearance: Boolean = false
) {
    val activity = LocalContext.current as ComponentActivity
    val appearanceController = LocalAppAppearanceController.current
    val palette = ThemePaletteResolver.resolve(config, systemDark)
    val policy = ReaderWindowPolicy(config.fullScreen, palette.isDark)
    ApplyReaderWindowPolicy(activity.window, policy)
    DisposableEffect(activity) {
        onDispose { WindowInsetsControllerCompat(activity.window, activity.window.decorView).show(WindowInsetsCompat.Type.systemBars()) }
    }
    var ready by remember { mutableIntStateOf(0) }
    var autoSettings by remember { mutableStateOf(false) }
    val safe = glassSafeInsets().asPaddingValues()
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
        .put("windowedScroll", false).put("chapterCount", 1)
    fun css(dark: Boolean): String = ReaderCss.themeCss(config, ThemePaletteResolver.resolve(config, dark),
        backgroundImageUrl = BuiltinBackgrounds.fromUri(config.backgroundImageUri)?.assetName
            ?.let { "https://appassets.androidplatform.net/assets/$it" })
    fun refresh(dark: Boolean, reason: String) {
        probe.view?.evaluateJavascript("MiradReader.setThemeCssOnly(${JSONObject.quote(css(dark))}, $layout);") {
            Log.i(TAG, "themeRefresh reason=$reason systemDark=$dark theme=${config.theme}")
        }
    }
    if (unifiedAppearance) {
        ReaderAppearanceEffect(config, systemDark, ready > 0) { _, dark, resumed -> refresh(dark, if (resumed) "resume" else "appearance") }
        LaunchedEffect(top, bottom, horizontal, clipTop, clipBottom) { if (ready > 0) refresh(systemDark, "insets") }
    } else {
        LaunchedEffect(config, ready, top, bottom, horizontal, clipTop, clipBottom) { if (ready > 0) refresh(systemDark, "config") }
        // 主题跟随系统时（legacy 预览链路）系统日夜切换也要即时刷新主题。
        LaunchedEffect(systemDark, ready) { if (ready > 0) refresh(systemDark, "system") }
    }
    WebViewGlassBackdrop(probe.view, chrome || settings || dialog, listOf(config, ready))
    DisposableEffect(probe) { onDispose { probe.view?.destroy(); probe.view = null } }
    CompositionLocalProvider(LocalReaderWindowPolicy provides policy) {
        Box(Modifier.fillMaxSize().background(Color(palette.background))) {
            AndroidView(factory = { context ->
                ReaderWebView(context).also { web ->
                    probe.view = web
                    web.settings.javaScriptEnabled = true
                    web.settings.blockNetworkLoads = true
                    web.settings.allowFileAccess = false
                    web.settings.allowContentAccess = false
                    web.onTap = { _, _ -> onChrome(!chrome) }
                    val assets = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context)).build()
                    web.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = assets.shouldInterceptRequest(request.url)
                        override fun onPageFinished(view: WebView, url: String) { probe.loads++; ready++; Log.i(TAG, "readerReady loads=${probe.loads}") }
                    }
                    val structural = context.assets.open("reader/reader.css").bufferedReader().use { it.readText() }
                    val script = context.assets.open("reader/reader.js").bufferedReader().use { it.readText() }
                    val paragraphs = listOf(
                        "雪下了整整一夜，官道上的车辙被填得平平整整，连马蹄印都寻不见半点痕迹。",
                        "沈砚把行囊往肩上一甩，回头望了一眼城门上那盏昏黄的灯笼，没再停留。",
                        "他怀里揣着半块冷透的胡饼，那是母亲昨夜塞给他的，说是路上压压饥。")
                    val text = (0..35).joinToString("") { "<p>${paragraphs[it % 3]}</p>" }
                    val html = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><style>$structural</style><style id="mirad-theme">${css(systemDark)}</style></head><body><div id="mirad-content"><h2>第一章 雪夜出城</h2>$text</div><script>window.MiradBridge={onReady(){},onState(){},onMetrics(){},onSelection(){},onChapterChanged(){}};$script;MiradReader.init($layout);</script></body></html>"""
                    web.loadDataWithBaseURL("https://appassets.androidplatform.net/preview/", html, "text/html", "utf-8", null)
                }
            }, modifier = Modifier.fillMaxSize(), update = { web -> web.onTap = { _, _ -> onChrome(!chrome) } })
            HeaderFooter(config, palette, "风雪归人", 2, 8, .35f)
            ReaderSystemBarProtection(Color(palette.background))
            if (chrome) {
                Box(Modifier.align(Alignment.TopCenter)) {
                    ReaderTopBar("第一章 雪夜出城", "风雪归人", false, false,
                        onBack, {}, {}, {}, {}, { autoSettings = true }, onSettings, {})
                }
                Box(Modifier.align(Alignment.BottomCenter)) {
                    ReaderBottomBar(.35f, 2, 8, 0, 6, {}, {}, onSettings, {}, {}, { autoSettings = true }, false, {})
                }
            }
            if (settings) key(page, navigationGeneration) {
                ReaderSettingsPanel(config = config, isEpub = isEpub, onChange = onChange,
                    onOpenAutoRead = { autoSettings = true }, onResetGlobal = {
                        if (unifiedAppearance) {
                            appearanceController?.resetReadingConfig(
                                setOf(
                                    com.mirad.reader.ui.common.ResetScope.LAYOUT_INTERACTION,
                                    com.mirad.reader.ui.common.ResetScope.DAY_STYLE,
                                    com.mirad.reader.ui.common.ResetScope.NIGHT_STYLE
                                )
                            )
                        } else onChange { ReaderConfig.DEFAULT }
                    },
                    onDismiss = onDismissSettings, initialPage = page)
            }
            if (autoSettings) AutoReadSheet(config, AutoReadState.OFF, onChange, {}, {}, { autoSettings = false })
            if (dialog) MiradDialog(onDismissRequest = { onDialog(false) }, title = { Text("写笔记") },
                text = { Text("雪下了整整一夜，官道上的车辙被填得平平整整。") },
                confirmButton = { MiradTextButton(onClick = { onDialog(false) }) { Text("保存") } })
        }
    }
}
