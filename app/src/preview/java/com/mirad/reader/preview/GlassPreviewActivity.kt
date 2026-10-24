package com.mirad.reader.preview

import android.os.Bundle
import android.app.Activity
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.webkit.ConsoleMessage
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.webkit.WebViewAssetLoader
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.ByteArrayInputStream
import androidx.compose.ui.viewinterop.AndroidView
import com.mirad.reader.core.model.*
import com.mirad.reader.core.reader.ReaderCss
import com.mirad.reader.data.db.dao.PreferenceDao
import com.mirad.reader.data.db.entity.BookEntity
import com.mirad.reader.data.db.entity.PreferenceEntity
import com.mirad.reader.data.repo.SettingsRepository
import com.mirad.reader.data.repo.ShelfBook
import com.mirad.reader.reader.ReaderWebView
import com.mirad.reader.ui.common.*
import com.mirad.reader.ui.reader.*
import com.mirad.reader.ui.settings.ReadingSettingsPage
import com.mirad.reader.ui.settings.SettingsContent
import com.mirad.reader.ui.settings.SettingsRoute
import com.mirad.reader.ui.shelf.*
import com.mirad.reader.ui.theme.MiradTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import java.io.IOException

internal class PreviewInMemoryPreferenceDao(
    initialTransparency: Int,
    @Volatile var failWrites: Boolean = false
) : PreferenceDao {
    private val store = MutableStateFlow(
        mapOf(SettingsRepository.KEY_GLASS_TRANSPARENCY to initialTransparency.coerceIn(0, 100).toString())
    )
    val savedGlassFlow: Flow<String?> = store.map { it[SettingsRepository.KEY_GLASS_TRANSPARENCY] }

    override suspend fun get(key: String): String? = store.value[key]

    override fun observe(key: String): Flow<String?> = store.map { it[key] }

    override suspend fun put(preference: PreferenceEntity) {
        if (failWrites) {
            throw IOException("Simulated preview DAO write failure for key=${preference.key}")
        }
        store.value = store.value.toMutableMap().apply { put(preference.key, preference.value) }
    }

    override suspend fun remove(key: String) {
        if (failWrites) {
            throw IOException("Simulated preview DAO remove failure for key=$key")
        }
        store.value = store.value.toMutableMap().apply { remove(key) }
    }

    override suspend fun removeByPrefix(prefix: String) {
        store.value = store.value.toMutableMap().apply { keys.filter { it.startsWith(prefix) }.forEach(::remove) }
    }

    override suspend fun getAll(): List<PreferenceEntity> =
        store.value.map { (k, v) -> PreferenceEntity(k, v, 0L) }
}

