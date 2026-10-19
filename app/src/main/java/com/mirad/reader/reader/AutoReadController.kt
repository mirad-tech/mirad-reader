package com.mirad.reader.reader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 自动阅读的统一会话状态：关闭 / 运行 / 暂停。 */
enum class AutoReadState { OFF, RUNNING, PAUSED }

/** 分页自动翻页的结果；用来区分正常翻页、全书读完和章节加载失败。 */
enum class AutoTurnResult { TURNED, BOOK_END, LOAD_FAILED }

/** 滚动自动接章的结果。 */
enum class AutoAdvanceResult { ADVANCED, BOOK_END, LOAD_FAILED }

/**
 * 自动阅读控制器：**唯一**持有状态与计时任务。
 *
 * 设计约束：
 *  - 分页模式只有一个可取消的计时 Job：每次到点调用一次 [Host.turnPageForAuto]，
 *    返回后才重新开始完整间隔；暂停/继续不会补执行暂停期间的翻页。
 *  - 滚动模式没有原生逐帧任务，位移由 reader.js 里唯一的 requestAnimationFrame
 *    循环驱动；这里只负责「章末停留 → 接下一章」这一个计时 Job。
 *  - 所有 Job 都带代次校验：暂停、关闭、重新开始之后，旧 Job 的回调不能再改变状态，
 *    也不能让已经关闭的自动阅读重新启动。
 *  - 不依赖 WebView / Android，纯协程实现，可在 JVM 上用虚拟时间测试。
 */
