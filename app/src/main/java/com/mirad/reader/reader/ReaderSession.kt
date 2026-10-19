package com.mirad.reader.reader

import android.annotation.SuppressLint
import android.graphics.Color
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.mirad.reader.AppContainer
import com.mirad.reader.core.model.AutoReadSpeed
import com.mirad.reader.core.model.BackgroundType
import com.mirad.reader.core.model.BuiltinBackgrounds
import com.mirad.reader.core.model.EpubLayoutMode
import com.mirad.reader.core.model.FontFamilyKey
import com.mirad.reader.core.model.PageAnim
import com.mirad.reader.core.model.PageMode
import com.mirad.reader.core.model.ProgressMath
import com.mirad.reader.core.model.ReaderConfig
import com.mirad.reader.core.model.ThemePaletteResolver
import com.mirad.reader.core.reader.ReaderCss
import com.mirad.reader.data.db.entity.BookEntity
import com.mirad.reader.data.db.entity.BookmarkEntity
import com.mirad.reader.data.db.entity.ChapterEntity
import com.mirad.reader.data.db.entity.HighlightEntity
import com.mirad.reader.data.db.entity.ProgressEntity
import kotlinx.coroutines.CoroutineScope
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume

/** 阅读器状态。 */
data class ReaderSessionState(
    val chapterIndex: Int = 0,
    val chapterCount: Int = 0,
    val chapterTitle: String = "",
    val page: Int = 0,
    val pages: Int = 1,
    val offset: Int = 0,
    val textLength: Int = 0,
    val percent: Float = 0f,
    val ready: Boolean = false,
    val selection: TextSelection? = null,
    val config: ReaderConfig = ReaderConfig.DEFAULT,
    val hasBookmarkHere: Boolean = false,
    val highlightCount: Int = 0
)

data class TextSelection(val start: Int, val end: Int, val text: String)

/**
 * 阅读会话：负责 WebView 配置、章节加载、配置注入、进度保存、标注渲染与阅读统计。
 */
