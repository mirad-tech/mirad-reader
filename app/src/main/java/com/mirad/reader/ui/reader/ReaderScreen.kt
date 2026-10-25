package com.mirad.reader.ui.reader

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.mirad.reader.ui.common.MiradTextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.mirad.reader.ui.common.LocalReaderWindowPolicy
import com.mirad.reader.ui.common.ReaderWindowPolicy
import com.mirad.reader.tts.ReaderTtsActions
import com.mirad.reader.ui.common.ApplyReaderWindowPolicy
import com.mirad.reader.ui.common.ReaderSystemBarProtection
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.mirad.reader.AppContainer
import com.mirad.reader.core.model.OrientationKey
import com.mirad.reader.core.model.PageAnim
import com.mirad.reader.core.model.PageMode
import com.mirad.reader.core.model.ReaderConfig
import com.mirad.reader.core.model.AppAppearance
import com.mirad.reader.ui.common.LocalAppAppearance
import com.mirad.reader.ui.common.LocalAppAppearanceController
import com.mirad.reader.ui.common.LocalAppDarkTheme
import com.mirad.reader.ui.common.LocalSettingsCommit
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import com.mirad.reader.core.reader.ReaderCss
import androidx.compose.ui.platform.LocalConfiguration
import kotlin.math.roundToInt
import com.mirad.reader.core.model.StatsMath
import com.mirad.reader.core.model.ThemePalette
import com.mirad.reader.core.model.ThemePaletteResolver
import com.mirad.reader.data.db.entity.BookEntity
import com.mirad.reader.data.repo.SearchHit
import com.mirad.reader.reader.ReaderSession
import com.mirad.reader.reader.ReaderWebView
import com.mirad.reader.reader.SelectionAction
import com.mirad.reader.tts.SentenceSplitter
import com.mirad.reader.tts.TtsBus
import com.mirad.reader.tts.TtsController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs

@Composable
fun ReaderScreen(
    container: AppContainer,
    bookId: Long,
    systemDark: Boolean,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val latestSystemDark by rememberUpdatedState(systemDark)
    var book by remember { mutableStateOf<BookEntity?>(null) }
    var session by remember { mutableStateOf<ReaderSession?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(bookId) {
        val loaded = container.books.getBook(bookId)
        if (loaded == null) {
            error = "书籍不存在"
            return@LaunchedEffect
        }
        val appearance = container.settings.currentAppAppearance()
        val config = appearance.resolveReaderConfig(container.settings.currentGlobal(), latestSystemDark)
        val created = ReaderSession(container, loaded, scope)
        runCatching { created.prepare(config, appearance.effectiveDark(latestSystemDark)) }
            .onFailure { error = it.message ?: "打开失败" }
        book = loaded
        session = created
    }

    val current = session
    val currentBook = book
    if (error != null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(error!!)
                MiradTextButton(onClick = onBack) { Text("返回书架") }
            }
        }
        return
    }
    if (current == null || currentBook == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    DisposableEffect(current) {
        onDispose { current.release(); com.mirad.reader.reader.ReaderKeys.pager = null }
    }

    ReaderContent(
        container = container,
        session = current,
        book = currentBook,
        systemDark = systemDark,
        onBack = onBack
    )
}