/** No MiradApp, room database, file imports, disk settings store or network is created here. */
class GlassPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scene = intent.getStringExtra("scene") ?: "shelf"
        val dark = intent.getBooleanExtra("dark", false)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark },
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark }
        )
        val baseline = intent.getBooleanExtra("baseline", false)
        val fallback = intent.getBooleanExtra("fallback", false)
        val hasFontScaleOverride = intent.hasExtra("fontScale")
        val fontScaleOverride = intent.getFloatExtra("fontScale", 1f)
        val scrolled = intent.getBooleanExtra("scrolled", false)
        val longTitle = intent.getBooleanExtra("longTitle", false)
        val fullScreen = intent.getBooleanExtra("fullScreen", false)
        val rtl = intent.getBooleanExtra("rtl", false)
        val readerDark = intent.getBooleanExtra("readerDark", dark)
        val initialTransparency = intent.getIntExtra("transparency", GlassAppearance.DEFAULT_PERCENT)
        val simulateSaveFailure = intent.getBooleanExtra("simulateSaveFailure", false)
        val initialCategoryName = intent.getStringExtra("category")
        val backdropColorHex = intent.getStringExtra("backdropColor") ?: "#0000FF"
        val baseBgColorHex = intent.getStringExtra("baseBgColor") ?: if (dark) "#15181A" else "#FFFFFF"
        val blockColorHex = intent.getStringExtra("blockColor") ?: if (dark) "#AAAAAA" else "#0000FF"
        val blockStartX = intent.getFloatExtra("blockStartX", 2f / 8f)
        val blockStartY = intent.getFloatExtra("blockStartY", 2f / 8f)
        val blockWidthFrac = intent.getFloatExtra("blockWidthFrac", 3f / 8f)
        val blockHeightFrac = intent.getFloatExtra("blockHeightFrac", 3f / 8f)
        val textOffsetXDp = intent.getFloatExtra("textOffsetX", 162f)
        val textOffsetYDp = intent.getFloatExtra("textOffsetY", 150f)
        setContent {
            MiradTheme(darkTheme = dark) {
                val density = LocalDensity.current
                val effectiveFontScale = if (hasFontScaleOverride) fontScaleOverride else density.fontScale
                val repoScope = rememberCoroutineScope()
                val inMemoryDao = remember {
                    PreviewInMemoryPreferenceDao(
                        initialTransparency = initialTransparency,
                        failWrites = simulateSaveFailure
                    )
                }
                val previewSettingsRepo = remember(inMemoryDao, repoScope) {
                    SettingsRepository(dao = inMemoryDao, repositoryScope = repoScope)
                }
                val glassAppearance by previewSettingsRepo.glassAppearance.collectAsState(
                    initial = GlassAppearance(initialTransparency)
                )
                val glassSaveFailed by previewSettingsRepo.glassSaveFailed.collectAsState(initial = false)
                val savedRaw by inMemoryDao.savedGlassFlow.collectAsState(initial = initialTransparency.toString())

                val controller = remember(previewSettingsRepo, repoScope) {
                    object : GlassAppearanceController {
                        override val saveFailed: Boolean
                            get() = glassSaveFailed
                        override fun update(appearance: GlassAppearance) {
                            previewSettingsRepo.queueGlassAppearance(appearance, repoScope)
                            Log.i("GlassPreview", "previewTransparencyUpdated=${appearance.transparencyPercent}")
                        }
                        override fun commit() {
                            previewSettingsRepo.flushGlassAppearance(repoScope)
                            Log.i("GlassPreview", "previewTransparencyCommitted=${glassAppearance.transparencyPercent}")
                        }
                        override fun reset() {
                            previewSettingsRepo.resetGlassAppearance(repoScope)
                            Log.i("GlassPreview", "previewTransparencyReset=${GlassAppearance.DEFAULT_PERCENT}")
                        }
                        override fun retrySave() {
                            previewSettingsRepo.retryGlassAppearance(repoScope)
                            Log.i("GlassPreview", "previewTransparencyRetryRequested=${glassAppearance.transparencyPercent}")
                        }
                    }
                }
                LaunchedEffect(glassAppearance.transparencyPercent, glassSaveFailed, savedRaw) {
                    Log.i(
                        "GlassPreview",
                        "glassRepoState visible=${glassAppearance.transparencyPercent} saved=$savedRaw saveFailed=$glassSaveFailed"
                    )
                }
                LaunchedEffect(density.density, effectiveFontScale, hasFontScaleOverride) {
                    Log.i("GlassPreview", "fontScaleEffective=$effectiveFontScale systemFontScale=${density.fontScale} override=$hasFontScaleOverride")
                }
                CompositionLocalProvider(
                    LocalGlassLegacy provides baseline,
                    LocalGlassForceFallback provides fallback,
                    LocalDensity provides Density(density.density, effectiveFontScale),
                    LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                    LocalGlassAppearance provides glassAppearance,
                    LocalGlassAppearanceController provides controller,
                    LocalGlassSaveFailed provides glassSaveFailed
                ) {
                    GlassBackdropHost {
                        PreviewDiagnosticsReceiver(
                            controller = controller,
                            onSetSaveFailMode = { fail ->
                                inMemoryDao.failWrites = fail
                                Log.i("GlassPreview", "previewSaveFailMode=$fail")
                            }
                        )
                        PreviewContent(
                            initialScene = scene,
                            dark = dark,
                            scrolled = scrolled,
                            longTitle = longTitle,
                            fullScreen = fullScreen,
                            readerDark = readerDark,
                            initialCategoryName = initialCategoryName,
                            backdropColorHex = backdropColorHex,
                            baseBgColorHex = baseBgColorHex,
                            blockColorHex = blockColorHex,
                            blockStartX = blockStartX,
                            blockStartY = blockStartY,
                            blockWidthFrac = blockWidthFrac,
                            blockHeightFrac = blockHeightFrac,
                            textOffsetXDp = textOffsetXDp,
                            textOffsetYDp = textOffsetYDp
                        )
                    }
                }
            }
        }
        Log.i("GlassPreview", "opened scene=$scene dark=$dark baseline=$baseline fallback=$fallback")
    }
}