class ReaderSession(
    private val container: AppContainer,
    val book: BookEntity,
    private val scope: CoroutineScope
) {

    private val books = container.books
    private val notes = container.notes
    private val settings = container.settings
    private val stats = container.stats

    val resources = ReaderResourceProvider(container.context, container.textProvider)

    private val _state = MutableStateFlow(ReaderSessionState())
    val state: StateFlow<ReaderSessionState> = _state.asStateFlow()

    // ---- 自动阅读 ----
    // 运行状态只存在会话里，不落库：重新打开书籍一律从关闭开始。
    private val _autoReadNotice = MutableStateFlow<String?>(null)
    val autoReadNotice: StateFlow<String?> = _autoReadNotice.asStateFlow()

    private val autoRead = AutoReadController(
        scope = scope,
        host = object : AutoReadController.Host {
            override fun isPaged(): Boolean = _state.value.config.pageMode != PageMode.SCROLL

            override fun pageIntervalSeconds(): Int =
                AutoReadSpeed.clamp(_state.value.config.autoPageSeconds)

            override fun scrollSecondsPerScreen(): Int =
                AutoReadSpeed.clamp(_state.value.config.autoScrollSecondsPerScreen)

            override suspend fun turnPageForAuto(): AutoTurnResult = autoTurnPage()

            override suspend fun advanceScrollChapter(): AutoAdvanceResult = autoAdvanceScrollChapter()

            override fun startAutoScroll(secondsPerScreen: Int) {
                evaluate("if (window.MiradReader) { MiradReader.setAutoScroll(true, $secondsPerScreen); }")
            }

            override fun stopAutoScroll() {
                evaluate("if (window.MiradReader) { MiradReader.setAutoScroll(false, 0); }")
            }

            override fun notify(message: String) {
                _autoReadNotice.value = message
            }
        }
    )
    val autoReadState: StateFlow<AutoReadState> = autoRead.state

    var chapters: List<ChapterEntity> = emptyList()
        private set

    var webView: ReaderWebView? = null
        private set

    private var chapterCharCounts: List<Long> = emptyList()
    private var saveJob: Job? = null
    private var restoreOffset: Int = 0
    private var systemDark: Boolean = false

    private var sessionStartedAt: Long = 0L
    private var sessionStartAbsolute: Long = 0L

    /** 状态栏 / 手势条预留高度（dp），由界面按 WindowInsets 写入。 */
    private var insetTopDp = 0
    private var insetBottomDp = 0

    /** 上次因"滚到底"信号而跨章的时间，用于抑制原地换章与信号同时到达造成的跳两章。 */
    private var lastBottomAdvanceAt = 0L

    init {
        // 音量键翻页：阅读会话在（且仅在前台时）注册；release() 时清理。
        ReaderKeys.pager = { delta -> handleVolumeKey(delta) }
    }

    /**
     * 音量键：+1 下一页、-1 上一页。滚动模式下按屏滚动（滚到章末继续滚会接下一章）。
     * 页面还没就绪时返回 false，把按键交还系统（仍可调音量）。
     */
    private fun handleVolumeKey(delta: Int): Boolean {
        if (!_state.value.ready) return false
        if (_state.value.config.pageMode == PageMode.SCROLL) scrollStep(delta) else turnPage(delta)
        return true
    }

    private val bridge = ReaderJsBridge(
        onReady = { pages, textLength ->
            _state.update { it.copy(pages = pages.coerceAtLeast(1), textLength = textLength, ready = true) }
        },
        onState = state@{ page, pages, offset, textLength ->
            val current = _state.value
            // 跨章只有两个显式入口：侧滑/点按的 turnPage(+1)，以及 JS 的原地换章
            // （成功后回调 onChapterChanged，失败后回调 onRequestNextChapter）。
            // 这里不再从 onState 的页码推断"到底"——那条隐式路径曾与原地换章重复触发跳两章。
            val pageChanged = current.page != page
            _state.update {
                it.copy(
                    page = page,
                    pages = pages.coerceAtLeast(1),
                    offset = offset,
                    textLength = textLength,
                    ready = true
                )
            }
            recomputePercent()
            scheduleProgressSave()
            // 书签标记要查库；滚动时页号会频繁变化，只在页号真的变了时才查，否则滚动会顿。
            if (pageChanged || !current.ready) refreshBookmarkFlag()
        },
        onSelection = { start, end, text ->
            _state.update { it.copy(selection = TextSelection(start, end, text)) }
        },
        onChapterChanged = { index -> onJsChapterChanged(index) },
        onRequestNextChapter = { index ->
            // 只有 JS 侧原地换章失败（fetch/解析出错）才会走到这里：显式重载目标章。
            // 这是换章兜底，不是用户操作，不能把自动阅读暂停掉。
            if (index in chapters.indices) gotoChapterInternal(index, 0, pauseAuto = false)
        },
        onAutoScrollEnd = { index ->
            // 旧章/旧页面的迟到回调不能启动已经暂停或关闭的自动阅读：章号与运行状态都要匹配。
            if (index == _state.value.chapterIndex) autoRead.onScrollChapterEnd()
        }
    )

    // ------------------------------------------------------------------ 生命周期

    /**
     * 滚动模式下 JS 已在**同一文档内**换到另一章（原地换章），这里同步原生状态。
     *
     * 注意不能走 [gotoChapter]：那会重新 loadUrl，正是"接章卡一下"的来源。
     * 换章后页号/偏移由随后的 reportState 回填，这里只负责章号、进度与本章标注。
     */
    private fun onJsChapterChanged(index: Int) {
        if (index !in chapters.indices) return
        if (index == _state.value.chapterIndex) return
        _state.update { it.copy(chapterIndex = index, chapterTitle = chapters[index].title, page = 0, offset = 0) }
        recomputePercent()
        scheduleProgressSave()
        renderHighlights()
        scope.launch { refreshBookmarkFlagNow() }
    }

    suspend fun prepare(config: ReaderConfig, systemDark: Boolean) {
        this.systemDark = systemDark
        chapters = withContext(Dispatchers.IO) { books.getChapters(book.id) }
        chapterCharCounts = chapters.map { it.charCount.coerceAtLeast(1L) }
        resources.bind(book, chapters)
        updateConfig(config)
        val progress = withContext(Dispatchers.IO) { books.getProgress(book.id) }
        val chapterIndex = (progress?.chapterIndex ?: 0).coerceIn(0, (chapters.size - 1).coerceAtLeast(0))
        restoreOffset = progress?.charOffset ?: 0
        val highlightCount = withContext(Dispatchers.IO) {
            notes.highlightsForChapter(book.id, chapterIndex).size
        }
        _state.update {
            it.copy(
                chapterCount = chapters.size,
                chapterIndex = chapterIndex,
                chapterTitle = chapters.getOrNull(chapterIndex)?.title.orEmpty(),
                highlightCount = highlightCount
            )
        }
        startSession()
    }

    /**
     * 绑定 WebView：只允许读取拦截器提供的虚拟源，外部请求一律拒绝。
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun attach(view: ReaderWebView) {
        webView = view
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            loadsImagesAutomatically = true
            blockNetworkImage = false
            builtInZoomControls = false
            displayZoomControls = false
            setSupportZoom(false)
            textZoom = 100
            cacheMode = WebSettings.LOAD_NO_CACHE
            mediaPlaybackRequiresUserGesture = true
        }
        view.setBackgroundColor(Color.TRANSPARENT)
        view.isVerticalScrollBarEnabled = false
        view.isHorizontalScrollBarEnabled = false
        view.addJavascriptInterface(bridge, "MiradBridge")
        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val uri = request.url
                if (uri.host == ReaderResourceProvider.HOST) {
                    return resources.intercept(uri)
                }
                // 阻断一切外部网络请求（EPUB 中的外链图片/脚本也不会联网）
                return WebResourceResponse("text/plain", "UTF-8", java.io.ByteArrayInputStream(ByteArray(0)))
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean = true

            override fun onPageFinished(view: WebView, url: String) {
                // about:blank 等中间页面会先触发回调，必须按 URL 过滤；
                // 同时忽略已被新一次 loadUrl 取代的旧章节，避免旧页面的回调把新章标成就绪。
                val expected = ReaderResourceProvider.chapterUrl(book.id, _state.value.chapterIndex)
                val normalized = url.substringBefore('#').substringBefore('?').trimEnd('/')
                if (normalized == expected) {
                    onChapterRendered()
                }
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                android.util.Log.i(
                    "MiradWebView",
                    "${message.message()} @${message.sourceId()}:${message.lineNumber()}"
                )
                return true
            }
        }
        view.onTap = { x, y -> onTap?.invoke(x, y) }
        view.onTouchDown = {
            // 手指按下立即暂停自动阅读（不等待 JS touchstart）；同一次手势仍可正常拖动。
            if (autoRead.isRunning) {
                autoRead.pause()
                evaluate("if (window.MiradReader) { MiradReader.cancelProgrammaticScroll(); }")
            }
        }
        loadCurrentChapter(initial = true)
    }

    var onTap: ((x: Float, y: Float) -> Unit)? = null

    /**
     * 界面侧自动翻页入口。由阅读界面注入 `performTurn(1, auto=true)`，
     * 这样覆盖翻页等 Compose 动画与手动翻页走同一条路径；为空时退回会话内翻页。
     */
    var autoPageTurn: (() -> Boolean)? = null

    private var chapterRendered = false
    private var renderAttempts = 0

    /** 最近一次真正完成 init 的章节号；自动翻页等换章就绪判断以它为准。 */
    private val _renderedChapter = MutableStateFlow(-1)

    private fun loadCurrentChapter(initial: Boolean) {
        val index = _state.value.chapterIndex
        chapterRendered = false
        renderAttempts = 0
        _renderedChapter.value = -1
        _state.update { it.copy(ready = false) }
        // restoreOffset 已由调用方决定（进度恢复，或 gotoChapter 传入的显式偏移）。
        // 这里不能再覆盖成 0：否则"在章首点左键回上一章"会落到上一章开头，而不是它的末尾。
        webView?.loadUrl(ReaderResourceProvider.chapterUrl(book.id, index))
    }

    /** 等 reader.js 就绪后再初始化，避免在空白页上误调用。 */
    private fun onChapterRendered() {
        if (chapterRendered) return
        val view = webView ?: return
        view.evaluateJavascript("typeof MiradReader !== 'undefined'") { result ->
            if (result == "true") {
                if (chapterRendered) return@evaluateJavascript
                chapterRendered = true
                val configJson = jsConfig()
                val offset = restoreOffset.coerceAtLeast(0)
                evaluate("MiradReader.init($configJson); MiradReader.gotoOffset($offset, false); true;")
                _renderedChapter.value = _state.value.chapterIndex
                renderHighlights()
                scope.launch { refreshBookmarkFlagNow() }
            } else if (renderAttempts < 8) {
                renderAttempts++
                view.postDelayed({ onChapterRendered() }, 150)
            }
        }
    }

    // ------------------------------------------------------------------ 自动阅读

    /** 开始或继续自动阅读。只有正文已就绪时才允许启动，避免在加载页上创建空任务。 */
    fun startAutoRead() {
        if (!_state.value.ready) return
        autoRead.start()
    }

    /** 暂停自动阅读（可重复调用）：取消计时任务/JS 滚动循环，等待用户手动继续。 */
    fun pauseAutoRead() {
        autoRead.pause()
    }

    /** 关闭自动阅读：立即取消任务并停止 JS 自动下滑。 */
    fun stopAutoRead() {
        autoRead.stop()
    }

    fun consumeAutoReadNotice() {
        _autoReadNotice.value = null
    }

    /**
     * 分页自动翻页：先确认当前页显示完成，再翻页；换章时等新章真正就绪。
     * 返回后控制器才开始下一轮完整间隔的计时，因此不会把动画/加载时间算进阅读时间。
     */
    private suspend fun autoTurnPage(): AutoTurnResult {
        if (!_state.value.ready) return AutoTurnResult.LOAD_FAILED
        if (_state.value.config.pageMode == PageMode.SCROLL) return AutoTurnResult.LOAD_FAILED
        val before = _state.value
        if (before.page >= before.pages - 1 && before.chapterIndex >= chapters.size - 1) {
            return AutoTurnResult.BOOK_END
        }
        // 优先走界面注入的翻页入口，让覆盖翻页等现有动画也被复用；
        // 没有界面 hook 时退回会话内翻页（仍复用 reader.js 的翻页入口）。
        val turner = autoPageTurn
        val switched = if (turner != null) turner() else turnPageInternal(1)
        if (switched) {
            val targetChapter = _state.value.chapterIndex
            delay(pageSettleDelayMs())
            // 等新章真正完成 init，而不是等任意一次 onState（旧页面的迟到回报也可能把它置为 ready）。
            val ready = withTimeoutOrNull(AUTO_CHAPTER_TIMEOUT_MS) {
                _renderedChapter.first { it == targetChapter }
            }
            if (ready == null) return AutoTurnResult.LOAD_FAILED
        } else {
            // 单页翻页：等现有翻页动画结束再开始计时（无动画时不必等待）。
            val settle = pageSettleDelayMs()
            if (settle > 0) delay(settle)
        }
        return AutoTurnResult.TURNED
    }

    /**
     * 滚动自动接章：JS 原地换章是异步的，这里等新章号 + ready 同时成立才返回。
     * 等待期间 JS 自动下滑已经停止，所以不会累计位移，也不会重复触发换章。
     */
    private suspend fun autoAdvanceScrollChapter(): AutoAdvanceResult {
        if (!_state.value.ready) return AutoAdvanceResult.LOAD_FAILED
        val before = _state.value.chapterIndex
        if (before >= chapters.size - 1) return AutoAdvanceResult.BOOK_END
        val started = evaluateBoolean(
            "if (window.MiradReader && MiradReader.advanceChapterForAuto) { MiradReader.advanceChapterForAuto(); } else { false; }"
        )
        if (started != true) {
            return if (before >= chapters.size - 1) AutoAdvanceResult.BOOK_END else AutoAdvanceResult.LOAD_FAILED
        }
        val changed = withTimeoutOrNull(AUTO_CHAPTER_TIMEOUT_MS) {
            state.first { it.ready && it.chapterIndex != before }
        }
        return if (changed != null) AutoAdvanceResult.ADVANCED else AutoAdvanceResult.LOAD_FAILED
    }

    /** 翻页动画的保守时长：滑动动画最长 320ms、覆盖动画 260ms；无动画不等待。 */
    private fun pageSettleDelayMs(): Long = when (_state.value.config.pageAnim) {
        PageAnim.SLIDE -> 360L
        PageAnim.COVER -> 300L
        else -> 0L
    }

    /** 排版相关配置发生变化时暂停自动阅读；仅调速不打断。 */
    private fun layoutChanged(previous: ReaderConfig, next: ReaderConfig): Boolean =
        previous.pageMode != next.pageMode ||
            previous.fontSizeSp != next.fontSizeSp ||
            previous.lineHeightMultiplier != next.lineHeightMultiplier ||
            previous.paragraphSpacingEm != next.paragraphSpacingEm ||
            previous.letterSpacingEm != next.letterSpacingEm ||
            previous.firstLineIndentEm != next.firstLineIndentEm ||
            previous.horizontalPaddingDp != next.horizontalPaddingDp ||
            previous.maxColumnWidthEm != next.maxColumnWidthEm ||
            previous.textAlign != next.textAlign ||
            previous.boldText != next.boldText ||
            previous.fontFamily != next.fontFamily ||
            previous.customFontAsset != next.customFontAsset ||
            previous.customFontPath != next.customFontPath ||
            previous.epubLayoutMode != next.epubLayoutMode ||
            previous.allowImages != next.allowImages

    private suspend fun evaluateBoolean(script: String): Boolean? = suspendCancellableCoroutine { continuation ->
        val view = webView
        if (view == null) {
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }
        runCatching {
            view.evaluateJavascript(script) { value ->
                if (continuation.isActive) continuation.resume(value == "true")
            }
        }.onFailure {
            if (continuation.isActive) continuation.resume(null)
        }
    }

    // ------------------------------------------------------------------ 配置

    /** 更新配置：仅注入样式，不重载章节（保留当前位置）。 */
    fun updateConfig(config: ReaderConfig) {
        val palette = ThemePaletteResolver.resolve(config, systemDark)

        // 背景：内置素材优先，其次用户选的图片
        val bgImageUrl = if (config.backgroundType == BackgroundType.IMAGE) {
            val builtin = BuiltinBackgrounds.fromUri(config.backgroundImageUri)
            val filePath = config.backgroundImageUri?.takeIf { it.isNotBlank() && File(it).exists() }
            when {
                builtin != null -> {
                    resources.backgroundAssetName = builtin.assetName
                    resources.backgroundImagePath = null
                    ReaderResourceProvider.backgroundUrl(book.id)
                }
                filePath != null -> {
                    resources.backgroundImagePath = filePath
                    resources.backgroundAssetName = null
                    ReaderResourceProvider.backgroundUrl(book.id)
                }
                else -> {
                    resources.backgroundAssetName = null
                    resources.backgroundImagePath = null
                    null
                }
            }
        } else {
            resources.backgroundAssetName = null
            resources.backgroundImagePath = null
            null
        }

        // 字体：内置字体优先，其次用户导入的字体文件
        val fontUrl = if (config.fontFamily == FontFamilyKey.CUSTOM) {
            val asset = config.customFontAsset?.takeIf { it.isNotBlank() }
            val path = config.customFontPath?.takeIf { it.isNotBlank() && File(it).exists() }
            when {
                asset != null -> {
                    resources.fontAssetName = asset
                    resources.fontFilePath = null
                    ReaderResourceProvider.fontUrl(book.id)
                }
                path != null -> {
                    resources.fontFilePath = path
                    resources.fontAssetName = null
                    ReaderResourceProvider.fontUrl(book.id)
                }
                else -> {
                    resources.fontAssetName = null
                    resources.fontFilePath = null
                    null
                }
            }
        } else {
            resources.fontAssetName = null
            resources.fontFilePath = null
            null
        }

        val css = ReaderCss.themeCss(
            config = config,
            palette = palette,
            customFontUrl = fontUrl,
            backgroundImageUrl = bgImageUrl
        )
        resources.updateTheme(css, bgImageUrl, fontUrl)
        _state.update { it.copy(config = config) }
    }

    /** 配置变更后刷新 WebView 中的样式（不重载章节）。 */
    fun applyConfigToWebView(config: ReaderConfig, systemDark: Boolean) {
        val previous = _state.value.config
        this.systemDark = systemDark
        updateConfig(config)
        // 阅读模式/排版变化会重排正文并可能移动锚点：自动阅读先暂停，由用户手动继续。
        if (layoutChanged(previous, config)) pauseAutoRead()
        // 调速不重建循环；运行中的滚动只在这里同步一次新速度。
        autoRead.onSpeedChanged()
        val css = jsQuote(resources.currentThemeCss())
        // 样式、版心和安全区域同批更新；由 DOM 记录更新前的阅读锚点。
        // 不重建文本/高亮，避免每次拖动清掉搜索与朗读标记。
        evaluate("if (window.MiradReader) {MiradReader.setThemeCssOnly($css, ${jsConfig()});}")
    }
    private fun jsQuote(value: String): String = org.json.JSONObject.quote(value)

    private fun jsConfig(): String {
        val config = _state.value.config
        // WebView 里 1 CSS px == 1 dp，所以留白直接就是 paddingDp（不要再乘 density，
        // 否则列间距会跑到屏幕外，页边距看起来"没生效"）。
        val pad = config.horizontalPaddingDp.coerceAtLeast(0)
        val colMax = if (config.maxColumnWidthEm > 0f) {
            (config.fontSizeSp * config.maxColumnWidthEm).roundToInt()
        } else {
            0
        }
        val mode = if (config.pageMode == PageMode.SCROLL) "SCROLL" else "PAGED"
        val anim = when (config.pageAnim) {
            PageAnim.NONE -> "NONE"
            PageAnim.COVER -> "COVER"
            PageAnim.CURL -> "CURL"
            else -> "SLIDE"
        }
        return """{mode:"$mode",gap:${pad * 2},pad:$pad,colMax:$colMax,""" +
            """padTop:$insetTopDp,padBottom:$insetBottomDp,anim:"$anim",""" +
            """chapterCount:${chapters.size},windowedScroll:${config.windowedScroll}}"""
    }

    /**
     * 系统栏预留：正文避开状态栏与手势条，但背景层仍然满屏（沉浸式不破坏）。
     * 由界面按 WindowInsets 计算后传入。
     */
    fun updateInsets(topDp: Int, bottomDp: Int) {
        if (insetTopDp == topDp && insetBottomDp == bottomDp) return
        insetTopDp = topDp
        insetBottomDp = bottomDp
        // 屏幕旋转/系统栏安全区域变化：正文需重排，自动阅读暂停等待用户继续。
        pauseAutoRead()
        if (_state.value.ready) applyConfigToWebView(_state.value.config, systemDark)
    }

    // ------------------------------------------------------------------ 翻页与跳转

    /** 统一翻页：章内翻页，边界处跨章。返回是否发生了跨章切换。 */
    fun turnPage(delta: Int, pauseAuto: Boolean = true): Boolean {
        // 手动翻页（点按/音量键）是阅读位置变更：先暂停自动阅读。
        if (pauseAuto) pauseAutoRead()
        return turnPageInternal(delta)
    }

    private fun turnPageInternal(delta: Int): Boolean {
        val current = _state.value
        if (!current.ready) return false
        val target = current.page + delta
        if (target in 0 until current.pages) {
            evaluate(if (delta > 0) "MiradReader.nextPage();" else "MiradReader.prevPage();")
            return false
        }
        val nextChapter = current.chapterIndex + delta
        if (nextChapter in chapters.indices) {
            gotoChapterInternal(nextChapter, if (delta > 0) 0 else Int.MAX_VALUE, pauseAuto = false)
            return true
        }
        return false
    }

    fun gotoChapter(index: Int, offset: Int = 0) {
        gotoChapterInternal(index, offset, pauseAuto = true)
    }

    private fun gotoChapterInternal(index: Int, offset: Int = 0, pauseAuto: Boolean): Boolean {
        val chapter = chapters.getOrNull(index) ?: return false
        if (pauseAuto) pauseAutoRead()
        saveProgressNow()
        restoreOffset = if (offset == Int.MAX_VALUE) Int.MAX_VALUE else offset
        _state.update {
            it.copy(chapterIndex = index, chapterTitle = chapter.title, page = 0, pages = 1, offset = 0)
        }
        scope.launch {
            val count = withContext(Dispatchers.IO) { notes.highlightsForChapter(book.id, index).size }
            _state.update { it.copy(highlightCount = count) }
        }
        loadCurrentChapter(initial = false)
        return true
    }

    fun jumpToOffset(offset: Int, mark: Boolean = false) {
        pauseAutoRead()
        restoreOffset = offset
        evaluate("MiradReader.gotoOffset($offset, true);")
        if (mark) evaluate("MiradReader.markSearch($offset, ${offset + 1});")
    }

    fun jumpToPercent(percent: Float) {
        pauseAutoRead()
        val total = chapterCharCounts.sum()
        if (total <= 0L) return
        val locator = ProgressMath.fromPercent(chapterCharCounts, percent)
        if (locator.chapterIndex != _state.value.chapterIndex) {
            gotoChapter(locator.chapterIndex, locator.charOffset)
        } else {
            jumpToOffset(locator.charOffset)
        }
    }

    fun scrollBy(deltaY: Float) {
        evaluate("window.scrollBy(0, ${deltaY.toInt()});")
    }

    /**
     * 「翻一屏」：上下滚动模式下平滑滚动，左右翻页模式下翻页。
     * 点击左右两侧时按模式分流，避免上下滚动时还在整页跳。
     */
    fun scrollStep(direction: Int) {
        // 点按滚动/音量键滚动都是手动位置变更：自动阅读暂停，交给用户。
        pauseAutoRead()
        if (_state.value.config.pageMode != PageMode.SCROLL) {
            turnPageInternal(direction)
            return
        }
        evaluate("if (window.MiradReader) { MiradReader.scrollStep($direction); }")
    }

    // ------------------------------------------------------------------ 标注

    fun renderHighlights() {
        val chapterIndex = _state.value.chapterIndex
        scope.launch {
            val list = withContext(Dispatchers.IO) { notes.highlightsForChapter(book.id, chapterIndex) }
            val payload = list.joinToString(prefix = "[", postfix = "]") { highlight ->
                """{"start":${highlight.startOffset},"end":${highlight.endOffset},"note":${highlight.note != null}}"""
            }
            evaluate("MiradReader.renderHighlights($payload);")
            _state.update { it.copy(highlightCount = list.size) }
        }
    }

    fun addHighlight(
        start: Int,
        end: Int,
        text: String,
        color: Int = DEFAULT_HIGHLIGHT_COLOR,
        note: String? = null,
        onResult: (Boolean) -> Unit = {}
    ) {
        if (end <= start || text.isBlank()) {
            onResult(false)
            return
        }
        val chapterIndex = _state.value.chapterIndex
        scope.launch {
            withContext(Dispatchers.IO) {
                notes.addHighlight(
                    HighlightEntity(
                        bookId = book.id,
                        chapterIndex = chapterIndex,
                        startOffset = start,
                        endOffset = end,
                        text = text,
                        color = color,
                        note = note,
                        createdAt = System.currentTimeMillis()
                    )
                )
            }
            renderHighlights()
            _state.update { it.copy(selection = null) }
            onResult(true)
        }
    }

    suspend fun deleteHighlight(highlight: HighlightEntity) {
        withContext(Dispatchers.IO) { notes.deleteHighlight(highlight) }
        renderHighlights()
    }

    fun clearSearchMark() {
        evaluate("MiradReader.markSearch(0, 0);")
    }

    // ------------------------------------------------------------------ 书签

    fun toggleBookmark(onResult: (Boolean) -> Unit = {}) {
        val current = _state.value
        val offset = current.offset
        scope.launch {
            val existing = withContext(Dispatchers.IO) {
                notes.findBookmark(book.id, current.chapterIndex, offset)
            }
            if (existing != null) {
                withContext(Dispatchers.IO) { notes.deleteBookmark(existing) }
                _state.update { it.copy(hasBookmarkHere = false) }
                onResult(false)
            } else {
                val snippet = withContext(Dispatchers.IO) { snippetAt(current.chapterIndex, offset) }
                withContext(Dispatchers.IO) {
                    notes.addBookmark(
                        BookmarkEntity(
                            bookId = book.id,
                            chapterIndex = current.chapterIndex,
                            charOffset = offset,
                            chapterTitle = current.chapterTitle,
                            snippet = snippet,
                            createdAt = System.currentTimeMillis()
                        )
                    )
                }
                _state.update { it.copy(hasBookmarkHere = true) }
                onResult(true)
            }
        }
    }

    private fun refreshBookmarkFlag() {
        scope.launch { refreshBookmarkFlagNow() }
    }

    private suspend fun refreshBookmarkFlagNow() {
        val current = _state.value
        val existing = withContext(Dispatchers.IO) {
            notes.findBookmark(book.id, current.chapterIndex, current.offset)
        }
        _state.update { it.copy(hasBookmarkHere = existing != null) }
    }

    private fun snippetAt(chapterIndex: Int, offset: Int): String {
        val chapter = chapters.firstOrNull { it.index == chapterIndex } ?: return ""
        val text = container.textProvider.readText(book, chapter).replace('\n', ' ')
        if (text.isEmpty()) return ""
        val start = (offset - 20).coerceAtLeast(0)
        val end = (offset + 40).coerceAtMost(text.length)
        return text.substring(start, end).trim()
    }

    /** 当前章节纯文本（TTS 与搜索用）。 */
    suspend fun currentChapterText(): String {
        val chapter = chapters.firstOrNull { it.index == _state.value.chapterIndex } ?: return ""
        return withContext(Dispatchers.IO) { container.textProvider.readText(book, chapter) }
    }

    suspend fun chapterText(index: Int): String {
        val chapter = chapters.firstOrNull { it.index == index } ?: return ""
        return withContext(Dispatchers.IO) { container.textProvider.readText(book, chapter) }
    }

    // ------------------------------------------------------------------ 进度与统计

    private fun recomputePercent() {
        val current = _state.value
        val percent = ProgressMath.percent(chapterCharCounts, current.chapterIndex, current.offset)
        _state.update { it.copy(percent = percent) }
    }

    private fun scheduleProgressSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(600)
            saveProgressNow()
        }
    }

    fun saveProgressNow() {
        val current = _state.value
        if (chapters.isEmpty()) return
        // 用应用级作用域：退出阅读页时组合作用域会被取消，进度与统计不能丢
        container.appScope.launch {
            withContext(Dispatchers.IO) {
                books.saveProgress(
                    ProgressEntity(
                        bookId = book.id,
                        chapterIndex = current.chapterIndex,
                        charOffset = current.offset,
                        pageIndex = current.page,
                        pageCount = current.pages,
                        percent = current.percent,
                        updatedAt = System.currentTimeMillis()
                    )
                )
                books.touchLastRead(book.id)
            }
        }
    }

    private fun absoluteOffset(): Long {
        val current = _state.value
        var sum = 0L
        for (i in 0 until current.chapterIndex.coerceAtMost(chapterCharCounts.size)) {
            sum += chapterCharCounts[i]
        }
        return sum + current.offset
    }

    fun startSession() {
        sessionStartedAt = System.currentTimeMillis()
        sessionStartAbsolute = absoluteOffset()
    }

    /** 进入后台或退出阅读时结算统计。 */
    fun endSession() {
        val started = sessionStartedAt
        if (started <= 0L) return
        sessionStartedAt = 0L
        val chars = (absoluteOffset() - sessionStartAbsolute).coerceAtLeast(0L)
        val endedAt = System.currentTimeMillis()
        // 同样使用应用级作用域，保证退出时也写入
        container.appScope.launch { stats.record(book.id, started, endedAt, chars) }
    }

    fun release() {
        // 退出阅读页：先取消自动阅读的计时任务/JS 滚动，再保存进度、释放 WebView。
        autoRead.release()
        _autoReadNotice.value = null
        saveJob?.cancel()
        saveProgressNow()
        endSession()
        webView?.let { view ->
            view.removeJavascriptInterface("MiradBridge")
            view.stopLoading()
            view.destroy()
        }
        webView = null
        resources.release()
    }

    // ------------------------------------------------------------------ 工具

    private fun evaluate(script: String) {
        webView?.evaluateJavascript(script, null)
    }

    fun evaluatePublic(script: String) = evaluate(script)

    fun applySearchMark(start: Int, end: Int) {
        evaluate("MiradReader.markSearch($start, $end);")
    }

    /**
     * 读取 WebView 内的章节文本（reader.js 的文本模型）。
     * 朗读与定位都以它为准，避免与磁盘侧文本的换行差异导致偏移错位。
     */
    suspend fun jsText(): String? = suspendCancellableCoroutine { continuation ->
        val view = webView
        if (view == null) {
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }
        runCatching {
            view.evaluateJavascript("MiradReader.getText()") { value ->
                val decoded = runCatching {
                    org.json.JSONTokener(value).nextValue() as? String
                }.getOrNull()
                if (continuation.isActive) continuation.resume(decoded)
            }
        }.onFailure {
            if (continuation.isActive) continuation.resume(null)
        }
    }

    /** 就近查找文本并跳转（跨端偏移不一致时更准确）。 */
    fun findTextAndJump(needle: String, approxOffset: Int, mark: Boolean = true): Boolean {
        if (needle.isBlank()) return false
        val quoted = org.json.JSONObject.quote(needle)
        evaluate("MiradReader.findText($quoted, $approxOffset, $mark);")
        return true
    }

    fun clearSelection() {
        _state.update { it.copy(selection = null) }
        evaluate("window.getSelection().removeAllRanges();")
    }

    val isEpubLayoutUnified: Boolean
        get() = _state.value.config.epubLayoutMode == EpubLayoutMode.UNIFIED

    companion object {
        const val DEFAULT_HIGHLIGHT_COLOR = 0x8C_FFD666.toInt()
        /** 自动阅读等待换章就绪的上限；超时按加载失败处理并关闭自动阅读。 */
        private const val AUTO_CHAPTER_TIMEOUT_MS = 12_000L
    }
}
