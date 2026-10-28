package com.mirad.reader.ui.reader

import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.mirad.reader.ui.common.MiradTextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.mirad.reader.ui.common.LocalReaderWindowPolicy
import com.mirad.reader.ui.common.ReaderWindowPolicy
import com.mirad.reader.tts.ReaderTtsActions
import com.mirad.reader.ui.common.ReaderSystemBarProtection
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.animation.core.tween
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import com.mirad.reader.AppContainer
import com.mirad.reader.core.model.PageAnim
import com.mirad.reader.core.model.PageMode
import com.mirad.reader.core.model.ReaderConfig
import com.mirad.reader.core.model.AppAppearance
import com.mirad.reader.ui.common.LocalAppAppearance
import com.mirad.reader.ui.common.LocalAppAppearanceController
import com.mirad.reader.ui.common.LocalAppDarkTheme
import com.mirad.reader.ui.common.LocalSettingsCommit
import androidx.compose.foundation.layout.asPaddingValues
import com.mirad.reader.core.reader.ReaderCss
import androidx.compose.ui.platform.LocalConfiguration
import kotlin.math.roundToInt
import com.mirad.reader.core.model.ThemePaletteResolver
import com.mirad.reader.data.db.entity.BookEntity
import com.mirad.reader.reader.AutoReadState
import com.mirad.reader.reader.ReaderSession
import com.mirad.reader.reader.ReaderWebView
import com.mirad.reader.reader.SelectionAction
import com.mirad.reader.tts.TtsBus
import com.mirad.reader.tts.TtsController
import com.mirad.reader.ui.common.ApplyReaderWindowSettings