@Composable
private fun PreviewDiagnosticsReceiver(
    controller: GlassAppearanceController,
    onSetSaveFailMode: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val backdrop = LocalGlassBackdrop.current
    val appearance = LocalGlassAppearance.current
    val saveFailed = LocalGlassSaveFailed.current
    val currentPercent by rememberUpdatedState(appearance.transparencyPercent)
    val currentSaveFailed by rememberUpdatedState(saveFailed)
    DisposableEffect(context, backdrop, controller) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: android.content.Context?, intent: android.content.Intent?) {
                when (intent?.action) {
                    "com.mirad.reader.preview.ACTION_DUMP_STATS" -> {
                        val label = intent.getStringExtra("label") ?: "manual"
                        Log.i(
                            "GlassPreview",
                            "glassStatsDump label=$label transparency=$currentPercent saveFailed=$currentSaveFailed ${backdrop?.statsSummary() ?: "none"}"
                        )
                    }
                    "com.mirad.reader.preview.ACTION_SET_TRANSPARENCY" -> {
                        val pct = intent.getIntExtra("percent", GlassAppearance.DEFAULT_PERCENT).coerceIn(0, 100)
                        controller.update(GlassAppearance(pct))
                        controller.commit()
                    }
                    "com.mirad.reader.preview.ACTION_SET_SAVE_FAIL_MODE" -> {
                        val fail = intent.getBooleanExtra("fail", false)
                        onSetSaveFailMode(fail)
                    }
                    "com.mirad.reader.preview.ACTION_RETRY_SAVE" -> {
                        controller.retrySave()
                    }
                }
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction("com.mirad.reader.preview.ACTION_DUMP_STATS")
            addAction("com.mirad.reader.preview.ACTION_SET_TRANSPARENCY")
            addAction("com.mirad.reader.preview.ACTION_SET_SAVE_FAIL_MODE")
            addAction("com.mirad.reader.preview.ACTION_RETRY_SAVE")
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, android.content.Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        onDispose { context.unregisterReceiver(receiver) }
    }
}