private data class PendingNote(
    val start: Int,
    val end: Int,
    val text: String,
    val chapterIndex: Int
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReaderContent(
    container: AppContainer,
    session: ReaderSession,
    book: BookEntity,
    systemDark: Boolean,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val state by session.state.collectAsState()
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

    val ttsState by TtsBus.state.collectAsState()
    var ttsSentences by remember { mutableStateOf<List<SentenceSplitter.Sentence>>(emptyList()) }
    var ttsChapterIndex by remember { mutableStateOf(-1) }

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

    suspend fun ensureTtsSentences(): List<SentenceSplitter.Sentence> {
        val chapter = session.state.value.chapterIndex
        if (ttsChapterIndex == chapter && ttsSentences.isNotEmpty()) return ttsSentences
        val text = session.jsText() ?: session.chapterText(chapter)
        val sentences = SentenceSplitter.split(text)
        ttsSentences = sentences
        ttsChapterIndex = chapter
        return sentences
    }

    /**
     * Android 13+ 的前台通知需要 POST_NOTIFICATIONS 运行时授权，否则朗读时通知栏不会出现播放控制。
     * 只在用户真正开始朗读时请求一次；用户拒绝也不阻断朗读本身（只是没有通知栏控制）。
     */
    val permissionActivity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity

    fun ensureNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        val activity = permissionActivity ?: return
        val permission = android.Manifest.permission.POST_NOTIFICATIONS
        if (activity.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED) return
        @Suppress("DEPRECATION")
        activity.requestPermissions(arrayOf(permission), 4127)
    }

    fun startTts(fromBeginning: Boolean) {
        // 朗读与自动阅读都控制阅读位置：启动朗读先暂停自动阅读，且不自动恢复。
        session.pauseAutoRead()
        ensureNotificationPermission()
        val targetChapter = session.state.value.chapterIndex
        val targetBookId = book.id
        scope.launch {
            val sentences = ensureTtsSentences()
            if (sentences.isEmpty()) {
                toast = "本章没有可朗读的内容"
                return@launch
            }
            // 若异步等待期间章节已改变，直接作废在途请求，绝不能错位播放
            if (!ReaderTtsActions.isRequestValid(targetBookId, book.id, targetChapter, session.state.value.chapterIndex)) {
                return@launch
            }
            val currentOffset = session.state.value.offset
            val startIndex = ReaderTtsActions.computeStartIndex(sentences, currentOffset, fromBeginning)
            tts.play(
                bookId = targetBookId,
                chapterIndex = targetChapter,
                title = state.chapterTitle,
                sentences = sentences,
                startIndex = startIndex,
                rate = config.ttsSpeechRate,
                pitch = config.ttsPitch
            )
        }
    }

    // 朗读与自动阅读互斥：通知栏/耳机等外部入口恢复朗读时，也要暂停自动阅读。
    LaunchedEffect(ttsState.playing) {
        if (ttsState.playing) session.pauseAutoRead()
    }

    // 朗读时高亮当前句并跟随滚动（严格核对 bookId 与 chapterIndex，防止旧章朗读驱动新章位置）
    LaunchedEffect(ttsState.active, ttsState.bookId, ttsState.chapterIndex, ttsState.sentenceStart, ttsState.sentenceEnd, state.ready, state.chapterIndex) {
        if (!state.ready) return@LaunchedEffect
        val isCurrentChapterTts = ttsState.active && ttsState.bookId == book.id && ttsState.chapterIndex == state.chapterIndex
        if (isCurrentChapterTts) {
            session.evaluatePublic("MiradReader.markTts(${ttsState.sentenceStart}, ${ttsState.sentenceEnd});")
            session.evaluatePublic("MiradReader.gotoOffset(${ttsState.sentenceStart}, true);")
        } else {
            session.evaluatePublic("MiradReader.clearTts();")
        }
    }

    // 本章读完后自动进入下一章（必须严格核对已读完的章是否就是当前屏幕上的章）
    LaunchedEffect(ttsState.chapterFinished) {
        if (!ttsState.chapterFinished) return@LaunchedEffect
        val finishedChapter = ttsState.chapterIndex
        val finishedBookId = ttsState.bookId
        tts.consumeChapterFinished()
        if (!ReaderTtsActions.canAutoAdvanceChapter(
                finishedBookId = finishedBookId,
                currentBookId = book.id,
                finishedChapter = finishedChapter,
                currentChapter = session.state.value.chapterIndex,
                autoNextChapterEnabled = config.ttsAutoNextChapter
            )
        ) return@LaunchedEffect
        val next = finishedChapter + 1
        if (next < session.chapters.size) {
            // 接章任务委托给独立协程作用域，绝不因消费事件后的 key 变化而被自身取消
            scope.launch {
                ttsSentences = emptyList()
                ttsChapterIndex = -1
                session.gotoChapter(next, 0)
                var waited = 0
                while (!session.state.value.ready && waited < 4000) {
                    if (session.state.value.chapterIndex != next) return@launch
                    kotlinx.coroutines.delay(60)
                    waited += 60
                }
                kotlinx.coroutines.delay(120)
                if (session.state.value.chapterIndex == next) {
                    startTts(true)
                }
            }
        } else {
            toast = "已读完最后一章"
        }
    }

    // 屏幕亮度 / 常亮 / 方向 / 全屏
    ApplyWindowSettings(config, effectiveDark)

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

    // COVER 翻页动画的旧页位图
    var overlay by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var overlayX by remember { mutableFloatStateOf(0f) }
    var animating by remember { mutableStateOf(false) }
    var manualTurnJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var captureRunning by remember { mutableStateOf(false) }
    var coverAnimator by remember { mutableStateOf<android.animation.ValueAnimator?>(null) }
    var coverBitmap by remember { mutableStateOf<Bitmap?>(null) }

    fun clearOverlay() {
        manualTurnJob?.cancel()
        manualTurnJob = null
        coverAnimator?.let { animator ->
            animator.removeAllListeners()
            animator.removeAllUpdateListeners()
            animator.cancel()
        }
        coverAnimator = null
        val released = coverBitmap
        coverBitmap = null
        overlay = null
        overlayX = 0f
        animating = false
        // Let Compose remove the old Image node before releasing its GPU buffer.
        val owner = session.webView
        if (owner != null && released != null) owner.postOnAnimation {
            owner.postOnAnimation { if (!released.isRecycled) released.recycle() }
        } else if (released != null && !released.isRecycled) released.recycle()
    }
    DisposableEffect(session) { onDispose { clearOverlay() } }
    DisposableEffect(lifecycleOwner, session) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) clearOverlay()
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose { lifecycleOwner?.lifecycle?.removeObserver(observer) }
    }

    /**
     * 开启或继续自动阅读：先关掉弹层与工具栏，再开始，避免面板遮住正文。
     * 朗读正在占用阅读位置时先暂停朗读；两者都不会自动恢复对方。
     */
    fun beginAutoRead() {
        if (ttsState.active) tts.pause()
        showAutoRead = false
        chromeVisible = false
        session.startAutoRead()
    }

    suspend fun performTurn(delta: Int, auto: Boolean = false): Boolean {
        if (captureRunning) return false
        // 上下滚动模式：点击左右两侧按屏平滑滚动，不整页跳（翻页动画也不适用）
        if (config.pageMode == PageMode.SCROLL) {
            session.scrollStep(delta)
            return false
        }
        val view = session.webView
        val useOverlay = config.pageAnim == PageAnim.COVER && view != null && state.ready && !animating
        if (!useOverlay) {
            val switched = session.turnPage(delta, pauseAuto = !auto)
            if (!auto && !switched && session.state.value.ready) {
                val s = session.state.value
                if ((delta > 0 && s.page >= s.pages - 1 && s.chapterIndex >= s.chapterCount - 1) ||
                    (delta < 0 && s.page <= 0 && s.chapterIndex <= 0)
                ) {
                    toast = if (delta > 0) "已经是最后一章" else "已经是第一章"
                }
            }
            return switched
        }
        val pageView = view ?: return false
        var switched = false
        fun showCapturedPage(bitmap: Bitmap?) {
            if (bitmap == null) {
                switched = session.turnPage(delta, pauseAuto = !auto)
                return
            }
            coverBitmap = bitmap
            overlay = bitmap.asImageBitmap()
            overlayX = 0f
            animating = true
            switched = session.turnPage(delta, pauseAuto = !auto)
            val distance = if (delta > 0) -pageView.width.toFloat() else pageView.width.toFloat()
            coverAnimator = android.animation.ValueAnimator.ofFloat(0f, distance).apply {
                duration = 260
                addUpdateListener { overlayX = it.animatedValue as Float }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        clearOverlay()
                    }

                    override fun onAnimationCancel(animation: android.animation.Animator) {
                        clearOverlay()
                    }
                })
                start()
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            captureRunning = true
            try {
                val bitmap = hardwareScene.capturePage()
                if (!sessionActive) bitmap?.recycle() else showCapturedPage(bitmap)
            } finally { captureRunning = false }
        } else {
            captureView(pageView, ::showCapturedPage)
        }
        return switched
    }
    fun turnFromTap(delta: Int) {
        if (captureRunning || manualTurnJob?.isActive == true) return
        manualTurnJob = scope.launch { performTurn(delta) }
    }

    // 自动翻页复用界面层的 performTurn（含覆盖翻页动画）；每次重组更新为最新配置。
    SideEffect {
        session.autoPageTurn = { performTurn(1, auto = true) }
    }
    DisposableEffect(session) {
        onDispose { session.autoPageTurn = null }
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
                ratio < 0.40f -> turnFromTap(-1)
                ratio > 0.60f -> turnFromTap(1)
                else -> chromeVisible = !chromeVisible
            }
            else -> when {
                ratio < 0.32f -> turnFromTap(-1)
                ratio > 0.68f -> turnFromTap(1)
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
        val overlayBitmap = overlay
        if (overlayBitmap != null) {
            Image(
                bitmap = overlayBitmap,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationX = overlayX }
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
                onAutoRead = {
                    // 打开面板即暂停：调速时保持暂停，继续时再用新速度启动。
                    session.pauseAutoRead()
                    showAutoRead = true
                },
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
                onTts = {
                    ReaderTtsActions.onToolbarClick(
                        isPlaying = ttsState.playing,
                        onPause = { tts.pause() },
                        onOpenPanel = { showTts = true }
                    )
                },
                speaking = ttsState.playing,
                onSliderChange = {
                    if (ttsState.active) tts.stop()
                    // 拖动进度条是"直接跳转"，不属于翻页：先清掉可能残留的覆盖位图，
                    // 否则旧页会盖在新位置上，看起来就是两页文字叠在一起。
                    clearOverlay()
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
                        ReaderTtsActions.StartPlan.StartFromBeginning -> startTts(fromBeginning = true)
                        ReaderTtsActions.StartPlan.StartFromCurrent -> startTts(fromBeginning = false)
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
                clearOverlay()
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

/** 亮度 / 常亮 / 方向 / 全屏。 */
@Composable
private fun ApplyWindowSettings(config: ReaderConfig, darkTheme: Boolean) {
    val context = LocalContext.current
    val activity = context as? Activity
    ApplyReaderWindowPolicy(activity?.window, ReaderWindowPolicy(config.fullScreen, darkTheme))
    DisposableEffect(activity, config.brightness, config.keepScreenOn, config.orientation) {
        val window = activity?.window
        if (window != null) {
            val params = window.attributes
            params.screenBrightness = if (config.brightness < 0f) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            else config.brightness.coerceIn(0.01f, 1f)
            window.attributes = params
            if (config.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity.requestedOrientation = when (config.orientation) {
                OrientationKey.PORTRAIT -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                OrientationKey.LANDSCAPE -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
        onDispose { }
    }
    DisposableEffect(activity) {
        onDispose {
            val w = activity?.window ?: return@onDispose
            w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val params = w.attributes
            params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            w.attributes = params
            val controller = WindowInsetsControllerCompat(w, w.decorView)
            controller.show(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
}

/** 页眉页脚：章节名 / 时间 / 电量 / 页码 / 进度。 */
@Composable
internal fun HeaderFooter(
    config: ReaderConfig,
    palette: ThemePalette,
    chapterTitle: String,
    page: Int,
    pages: Int,
    percent: Float,
    insetLeftDp: Int = -1
) {
    val clock = rememberClock(config.showClock)
    val battery = rememberBattery(config.showBattery)
    val textColor = Color(palette.secondaryText)
    // 正文列居中后，页眉页脚要跟着列左边缘走，否则会贴在屏幕边上与正文错位。
    val horizontal = if (insetLeftDp >= 0) (insetLeftDp + 4).dp else (config.horizontalPaddingDp + 4).dp
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(readerSafeInsets().only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .padding(horizontal = horizontal, vertical = 4.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        if (config.showHeader) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = chapterTitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor,
                    maxLines = 1
                )
                Row {
                    if (config.showBattery) {
                        Text("$battery%  ", style = MaterialTheme.typography.labelSmall, color = textColor)
                    }
                    if (config.showClock) {
                        Text(clock, style = MaterialTheme.typography.labelSmall, color = textColor)
                    }
                }
            }
        }
        if (config.showFooter) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = String.format(java.util.Locale.US, "%.1f%%", percent * 100f),
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor
                )
                if (config.showPageNumber) {
                    Text(
                        text = "${page + 1} / ${pages}",
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor
                    )
                }
            }
        }
    }
}

@Composable
private fun rememberClock(enabled: Boolean): String {
    var time by remember { mutableStateOf(currentTime()) }
    LaunchedEffect(enabled) {
        while (enabled) {
            time = currentTime()
            delay(20_000)
        }
    }
    return time
}

private fun currentTime(): String = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))

@Composable
private fun rememberBattery(enabled: Boolean): Int {
    val context = LocalContext.current
    var level by remember { mutableStateOf(readBattery(context)) }
    LaunchedEffect(enabled) {
        while (enabled) {
            level = readBattery(context)
            delay(60_000)
        }
    }
    return level
}

private fun readBattery(context: Context): Int = runCatching {
    val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    if (level >= 0 && scale > 0) level * 100 / scale else -1
}.getOrDefault(-1)

/** 用软件层捕获当前页位图，用于覆盖翻页动画；失败时返回 null（自动退化为普通翻页）。 */
private fun captureView(view: View, onResult: (Bitmap?) -> Unit) {
    if (view.width <= 0 || view.height <= 0) {
        onResult(null)
        return
    }
    val bitmap = runCatching {
        Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    }.getOrNull() ?: return onResult(null)
    val success = runCatching {
        val canvas = android.graphics.Canvas(bitmap)
        view.draw(canvas)
        hasVisibleContent(bitmap)
    }.getOrDefault(false)
    if (success) {
        onResult(bitmap)
    } else {
        bitmap.recycle()
        onResult(null)
    }
}

/** 抽样检查位图是否有实际内容，避免把"空白快照"当成有效动画帧。 */
private fun hasVisibleContent(bitmap: Bitmap): Boolean {
    val stepX = (bitmap.width / 12).coerceAtLeast(1)
    val stepY = (bitmap.height / 12).coerceAtLeast(1)
    var visible = 0
    var y = 0
    while (y < bitmap.height) {
        var x = 0
        while (x < bitmap.width) {
            val pixel = bitmap.getPixel(x, y)
            if (android.graphics.Color.alpha(pixel) > 8) visible++
            x += stepX
        }
        y += stepY
    }
    return visible >= 6
}