private data class PendingNote(
    val start: Int,
    val end: Int,
    val text: String,
    val chapterIndex: Int
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ReaderContent(
    container: AppContainer,
    session: ReaderSession,
    book: BookEntity,
    systemDark: Boolean,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val stateHolder = session.state.collectAsState()
    val state by stateHolder
    val appearance = LocalAppAppearance.current
    val appearanceController = LocalAppAppearanceController.current
    val latestSystemDark by rememberUpdatedState(systemDark)
    val effectiveDark = LocalAppDarkTheme.current ?: systemDark
    val config = appearance?.resolveReaderConfig(state.config, systemDark) ?: state.config
    val palette = remember(config.theme, config.backgroundColor, effectiveDark) {
        ThemePaletteResolver.resolve(config, effectiveDark)
    }
    var appliedDark by remember(session) { mutableStateOf(effectiveDark) }
    var sessionActive by remember(session) { mutableStateOf(true) }
    ReaderAppearanceEffect(config, effectiveDark, state.ready) { desired, dark, resume ->
        if (shouldRefreshReaderAppearance(session.state.value.config, desired, appliedDark, dark, resume)) {
            session.applyConfigToWebView(desired, dark)
            appliedDark = dark
        }
    }

    var chromeVisible by remember { mutableStateOf(false) }
    val hardwareScene = remember(session) { ReaderHardwareSceneController() }
    var modalVisible by remember(session) { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showProgress by remember { mutableStateOf(false) }
    var showNotes by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var showTts by remember { mutableStateOf(false) }
    var showAutoRead by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    val feedback = com.mirad.reader.ui.common.rememberFeedbackState("reader:${book.id}")
    var toast by feedback
    var bottomControlsHeight by remember { mutableStateOf(0) }
    var pendingNote by remember { mutableStateOf<PendingNote?>(null) }
    var noteDraft by remember { mutableStateOf("") }

    val autoReadState by session.autoReadState.collectAsState()
    val autoReadNotice by session.autoReadNotice.collectAsState()
    LaunchedEffect(autoReadNotice) {
        val message = autoReadNotice ?: return@LaunchedEffect
        toast = message
        session.consumeAutoReadNotice()
    }

    val tts = remember { TtsController(container.context) }
    DisposableEffect(Unit) { onDispose { tts.stop() } }

    val ttsStateHolder = TtsBus.state.collectAsState()
    val ttsState by ttsStateHolder
    // 落库防抖：阅读设置全书通用，拖滑杆时停顿 400ms 再落库全局配置。
    val onConfigApplied: (ReaderConfig, AppAppearance) -> Unit = { updated, currentAppearance ->
        if (sessionActive) {
            val dark = currentAppearance.effectiveDark(latestSystemDark)
            session.applyConfigToWebView(currentAppearance.resolveReaderConfig(updated, latestSystemDark), dark)
            appliedDark = dark
        }
    }
    val applyConfig: ((ReaderConfig) -> ReaderConfig) -> Unit = { transform ->
        appearanceController?.updateReadingConfig(effectiveDark, transform, onConfigApplied)
    }
    DisposableEffect(session, appearanceController) {
        sessionActive = true
        onDispose {
            sessionActive = false
            appearanceController?.commitReadingConfig()
        }
    }

    val startTts = rememberReaderTts(tts, ttsStateHolder, session, book, config, stateHolder, scope) { toast = it }

    // 屏幕亮度 / 常亮 / 方向 / 全屏
    ApplyReaderWindowSettings(config, effectiveDark)

    // 打开目录/设置/搜索/朗读等弹层，或呼出上下工具栏：自动阅读暂停，关闭后也不自动继续。
    val overlayVisible = showToc || showSettings || showProgress || showNotes ||
        showSearch || showTts || showAutoRead || pendingNote != null
    LaunchedEffect(overlayVisible, chromeVisible) {
        if (overlayVisible || chromeVisible) session.pauseAutoRead()
    }

    // 横竖屏切换会重排正文：屏幕方向变化时暂停自动阅读（尺寸/安全区域变化由 updateInsets 兜底）。
    val configuration = LocalConfiguration.current
    LaunchedEffect(configuration.orientation) {
        session.pauseAutoRead()
    }

    // 前后台切换时结算阅读时长（后台计时不算入统计）
    val lifecycleOwner = context as? androidx.lifecycle.LifecycleOwner
    DisposableEffect(lifecycleOwner, session) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> {
                    // 切后台/锁屏：暂停自动阅读并保存当前位置；回到前台由用户手动继续。
                    session.pauseAutoRead()
                    session.saveProgressNow()
                    session.endSession()
                }
                androidx.lifecycle.Lifecycle.Event.ON_START -> session.startSession()
                else -> Unit
            }
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose { lifecycleOwner?.lifecycle?.removeObserver(observer) }
    }

    // API 29+ 的覆盖页使用 GPU buffer，不能把正文降成软件层。
    LaunchedEffect(config.pageAnim) {
        session.webView?.setLayerType(
            if (android.os.Build.VERSION.SDK_INT < 29 && config.pageAnim == PageAnim.COVER) View.LAYER_TYPE_SOFTWARE
            else View.LAYER_TYPE_HARDWARE,
            null
        )
    }

    val pageTurn = rememberReaderPageTurn(session, config, stateHolder, scope, lifecycleOwner, hardwareScene,
        { sessionActive }) { toast = it }

    /**
     * 开启或继续自动阅读：先关掉弹层与工具栏，再开始，避免面板遮住正文。
     * 朗读正在占用阅读位置时先暂停朗读；两者都不会自动恢复对方。
     */
    fun beginAutoRead() {
        ReaderAutoReadActions.start(ttsState.active, tts::pause, {
            showAutoRead = false
            chromeVisible = false
        }, session::startAutoRead)
    }

    // 正文与顶部阅读文字不再为状态栏、挖孔增加留白，也不执行挖孔区域文字裁剪；保留页眉页脚自身占位与导航栏预留。
    val systemBarInsets = readerSafeInsets().asPaddingValues()
    val layoutDirection = LocalLayoutDirection.current
    val reserveTop = if (config.showHeader) 24 else 0
    val reserveBottom = systemBarInsets.calculateBottomPadding().value.roundToInt() + if (config.showFooter) 24 else 0
    val reserveLeft = readerHorizontalInsetDp(systemBarInsets.calculateLeftPadding(layoutDirection))
    val reserveRight = readerHorizontalInsetDp(systemBarInsets.calculateRightPadding(layoutDirection))
    androidx.compose.runtime.LaunchedEffect(reserveTop, reserveBottom, reserveLeft, reserveRight) {
        session.updateInsets(reserveTop, reserveBottom, reserveLeft, reserveRight, 0, 0)
    }

    session.onTap = tap@{ x, y ->
        val view = session.webView ?: return@tap
        // 事件优先级（自上而下）：
        // 1) 有弹层：由弹层处理，正文不接管；
        // 2) 工具栏显示：整块正文区域单击只关闭工具栏，不翻页、不滚动；
        // 3) 工具栏隐藏：才进入原有的分页/滚动点击分区。
        if (overlayVisible) return@tap
        if (chromeVisible) {
            chromeVisible = false
            return@tap
        }
        // 上下滚动模式按"竖向分区"响应：上 1/3 往回滚一屏、下 1/3 往前滚一屏、中间呼出菜单。
        // 章首/章末继续同一方向时，由 scrollStep 接到上一章末尾 / 下一章开头。
        if (config.pageMode == PageMode.SCROLL) {
            val height = view.height.toFloat()
            val vRatio = if (height > 0) y / height else 0.5f
            when {
                vRatio < 0.32f -> session.scrollStep(-1)
                vRatio > 0.68f -> session.scrollStep(1)
                else -> chromeVisible = !chromeVisible
            }
            return@tap
        }
        val width = view.width.toFloat()
        val ratio = if (width > 0) x / width else 0.5f
        when (config.tapZone) {
            "NONE" -> chromeVisible = !chromeVisible
            // 两分模式：左右仍然各翻一页，但中央留一条窄带专门呼出菜单。
            // 否则 chromeVisible 初始为 false、返回键退出阅读、长按用于选词，用户就再也打不开目录/设置了。
            "TWO" -> when {
                ratio < 0.40f -> pageTurn.turnFromTap(-1)
                ratio > 0.60f -> pageTurn.turnFromTap(1)
                else -> chromeVisible = !chromeVisible
            }
            else -> when {
                ratio < 0.32f -> pageTurn.turnFromTap(-1)
                ratio > 0.68f -> pageTurn.turnFromTap(1)
                else -> chromeVisible = !chromeVisible
            }
        }
    }

    BackHandler(enabled = !modalVisible) {
        when {
            showToc || showSettings || showProgress || showNotes || showSearch || showTts || showAutoRead -> {
                showToc = false; showSettings = false; showProgress = false
                showNotes = false; showSearch = false; showTts = false; showAutoRead = false
            }
            chromeVisible -> chromeVisible = false
            else -> {
                session.saveProgressNow()
                tts.stop()
                onBack()
            }
        }
    }

    CompositionLocalProvider(LocalReaderWindowPolicy provides ReaderWindowPolicy(config.fullScreen, effectiveDark)) {
    ReaderHardwareScene(
            controller = hardwareScene,
            backgroundColor = palette.background,
            revision = listOf(state.chapterIndex, state.ready, config, effectiveDark),
            modifier = Modifier.fillMaxSize(),
            webViewFactory = { ctx ->
                ReaderWebView(ctx).also { view ->
                    session.attach(view)
                    view.onSelectionAction = { action ->
                        val selection = session.state.value.selection
                        if (selection == null || selection.end <= selection.start) {
                            toast = "请先选择文字"
                        } else {
                            when (action) {
                                SelectionAction.HIGHLIGHT -> {
                                    session.addHighlight(
                                        start = selection.start,
                                        end = selection.end,
                                        text = selection.text,
                                        chapterIndex = selection.chapterIndex
                                    ) { ok ->
                                        toast = if (ok) "已划线" else "划线失败"
                                    }
                                    session.clearSelection()
                                }
                                SelectionAction.NOTE -> {
                                    pendingNote = PendingNote(
                                        start = selection.start,
                                        end = selection.end,
                                        text = selection.text,
                                        chapterIndex = selection.chapterIndex
                                    )
                                    noteDraft = ""
                                }
                                SelectionAction.SEARCH -> {
                                    showSearch = true
                                }
                            }
                        }
                    }
                }
            },
            content = {
        Box(Modifier.fillMaxSize()) {
        // 旧页位图覆盖层（覆盖翻页）
        val overlayBitmap = pageTurn.overlay
        if (overlayBitmap != null) {
            Image(
                bitmap = overlayBitmap,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationX = pageTurn.overlayX() }
            )
        }

        // 页眉页脚（始终显示，非交互）：与正文列左边缘对齐
        val screenWidthDp = LocalConfiguration.current.screenWidthDp
        val colMax = if (config.maxColumnWidthEm > 0f) {
            (config.fontSizeSp * config.maxColumnWidthEm).roundToInt()
        } else {
            0
        }
        val geometry = remember(screenWidthDp, config.horizontalPaddingDp, colMax) {
            ReaderCss.PageMath.columnGeometry(screenWidthDp, config.horizontalPaddingDp, colMax)
        }
        HeaderFooter(
            config = config,
            palette = palette,
            // 开关判断只在传参处保留一处：页眉是否显示章节名由这里决定，HeaderFooter 内部不再重复判断。
            chapterTitle = if (config.showChapterTitleInHeader) state.chapterTitle else "",
            page = state.page,
            pages = state.pages,
            percent = state.percent,
            insetLeftDp = geometry.inset
        )

        ReaderSystemBarProtection(Color(palette.background))

        if (!state.ready) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color(palette.background).copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
        }
        }
        },
        overlays = {
        ReaderOverlayHost(onModalVisibilityChanged = {
            modalVisible = it
            hardwareScene.setModalVisible(it)
        }) {
        // 阅读器内所有同窗弹层（朗读/自动阅读/设置等）共享的滑块松手提交回调；
        // 不提供时 LabeledSlider 的 onCommit 落到空实现，只能等 400ms 防抖落库。
        CompositionLocalProvider(LocalSettingsCommit provides { appearanceController?.commitReadingConfig() }) {
        Box(Modifier.fillMaxSize()) {

        // 顶部/底部工具栏
        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(tween(160)) + slideInVertically(tween(180)) { -with(density) { 8.dp.roundToPx() } },
            exit = fadeOut(tween(160)) + slideOutVertically(tween(160)) { -with(density) { 8.dp.roundToPx() } },
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            ReaderTopBar(
                title = state.chapterTitle.ifBlank { book.title },
                subtitle = book.title,
                hasBookmark = state.hasBookmarkHere,
                onBack = { session.saveProgressNow(); onBack() },
                onBookmark = {
                    session.toggleBookmark { added -> toast = if (added) "已添加书签" else "已取消书签" }
                },
                onNotes = { showNotes = true },
                onSearch = { showSearch = true },
                onToc = { showToc = true },
                onAutoRead = { beginAutoRead() },
                onSettings = { showSettings = true },
                onToggleTts = {
                    ReaderTtsActions.onToolbarClick(
                        isPlaying = ttsState.playing,
                        onPause = { tts.pause() },
                        onOpenPanel = { showTts = true }
                    )
                },
                speaking = ttsState.playing
            )
        }

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(tween(160)) + slideInVertically(tween(180)) { with(density) { 8.dp.roundToPx() } },
            exit = fadeOut(tween(160)) + slideOutVertically(tween(160)) { with(density) { 8.dp.roundToPx() } },
            modifier = Modifier.align(Alignment.BottomCenter).onSizeChanged { bottomControlsHeight = it.height }
        ) {
            ReaderBottomBar(
                percent = state.percent,
                page = state.page,
                pages = state.pages,
                chapterIndex = state.chapterIndex,
                chapterCount = state.chapterCount,
                onToc = { showToc = true },
                onProgress = { showProgress = true },
                onSettings = { showSettings = true },
                onNotes = { showNotes = true },
                onSearch = { showSearch = true },
                onAutoRead = { beginAutoRead() },
                autoReading = autoReadState == AutoReadState.RUNNING,
                onSliderChange = {
                    if (ttsState.active) tts.stop()
                    // 拖动进度条是"直接跳转"，不属于翻页：先清掉可能残留的覆盖位图，
                    // 否则旧页会盖在新位置上，看起来就是两页文字叠在一起。
                    pageTurn.clearOverlay()
                    session.jumpToPercent(it)
                }
            )
        }

        if (showTts) {
            TtsSheet(
                tts = tts,
                config = config,
                onChange = applyConfig,
                onStart = { resume, fromBeginning ->
                    // 继续/开始朗读前先暂停自动阅读，两者不同时控制阅读位置。
                    session.pauseAutoRead()
                    when (ReaderTtsActions.resolveStartPlan(resume, ttsState.active, fromBeginning)) {
                        ReaderTtsActions.StartPlan.ResumeExisting -> tts.resume()
                        ReaderTtsActions.StartPlan.StartFromBeginning -> startTts(true)
                        ReaderTtsActions.StartPlan.StartFromCurrent -> startTts(false)
                    }
                },
                onClose = { showTts = false }
            )
        }

        com.mirad.reader.ui.common.FeedbackHost(
            feedback,
            Modifier.align(Alignment.BottomCenter).imePadding().then(
                if (chromeVisible) Modifier.padding(bottom = with(density) { bottomControlsHeight.toDp() })
                else Modifier.navigationBarsPadding()
            )
        )
    }

    if (showToc) {
        TocSheet(
            chapters = session.chapters,
            currentIndex = state.chapterIndex,
            onDismiss = { showToc = false },
            onSelect = { index ->
                if (ttsState.active && index != state.chapterIndex) tts.stop()
                session.gotoChapter(index)
                showToc = false
            }
        )
    }

    if (showSettings) {
        ReaderSettingsSheet(
            container = container,
            bookId = book.id,
            isEpub = book.format == com.mirad.reader.data.db.entity.BookFormat.EPUB,
            config = config,
            onChange = applyConfig,
            onOpenAutoRead = {
                // 两个入口打开同一个自动阅读弹层；先收起设置面板，避免两个弹层叠在一起。
                session.pauseAutoRead()
                showSettings = false
                showAutoRead = true
            },
            onResetGlobal = {
                // 恢复默认前列出会重置的项，与设置页共用同一个对话框。
                showResetDialog = true
            },
            onDismiss = { showSettings = false }
        )
    }

    if (showResetDialog) {
        com.mirad.reader.ui.common.ResetReadingDialog(
            onDismiss = { showResetDialog = false },
            onConfirm = { scopes ->
                appearanceController?.resetReadingConfig(scopes, onConfigApplied)
            }
        )
    }

    if (showAutoRead) {
        AutoReadSheet(
            config = config,
            state = autoReadState,
            onChange = applyConfig,
            onStart = { beginAutoRead() },
            onStop = { session.stopAutoRead() },
            onDismiss = { showAutoRead = false }
        )
    }

    if (showProgress) {
        ProgressSheet(
            percent = state.percent,
            chapterIndex = state.chapterIndex,
            chapterCount = state.chapterCount,
            chapterTitle = state.chapterTitle,
            page = state.page,
            pages = state.pages,
            onJump = {
                if (ttsState.active) tts.stop()
                pageTurn.clearOverlay()
                session.jumpToPercent(it)
            },
            onJumpChapter = { index ->
                if (index in session.chapters.indices) {
                    if (ttsState.active && index != state.chapterIndex) tts.stop()
                    session.gotoChapter(index)
                }
            },
            onDismiss = { showProgress = false }
        )
    }

    if (showNotes) {
        NotesSheet(
            container = container,
            session = session,
            book = book,
            onDismiss = { showNotes = false },
            onJump = { chapterIndex, offset ->
                if (chapterIndex != session.state.value.chapterIndex) {
                    if (ttsState.active) tts.stop()
                    session.gotoChapter(chapterIndex, offset)
                } else {
                    session.jumpToOffset(offset, mark = true)
                }
                showNotes = false
            }
        )
    }

    if (showSearch) {
        SearchSheet(
            container = container,
            session = session,
            book = book,
            onDismiss = { showSearch = false },
            onJump = { hit ->
                if (ttsState.active && hit.chapterIndex != session.state.value.chapterIndex) tts.stop()
                session.jumpToSearchHit(hit.chapterIndex, hit.match, hit.charOffset)
                showSearch = false
            }
        )
    }

    pendingNote?.let { noteInfo ->
        com.mirad.reader.ui.common.MiradDialog(
            onDismissRequest = { pendingNote = null },
            title = { Text("写笔记") },
            text = {
                Column {
                    Text(
                        text = noteInfo.text.take(80),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    androidx.compose.material3.OutlinedTextField(
                        value = noteDraft,
                        onValueChange = { noteDraft = it },
                        label = { Text("笔记内容") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                        minLines = 3, maxLines = 7
                    )
                }
            },
            confirmButton = {
                MiradTextButton(onClick = {
                    session.addHighlight(
                        start = noteInfo.start,
                        end = noteInfo.end,
                        text = noteInfo.text,
                        note = noteDraft.ifBlank { null },
                        chapterIndex = noteInfo.chapterIndex
                    ) { ok ->
                        toast = if (ok) "已保存笔记" else "保存失败"
                    }
                    session.clearSelection()
                    pendingNote = null
                }) { Text("保存") }
            },
            dismissButton = {
                MiradTextButton(onClick = { pendingNote = null }) { Text("取消") }
            }
        )
    }
    }
    }
    })
    }
}