@Composable
private fun PreviewContent(
    initialScene: String,
    dark: Boolean,
    scrolled: Boolean,
    longTitle: Boolean,
    fullScreen: Boolean,
    readerDark: Boolean,
    initialCategoryName: String? = null,
    backdropColorHex: String = "#0000FF",
    baseBgColorHex: String = "#FFFFFF",
    blockColorHex: String = "#0000FF",
    blockStartX: Float = 2f / 8f,
    blockStartY: Float = 2f / 8f,
    blockWidthFrac: Float = 3f / 8f,
    blockHeightFrac: Float = 3f / 8f,
    textOffsetXDp: Float = 162f,
    textOffsetYDp: Float = 150f
) {
    val activity = LocalContext.current as Activity
    var scene by remember { mutableStateOf(initialScene) }
    var dialog by remember { mutableStateOf(initialScene == "dialog") }
    val resolvedInitialCategory = remember(initialCategoryName) {
        SettingsRoute.parse(initialCategoryName)
    }
    var category by remember { mutableStateOf(resolvedInitialCategory) }
    var query by remember { mutableStateOf("") }
    var search by remember { mutableStateOf(false) }
    var layout by remember { mutableStateOf(ShelfLayout.GRID) }
    val books = remember { sampleBooks() }
    BackHandler(scene != "shelf") { scene = "shelf"; Log.i("GlassPreview", "backToShelf") }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when (scene) {
            "mixed-pattern" -> MixedPatternPreview(
                baseBgColorHex = baseBgColorHex,
                blockColorHex = blockColorHex,
                blockStartX = blockStartX,
                blockStartY = blockStartY,
                blockWidthFrac = blockWidthFrac,
                blockHeightFrac = blockHeightFrac,
                textOffsetXDp = textOffsetXDp,
                textOffsetYDp = textOffsetYDp
            )
            "color-contrast" -> ColorContrastPreview(backdropColorHex = backdropColorHex)
            "reader", "reader-settings", "reader-typography", "reader-appearance", "reader-dialog", "reader-probe", "reader-windowed-probe" -> ReaderPreview(
                readerDark,
                scene == "reader-settings" || scene == "reader-typography" || scene == "reader-appearance",
                longTitle,
                fullScreen = fullScreen,
                typography = scene == "reader-typography",
                initialDialog = scene == "reader-dialog",
                probe = scene.endsWith("probe"), windowed = scene == "reader-windowed-probe", onBack = {
                    scene = "shelf"
                    activity.window.decorView.postDelayed({
                        val bars = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
                        Log.i("GlassPreview", "readerPolicyRestored=${bars.isAppearanceLightStatusBars == !dark && bars.isAppearanceLightNavigationBars == !dark}")
                    }, 500)
                }
            )
            "settings" -> SettingsContent(
                route = category, onBack = { if (category != null) category = null else scene = "shelf" },
                onOpenRoute = { category = it }, initialScrollPx = if (scrolled) 120 else 0
            )
            else -> ShelfContent(
                state = ShelfContentState(
                    shelf = if (scene == "empty") emptyList() else books,
                    visible = if (scene == "empty") emptyList() else books.filter { it.book.title.contains(query) },
                    query = query, showSearch = search, layout = layout
                ),
                actions = ShelfContentActions(
                    onOpenBook = { scene = "reader" }, onSettings = { scene = "settings" },
                    onToggleSearch = { search = !search }, onQueryChange = { query = it },
                    onLayoutChange = { layout = it }, onBookAction = { dialog = true },
                    onImportFile = { dialog = true }
                ),
                initialScrollIndex = if (scrolled) 3 else 0,
                initialScrollOffset = if (scrolled) 80 else 0
            )
        }
        if (dialog) MiradDialog(
            onDismissRequest = { dialog = false; Log.i("GlassPreview", "dialogDismiss") },
            title = { Text("删除书籍") },
            text = { Text("确定删除所选书籍？") },
            confirmButton = { MiradTextButton(danger = true, onClick = { dialog = false; Log.i("GlassPreview", "dialogConfirmInMemory") }) { Text("删除") } },
            dismissButton = { MiradTextButton(onClick = { dialog = false }) { Text("取消") } }
        )
    }
}

@Composable
internal fun MixedPatternPreview(
    baseBgColorHex: String = "#FFFFFF",
    blockColorHex: String = "#0000FF",
    blockStartX: Float = 2f / 8f,
    blockStartY: Float = 2f / 8f,
    blockWidthFrac: Float = 3f / 8f,
    blockHeightFrac: Float = 3f / 8f,
    textOffsetXDp: Float = 162f,
    textOffsetYDp: Float = 150f
) {
    val baseColor = remember(baseBgColorHex) {
        val raw = runCatching { android.graphics.Color.parseColor(baseBgColorHex) }
            .getOrDefault(android.graphics.Color.WHITE)
        Color(raw)
    }
    val blockColor = remember(blockColorHex) {
        val raw = runCatching { android.graphics.Color.parseColor(blockColorHex) }
            .getOrDefault(android.graphics.Color.BLUE)
        Color(raw)
    }
    val source = LocalGlassBackdrop.current
    LaunchedEffect(source, blockStartX, blockStartY, textOffsetXDp, textOffsetYDp) {
        kotlinx.coroutines.delay(2000)
        Log.i("PatternAcceptance", "stats ${source?.statsSummary()}")
    }
    Box(
        Modifier.fillMaxSize().background(baseColor),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier.size(360.dp).onGloballyPositioned {
                val pos = it.positionOnScreen()
                Log.i("PatternAcceptance", "rect x=${pos.x} y=${pos.y} w=${it.size.width} h=${it.size.height}")
            }
        ) {
            Canvas(Modifier.fillMaxSize().glassBackdropSource()) {
                drawRect(baseColor)
                drawRect(
                    blockColor,
                    topLeft = Offset(size.width * blockStartX, size.height * blockStartY),
                    size = Size(size.width * blockWidthFrac, size.height * blockHeightFrac)
                )
            }
            GlassSurface(modifier = Modifier.fillMaxSize(), style = GlassStyle.Panel) {
                Text(
                    "I",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 14.sp),
                    modifier = Modifier.offset(x = textOffsetXDp.dp, y = textOffsetYDp.dp)
                )
            }
        }
    }
}

class PatternAcceptanceActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dark = intent.getBooleanExtra("dark", false)
        val transparency = intent.getIntExtra("transparency", 100)
        val baseBgColorHex = intent.getStringExtra("baseBgColor") ?: if (dark) "#15181A" else "#FFFFFF"
        val blockColorHex = intent.getStringExtra("blockColor") ?: if (dark) "#AAAAAA" else "#0000FF"
        val blockStartX = intent.getFloatExtra("blockStartX", 2f / 8f)
        val blockStartY = intent.getFloatExtra("blockStartY", 2f / 8f)
        val blockWidthFrac = intent.getFloatExtra("blockWidthFrac", 3f / 8f)
        val blockHeightFrac = intent.getFloatExtra("blockHeightFrac", 3f / 8f)
        val textOffsetXDp = intent.getFloatExtra("textOffsetX", 162f)
        val textOffsetYDp = intent.getFloatExtra("textOffsetY", 150f)
        enableEdgeToEdge()
        setContent {
            MiradTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalGlassAppearance provides GlassAppearance(transparency)) {
                    GlassBackdropHost {
                        MixedPatternPreview(
                            baseBgColorHex = baseBgColorHex,
                            blockColorHex = blockColorHex,
                            blockStartX = blockStartX,
                            blockStartY = blockStartY,
                            blockWidthFrac = blockWidthFrac,
                            blockHeightFrac = blockHeightFrac,
                            textOffsetXDp = textOffsetXDp,
                            textOffsetYDp = textOffsetYDp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ColorContrastPreview(backdropColorHex: String) {
    val parsedColor = remember(backdropColorHex) {
        val raw = runCatching { android.graphics.Color.parseColor(backdropColorHex) }
            .getOrDefault(android.graphics.Color.BLUE)
        androidx.compose.ui.graphics.Color(raw)
    }
    Box(Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .glassBackdropSource()
                .background(parsedColor)
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 64.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            GlassSurface(
                style = GlassStyle.Toolbar,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "工具栏主文字可读性检验",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "工具栏次要文字与状态说明 100%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            GlassSurface(
                style = GlassStyle.Panel,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "面板主文字对比度检验",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "面板次要文字：高饱和背景下的可读性保护（不低于 4.5:1）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "底部区域次要文字可读性采样",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ReaderPreview(
    dark: Boolean, initialSettings: Boolean, longTitle: Boolean, fullScreen: Boolean,
    typography: Boolean, initialDialog: Boolean, probe: Boolean, windowed: Boolean, onBack: () -> Unit
) {
    val activity = LocalContext.current as Activity
    val policy = ReaderWindowPolicy(fullScreen, dark)
    ApplyReaderWindowPolicy(activity.window, policy)
    DisposableEffect(activity) {
        onDispose { WindowInsetsControllerCompat(activity.window, activity.window.decorView).show(WindowInsetsCompat.Type.systemBars()) }
    }
    var config by remember { mutableStateOf(ReaderConfig.DEFAULT.copy(theme = if (dark) ThemeKey.DARK else ThemeKey.PAPER, pageMode = PageMode.SCROLL, fullScreen = fullScreen)) }
    val fonts = remember { mutableStateListOf(
        ReadingFont("霞鹜文楷", assetName = ReadingFonts.BUNDLED_WENKAI),
        ReadingFont("思源宋体", assetName = ReadingFonts.BUNDLED_SERIF),
        ReadingFont("BarlowCondensed-Bold", filePath = "preview-only.ttf")
    ) }
    var view by remember { mutableStateOf<ReaderWebView?>(null) }
    var ready by remember { mutableIntStateOf(0) }
    var chrome by remember { mutableStateOf(true) }
    var settings by remember { mutableStateOf(initialSettings) }
    var dialog by remember { mutableStateOf(initialDialog) }
    var bookmarked by remember { mutableStateOf(false) }
    var percent by remember { mutableFloatStateOf(.35f) }
    val title = if (longTitle) "第一章 雪夜出城 · 第二章 破庙逢人 · 第三章 一纸旧约" else "第一章 雪夜出城"
    val backdrop = LocalGlassBackdrop.current
    LaunchedEffect(backdrop) {
        snapshotFlow { backdrop?.image }.collect {
            Log.i("GlassPreview", if (it == null) "backdropReleased" else "backdropFrame=${it.width}x${it.height}")
        }
    }
    WebViewGlassBackdrop(view, chrome || settings || dialog, listOf(config, ready))
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, backdrop) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val observer = LifecycleEventObserver { _, event ->
            Log.i("GlassPreview", "lifecycle=$event")
            if (event == Lifecycle.Event.ON_STOP) {
                val attempts = backdrop?.captureAttempts
                handler.postDelayed({
                    Log.i("GlassPreview", "backgroundCaptureStopped=${attempts == backdrop?.captureAttempts && backdrop?.image == null} attempts=${backdrop?.captureAttempts}")
                }, 500)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); handler.removeCallbacksAndMessages(null) }
    }
    LaunchedEffect(probe, ready) {
        if (probe && ready > 0 && view != null && backdrop != null) {
            runBackdropProbe(activity, view!!, backdrop, windowed, fullScreen,
                setChrome = { chrome = it }, setSettings = { settings = it }, setDialog = { dialog = it })
        }
    }
    val attachedView = view
    DisposableEffect(attachedView) { onDispose { attachedView?.destroy() } }
    LaunchedEffect(config, ready) {
        if (ready > 0) {
            val css = ReaderCss.themeCss(config, ThemePaletteResolver.resolve(config, dark))
            view?.evaluateJavascript("MiradReader.setThemeCssOnly(${JSONObject.quote(css)}, {mode:'SCROLL',anim:'NONE',pad:24,padTop:42,padBottom:38,windowedScroll:$windowed,chapterCount:${if (windowed) 3 else 1}});", null)
        }
    }
    CompositionLocalProvider(LocalReaderWindowPolicy provides policy) {
    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { context ->
            ReaderWebView(context).also { web ->
                view = web
                web.settings.javaScriptEnabled = true
                web.settings.blockNetworkLoads = true
                web.settings.allowFileAccess = false
                web.settings.allowContentAccess = false
                web.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                        Log.i("GlassPreview", "web: ${message.message()}")
                        return true
                    }
                }
                web.onTap = { _, _ -> chrome = !chrome; Log.i("GlassPreview", "chromeVisible=$chrome") }
                web.onTouchDown = { Log.i("GlassPreview", "readerTouch") }
                val assets = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context)).build()
                web.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                        assets.shouldInterceptRequest(request.url)
                            ?: WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                    override fun onPageFinished(webView: WebView, url: String?) {
                        web.postDelayed({
                            ready++
                            web.evaluateJavascript("JSON.stringify({text:document.body.innerText.length,background:getComputedStyle(document.body).backgroundColor,reader:!!window.MiradReader})") {
                                Log.i("GlassPreview", "readerReady $it")
                            }
                        }, 300)
                    }
                }
                val structural = context.assets.open("reader/reader.css").bufferedReader().use { it.readText() }
                val javascript = context.assets.open("reader/reader.js").bufferedReader().use { it.readText() }
                val css = ReaderCss.themeCss(config, ThemePaletteResolver.resolve(config, dark))
                // Existing regression text from tools/make_samples.py, kept entirely in memory.
                val paragraphs = listOf(
                    "雪下了整整一夜，官道上的车辙被填得平平整整，连马蹄印都寻不见半点痕迹。",
                    "沈砚把行囊往肩上一甩，回头望了一眼城门上那盏昏黄的灯笼，没再停留。",
                    "他怀里揣着半块冷透的胡饼，那是母亲昨夜塞给他的，说是路上压压饥。"
                )
                val chapter = "<h2>第一章 雪夜出城</h2>" + (0..if (probe) 44 else 14).joinToString("") { "<p>${paragraphs[it % 3]}</p>" }
                val body = if (windowed) (0..2).joinToString("") { "<section class='mirad-chapter' data-chapter='$it'>$chapter</section>" } else chapter
                val html = "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'><style>$structural</style><style id='mirad-theme'>$css</style></head><body><div id='mirad-content' data-chapter='0'>$body</div><script>window.__probeChapter=0;window.MiradBridge={onReady(){},onState(){},onMetrics(){},onSelection(){},onChapterChanged(index){window.__probeChapter=index;}};$javascript;MiradReader.init({mode:'SCROLL',anim:'NONE',pad:24,padTop:42,padBottom:38,windowedScroll:$windowed,chapterCount:${if (windowed) 3 else 1}});</script></body></html>"
                web.loadDataWithBaseURL("https://appassets.androidplatform.net/preview/", html, "text/html", "utf-8", null)
            }
        }, modifier = Modifier.fillMaxSize())
        ReaderSystemBarProtection(androidx.compose.ui.graphics.Color(ThemePaletteResolver.resolve(config, dark).background))
        if (chrome) {
            Box(Modifier.align(Alignment.TopCenter)) {
                ReaderTopBar(title, "风雪归人", bookmarked, false,
                    onBack, { bookmarked = !bookmarked }, {}, {}, {}, {}, { settings = true }, {})
            }
            Box(Modifier.align(Alignment.BottomCenter)) {
                ReaderBottomBar(percent, 2, 8, 0, 6,
                    {}, {}, { settings = true }, {}, {}, {}, false,
                    { percent = it; Log.i("GlassPreview", "sliderCommit=$it") })
            }
        }
        if (settings) ReaderSettingsPanel(
            config = config, fonts = fonts,
            onChange = { config = it(config); Log.i("GlassPreview", "configChanged") },
            onOpenAutoRead = {}, onResetGlobal = { config = ReaderConfig.DEFAULT },
            onDismiss = { settings = false; Log.i("GlassPreview", "sheetDismiss") },
            // 阅读面板只承载阅读级页面；reader-appearance 场景落在面板首页。
            initialPage = if (typography) ReadingSettingsPage.TYPOGRAPHY else null,
            onDeleteFont = { font ->
                fonts.remove(font)
                if (config.customFontPath == font.filePath && font.filePath != null) {
                    config = config.copy(fontFamily = FontFamilyKey.SYSTEM, customFontPath = null, customFontAsset = null)
                }
                Log.i("GlassPreview", "fontDeleted current=${config.fontFamily}")
            }
        )
        if (dialog) MiradDialog(
            onDismissRequest = { dialog = false },
            title = { Text("写笔记") },
            text = { Text("雪下了整整一夜，官道上的车辙被填得平平整整，连马蹄印都寻不见半点痕迹。") },
            confirmButton = { MiradTextButton(onClick = { dialog = false }) { Text("保存") } },
            dismissButton = { MiradTextButton(onClick = { dialog = false }) { Text("取消") } }
        )
    }
    }
}

/** Sample metadata uses the repository's regression book/chapter names; never persisted. */
private fun sampleBooks(): List<ShelfBook> {
    val titles = listOf("风雪归人", "雪夜出城", "破庙逢人", "一纸旧约", "剑鸣于匣", "各奔东西", "归来不是少年")
    return (0..20).map { index ->
        ShelfBook(BookEntity(id = index + 1L, title = titles[index % titles.size], author = "测试作者",
            format = if (index % 3 == 0) "EPUB" else "TXT", filePath = "", totalChapters = 6),
            progress = null, percent = 0f, lastReadAt = 0L)
    }
}