class AutoReadController(
    private val scope: CoroutineScope,
    private val host: Host
) {

    /** 控制器向阅读会话请求的实际动作。所有方法都在主线程调用。 */
    interface Host {
        fun isPaged(): Boolean
        fun pageIntervalSeconds(): Int
        fun scrollSecondsPerScreen(): Int

        /**
         * 分页模式翻一页，并在**页面显示完成**后返回（单页动画结束，或换章后新章就绪）。
         * 实现方负责章节代次与超时；这里不重复等待。
         */
        suspend fun turnPageForAuto(): AutoTurnResult

        /**
         * 滚动模式接下一章。JS 原地换章是异步的，实现方要等到新章就绪或明确失败再返回。
         */
        suspend fun advanceScrollChapter(): AutoAdvanceResult

        fun startAutoScroll(secondsPerScreen: Int)
        fun stopAutoScroll()
        fun notify(message: String)
    }

    private val _state = MutableStateFlow(AutoReadState.OFF)
    val state: StateFlow<AutoReadState> = _state.asStateFlow()

    /** 当前唯一的计时任务；滚动位移的 rAF 循环在 JS 侧，同样只允许一份。 */
    private var job: Job? = null

    /** 会话代次：任何停止/暂停/重新开始都会递增，旧协程据此失效。 */
    private var generation = 0L

    /** 滚动模式已经到达章末、正在等待停留或换章。 */
    private var scrollAtChapterEnd = false

    val isRunning: Boolean
        get() = _state.value == AutoReadState.RUNNING

    /** 开始或从暂停继续。重复调用（已经运行时）不会创建第二个计时器。 */
    fun start() {
        if (_state.value == AutoReadState.RUNNING) return
        val gen = beginGeneration()
        _state.value = AutoReadState.RUNNING
        scrollAtChapterEnd = false
        if (host.isPaged()) {
            startPagedLoop(gen)
        } else {
            host.startAutoScroll(host.scrollSecondsPerScreen())
        }
    }

    /** 暂停：取消计时任务并停止 JS 自动滚动；重复调用安全。 */
    fun pause() {
        if (_state.value != AutoReadState.RUNNING) return
        beginGeneration()
        _state.value = AutoReadState.PAUSED
        scrollAtChapterEnd = false
        host.stopAutoScroll()
    }

    /** 关闭：取消任务、停止滚动并回到 OFF。 */
    fun stop() {
        if (_state.value == AutoReadState.OFF) return
        beginGeneration()
        _state.value = AutoReadState.OFF
        scrollAtChapterEnd = false
        host.stopAutoScroll()
    }

    /** 退出阅读页：取消所有任务，不再向会话发任何回调。 */
    fun release() {
        beginGeneration()
        _state.value = AutoReadState.OFF
        scrollAtChapterEnd = false
        host.stopAutoScroll()
    }

    /** 滚动速度变化：JS 只在速度变化时同步一次，不逐帧过桥。 */
    fun onSpeedChanged() {
        if (_state.value != AutoReadState.RUNNING) return
        if (host.isPaged() || scrollAtChapterEnd) return
        host.startAutoScroll(host.scrollSecondsPerScreen())
    }

    /**
     * JS 报告自动滚动已到本章末尾。
     *
     * 同一章只接受一次；停留一个「翻页间隔」后接下一章。短章同样走这条路径。
     */
    fun onScrollChapterEnd() {
        if (_state.value != AutoReadState.RUNNING) return
        if (host.isPaged() || scrollAtChapterEnd) return
        scrollAtChapterEnd = true
        val gen = generation
        cancelJob()
        job = scope.launch {
            try {
                // 最后一屏的阅读时间由「翻页间隔」给出；这段时间不累计滚动位移。
                delay(host.pageIntervalSeconds() * 1000L)
                if (generation != gen || _state.value != AutoReadState.RUNNING) return@launch
                when (host.advanceScrollChapter()) {
                    AutoAdvanceResult.ADVANCED -> {
                        if (generation != gen || _state.value != AutoReadState.RUNNING) return@launch
                        scrollAtChapterEnd = false
                        // 新章就绪后重新开始下滑；计时器本身不承载逐帧位移。
                        host.startAutoScroll(host.scrollSecondsPerScreen())
                    }
                    AutoAdvanceResult.BOOK_END -> stopWithNotice("已读完最后一章")
                    AutoAdvanceResult.LOAD_FAILED -> stopWithNotice("章节加载失败，自动阅读已停止")
                }
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (_: Throwable) {
                stopWithNotice("自动阅读已停止")
            }
        }
    }

    // ------------------------------------------------------------------ 内部

    private fun beginGeneration(): Long {
        generation++
        cancelJob()
        return generation
    }

    private fun cancelJob() {
        job?.cancel()
        job = null
    }

    private fun startPagedLoop(gen: Long) {
        cancelJob()
        job = scope.launch {
            try {
                while (isActive && generation == gen && _state.value == AutoReadState.RUNNING) {
                    if (!host.isPaged()) {
                        // 模式已经切换；状态收敛为暂停，等用户手动继续。
                        pause()
                        return@launch
                    }
                    // 当前页已经显示完成；先等满一个完整间隔，暂停期间不补翻页。
                    delay(host.pageIntervalSeconds() * 1000L)
                    if (generation != gen || _state.value != AutoReadState.RUNNING) return@launch
                    if (!host.isPaged()) {
                        // 模式切换后不再翻页；状态收敛为暂停，等用户手动继续。
                        pause()
                        return@launch
                    }
                    when (host.turnPageForAuto()) {
                        AutoTurnResult.TURNED -> Unit
                        AutoTurnResult.BOOK_END -> {
                            stopWithNotice("已读完最后一章")
                            return@launch
                        }
                        AutoTurnResult.LOAD_FAILED -> {
                            stopWithNotice("章节加载失败，自动阅读已停止")
                            return@launch
                        }
                    }
                }
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (_: Throwable) {
                stopWithNotice("自动阅读已停止")
            }
        }
    }

    /** 关闭并反馈。只在仍处于 RUNNING 时执行，避免暂停/关闭后的旧回调复写状态。 */
    private fun stopWithNotice(message: String) {
        if (_state.value != AutoReadState.RUNNING) return
        beginGeneration()
        _state.value = AutoReadState.OFF
        scrollAtChapterEnd = false
        host.stopAutoScroll()
        host.notify(message)
    }
}
