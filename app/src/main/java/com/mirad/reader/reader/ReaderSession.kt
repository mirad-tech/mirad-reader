package com.mirad.reader.reader

import android.webkit.WebView
import com.mirad.reader.AppContainer
import com.mirad.reader.core.model.AutoReadSpeed
import com.mirad.reader.core.model.EpubLayoutMode
import com.mirad.reader.core.model.PageAnim
import com.mirad.reader.core.model.PageMode
import com.mirad.reader.core.model.ProgressMath
import com.mirad.reader.core.model.ReaderConfig
import com.mirad.reader.data.db.entity.BookEntity
import com.mirad.reader.data.db.entity.ChapterEntity
import com.mirad.reader.data.db.entity.HighlightEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
    private var restoreOffset: Int = 0
    private var systemDark: Boolean = false


    /**
     * [prepare] 是否成功走完。
     *
     * 未完成时会话没有可信的阅读位置：`chapters` 可能已加载，而 `_state.chapterIndex` 还是
     * 初始值 0。此时任何写库都会把用户真实进度覆盖成"第 0 章开头"，所以 [saveProgressNow]
     * 必须挡住这种半初始化状态（打开失败后界面会调用 [release] 丢弃会话）。
     */
    private var prepared = false

    /** 上次因"滚到底"信号而跨章的时间，用于抑制原地换章与信号同时到达造成的跳两章。 */
    private var lastBottomAdvanceAt = 0L


    private val style = ReaderSessionStyle(book, resources)
    private val scripts = ReaderScript { webView }
    private val annotations = ReaderAnnotations(container, book, scope, _state, { chapters }, ::evaluate)
    private val progressTracker = ReaderProgress(container, book.id, scope, _state, { chapterCharCounts }) {
        prepared && chapters.isNotEmpty()
    }

    private val volumeKeyHandler: (Int) -> Boolean = { delta -> handleVolumeKey(delta) }

    init {
        // 音量键翻页：阅读会话在（且仅在前台时）注册；release() 时清理。
        ReaderKeys.pager = volumeKeyHandler
    }

    /**
     * 音量键：+1 下一页、-1 上一页。滚动模式下按屏滚动（滚到章末继续滚会接下一章）。
     * 页面还没就绪或设置里关闭音量键翻页时返回 false，把按键交还系统（仍可调音量）。
     */
    private fun handleVolumeKey(delta: Int): Boolean {
        if (!shouldHandleVolumeKey(_state.value.ready, _state.value.config)) return false
        if (_state.value.config.pageMode == PageMode.SCROLL) scrollStep(delta) else turnPage(delta)
        return true
    }

    private val visualChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private val bridge = ReaderJsBridge(
        onReady = { pages, textLength ->
            _state.update { it.copy(pages = pages.coerceAtLeast(1), textLength = textLength, ready = true) }
        },
        onState = state@{ page, pages, offset, textLength ->
            val current = _state.value
            // 跨章只有两个显式入口：侧滑/点按的 turnPage(+1)，以及 JS 的原地换章
            // （成功后回调 onChapterChanged，失败后回调 onRequestNextChapter）。
            // 这里不再从 onState 的页码推断"到底"——那条隐式路径曾与原地换章重复触发跳两章。
            val safePages = pages.coerceAtLeast(1)
            val safePage = page.coerceIn(0, safePages - 1)
            val pageChanged = current.page != safePage
            _state.update {
                it.copy(
                    page = safePage,
                    pages = safePages,
                    offset = offset,
                    textLength = textLength,
                    ready = true
                )
            }
            recomputePercent()
            progressTracker.scheduleProgressSave()
            // 书签标记要查库；滚动时页号会频繁变化，只在页号真的变了时才查，否则滚动会顿。
            if (pageChanged || !current.ready) annotations.refreshBookmarkFlag()
        },
        onSelection = { start, end, text, chapterIndex ->
            if (start < 0 || end <= start) {
                _state.update { it.copy(selection = null) }
            } else {
                val targetCh = if (chapterIndex >= 0) chapterIndex else _state.value.chapterIndex
                _state.update { it.copy(selection = TextSelection(start, end, text, targetCh)) }
            }
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
        },
        onVisualChanged = {
            webView?.notifyBackdropContentChanged()
            visualChanges.tryEmit(Unit)
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
        progressTracker.scheduleProgressSave()
        renderHighlights()
        scope.launch { annotations.refreshBookmarkFlagNow() }
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
        prepared = true
    }

    /**
     * 绑定 WebView：只允许读取拦截器提供的虚拟源，外部请求一律拒绝。
     */
    fun attach(view: ReaderWebView) {
        webView = view
        view.bindReader(resources, bridge,
            { ReaderResourceProvider.chapterUrl(book.id, _state.value.chapterIndex) }, ::onChapterRendered)
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
    var autoPageTurn: (suspend () -> Boolean)? = null

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
                scope.launch { annotations.refreshBookmarkFlagNow() }
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
        val isBookEndInitial = scripts.boolean("window.MiradReader && MiradReader.isAtBookEnd ? MiradReader.isAtBookEnd() : false") ?: false
        if (before >= chapters.size - 1 || isBookEndInitial) return AutoAdvanceResult.BOOK_END

        val resultRaw = scripts.raw(
            "if (window.MiradReader && MiradReader.advanceChapterForAuto) { MiradReader.advanceChapterForAuto(); } else { 'FAILED'; }"
        )?.trim('"') ?: "FAILED"

        when (resultRaw) {
            "BOOK_END" -> return AutoAdvanceResult.BOOK_END
            "READY" -> return AutoAdvanceResult.ADVANCED
            "ADVANCING" -> {
                val changed = withTimeoutOrNull(AUTO_CHAPTER_TIMEOUT_MS) {
                    state.first { it.ready && it.chapterIndex != before }
                }
                if (changed != null) return AutoAdvanceResult.ADVANCED
                val isBookEndFinal = scripts.boolean("window.MiradReader && MiradReader.isAtBookEnd ? MiradReader.isAtBookEnd() : false") ?: false
                return if (_state.value.chapterIndex >= chapters.size - 1 || isBookEndFinal) AutoAdvanceResult.BOOK_END else AutoAdvanceResult.LOAD_FAILED
            }
            "LOADING" -> {
                // 窗口模式慢补章：等待章节追加进 DOM 触发 visualChanges
                val changed = withTimeoutOrNull(AUTO_CHAPTER_TIMEOUT_MS) {
                    visualChanges.first()
                }
                if (changed != null) {
                    val isEnd = scripts.boolean("window.MiradReader && MiradReader.isAtBookEnd ? MiradReader.isAtBookEnd() : false") ?: false
                    return if (isEnd) AutoAdvanceResult.BOOK_END else AutoAdvanceResult.ADVANCED
                }
                val isBookEndFinal = scripts.boolean("window.MiradReader && MiradReader.isAtBookEnd ? MiradReader.isAtBookEnd() : false") ?: false
                return if (_state.value.chapterIndex >= chapters.size - 1 || isBookEndFinal) AutoAdvanceResult.BOOK_END else AutoAdvanceResult.LOAD_FAILED
            }
            else -> {
                val isBookEndCheck = scripts.boolean("window.MiradReader && MiradReader.isAtBookEnd ? MiradReader.isAtBookEnd() : false") ?: false
                return if (before >= chapters.size - 1 || isBookEndCheck) AutoAdvanceResult.BOOK_END else AutoAdvanceResult.LOAD_FAILED
            }
        }
    }

    /** 翻页动画的保守时长：滑动动画最长 320ms、覆盖动画 260ms；无动画不等待。 */
    private fun pageSettleDelayMs(): Long = when (_state.value.config.pageAnim) {
        PageAnim.SLIDE -> 360L
        PageAnim.COVER -> 300L
        else -> 0L
    }

    /** 排版相关配置发生变化时暂停自动阅读；仅调速不打断。 */


    // ------------------------------------------------------------------ 配置

    /** 更新配置时保留当前位置，仅发布样式与会话配置。 */
    fun updateConfig(config: ReaderConfig) {
        style.update(config, systemDark)
        _state.update { it.copy(config = config) }
    }

    /** 配置变更后刷新 WebView 中的样式（不重载章节）。 */
    fun applyConfigToWebView(config: ReaderConfig, systemDark: Boolean) {
        val previous = _state.value.config
        this.systemDark = systemDark
        updateConfig(config)
        // 阅读模式/排版变化会重排正文并可能移动锚点：自动阅读先暂停，由用户手动继续。
        if (style.layoutChanged(previous, config)) pauseAutoRead()
        // 调速不重建循环；运行中的滚动只在这里同步一次新速度。
        autoRead.onSpeedChanged()
        val css = jsQuote(resources.currentThemeCss())
        // 样式、版心和安全区域同批更新；由 DOM 记录更新前的阅读锚点。
        // 不重建文本/高亮，避免每次拖动清掉搜索与朗读标记。
        evaluate("if (window.MiradReader) {MiradReader.setThemeCssOnly($css, ${jsConfig()});}")
    }
    private fun jsQuote(value: String): String = org.json.JSONObject.quote(value)

    private fun jsConfig(): String = style.jsConfig(_state.value.config, chapters.size)

    /** 安全区域变化仍暂停自动阅读，并保持样式和版心同批更新。 */
    fun updateInsets(topDp: Int, bottomDp: Int, leftDp: Int = 0, rightDp: Int = 0,
                     cutoutTopDp: Int = 0, cutoutBottomDp: Int = 0) {
        if (!style.updateInsets(topDp, bottomDp, leftDp, rightDp, cutoutTopDp, cutoutBottomDp)) return
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

    private val searchNavCoordinator = SearchNavigationCoordinator(
        scope = scope,
        onRequestGotoChapter = { chapterIndex, offset ->
            gotoChapterInternal(chapterIndex, offset, pauseAuto = true, fromSearchHit = true)
        },
        onExecuteJump = { needle, offset ->
            findTextAndJump(needle, offset)
        }
    )

    private fun cancelSearchNavigation() {
        searchNavCoordinator.cancel()
    }

    fun jumpToSearchHit(chapterIndex: Int, needle: String, charOffset: Int) {
        val isReady = _renderedChapter.value == chapterIndex && _state.value.ready
        searchNavCoordinator.jumpToSearchHit(
            targetChapter = chapterIndex,
            needle = needle,
            charOffset = charOffset,
            currentChapter = _state.value.chapterIndex,
            isRenderedAndReady = isReady,
            renderedChapterFlow = _renderedChapter,
            stateFlow = _state
        )
    }

    fun gotoChapter(index: Int, offset: Int = 0) {
        gotoChapterInternal(index, offset, pauseAuto = true)
    }

    private fun gotoChapterInternal(
        index: Int,
        offset: Int = 0,
        pauseAuto: Boolean,
        fromSearchHit: Boolean = false
    ): Boolean {
        if (!fromSearchHit) cancelSearchNavigation()
        val chapter = chapters.getOrNull(index) ?: return false
        if (pauseAuto) pauseAutoRead()
        saveProgressNow()
        restoreOffset = if (offset == Int.MAX_VALUE) Int.MAX_VALUE else offset
        _state.update {
            it.copy(chapterIndex = index, chapterTitle = chapter.title, page = 0, pages = 1, offset = 0, ready = false)
        }
        scope.launch {
            val count = withContext(Dispatchers.IO) { notes.highlightsForChapter(book.id, index).size }
            _state.update { it.copy(highlightCount = count) }
        }
        loadCurrentChapter(initial = false)
        return true
    }

    fun jumpToOffset(offset: Int, mark: Boolean = false) {
        cancelSearchNavigation()
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

    fun renderHighlights(chapterIndex: Int = -1) = annotations.renderHighlights(chapterIndex)

    fun addHighlight(
        start: Int,
        end: Int,
        text: String,
        color: Int = DEFAULT_HIGHLIGHT_COLOR,
        note: String? = null,
        chapterIndex: Int? = null,
        onResult: (Boolean) -> Unit = {}
    ) = annotations.addHighlight(start, end, text, color, note, chapterIndex, onResult)

    suspend fun deleteHighlight(highlight: HighlightEntity) = annotations.deleteHighlight(highlight)

    fun toggleBookmark(onResult: (Boolean) -> Unit = {}) = annotations.toggleBookmark(onResult)


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


    fun saveProgressNow() = progressTracker.saveProgressNow()
    fun startSession() = progressTracker.startSession()
    fun endSession() = progressTracker.endSession()

    fun release() {
        // 退出阅读页：先取消自动阅读的计时任务/JS 滚动，再保存进度、释放 WebView。
        autoRead.release()
        _autoReadNotice.value = null
        cancelSearchNavigation()
        progressTracker.cancelPendingSave()
        saveProgressNow()
        endSession()
        // 只清理本会话注册的 handler，避免音量键继续驱动已销毁的会话。
        if (ReaderKeys.pager === volumeKeyHandler) ReaderKeys.pager = null
        webView?.let { view ->
            view.removeJavascriptInterface("MiradBridge")
            view.stopLoading()
            view.destroy()
        }
        webView = null
        resources.release()
    }

    // ------------------------------------------------------------------ 工具

    private fun evaluate(script: String) = scripts.evaluate(script)

    fun evaluatePublic(script: String) = evaluate(script)

    /**
     * 读取 WebView 内的章节文本（reader.js 的文本模型）。
     * 朗读与定位都以它为准，避免与磁盘侧文本的换行差异导致偏移错位。
     */
    suspend fun jsText(): String? = scripts.text()

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

        /** 音量键翻页的接管条件：页面就绪且设置里没有关闭。 */
        internal fun shouldHandleVolumeKey(ready: Boolean, config: ReaderConfig): Boolean =
            ready && config.volumeKeyPage

        /** 滚动模式固定启用 3 章滑动窗口引擎（连续接章）；原配置开关已从模型中移除。 */
        const val WINDOWED_SCROLL = true
    }
}
