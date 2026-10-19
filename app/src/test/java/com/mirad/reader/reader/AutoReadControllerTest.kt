package com.mirad.reader.reader

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动阅读的状态与计时约束：始终只有一个计时任务，暂停/关闭后旧回调不得复活。
 * 滚动位移本身在 reader.js 的 rAF 循环里，这里只验证原生侧的「章末停留 → 接章」计时。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AutoReadControllerTest {

    private class FakeHost(
        var paged: Boolean = true,
        var pageSeconds: Int = 20,
        var scrollSeconds: Int = 30
    ) : AutoReadController.Host {
        var turns = 0
        var advances = 0
        var scrollStarts = 0
        var scrollStops = 0
        var lastScrollSeconds = 0
        var turnResult = AutoTurnResult.TURNED
        var advanceResult = AutoAdvanceResult.ADVANCED
        var advanceDelayMs = 0L
        val notices = mutableListOf<String>()

        override fun isPaged(): Boolean = paged
        override fun pageIntervalSeconds(): Int = pageSeconds
        override fun scrollSecondsPerScreen(): Int = scrollSeconds

        override suspend fun turnPageForAuto(): AutoTurnResult {
            turns++
            return turnResult
        }

        override suspend fun advanceScrollChapter(): AutoAdvanceResult {
            advances++
            if (advanceDelayMs > 0) delay(advanceDelayMs)
            return advanceResult
        }

        override fun startAutoScroll(secondsPerScreen: Int) {
            scrollStarts++
            lastScrollSeconds = secondsPerScreen
        }

        override fun stopAutoScroll() {
            scrollStops++
        }

        override fun notify(message: String) {
            notices += message
        }
    }

    @Test
    fun pagedUsesFullIntervalBeforeEveryTurn() = runTest {
        val host = FakeHost(pageSeconds = 20)
        val controller = AutoReadController(backgroundScope, host)
        controller.start()

        advanceTimeBy(19_999)
        runCurrent()
        assertEquals(0, host.turns)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, host.turns)

        advanceTimeBy(19_999)
        runCurrent()
        assertEquals(1, host.turns)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, host.turns)
    }

    @Test
    fun repeatedStartDoesNotCreateSecondTimer() = runTest {
        val host = FakeHost(pageSeconds = 20)
        val controller = AutoReadController(backgroundScope, host)
        controller.start()
        controller.start()
        controller.start()

        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(1, host.turns)

        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(2, host.turns)
    }

    @Test
    fun pauseStopsTimerAndResumeWaitsFullInterval() = runTest {
        val host = FakeHost(pageSeconds = 20)
        val controller = AutoReadController(backgroundScope, host)
        controller.start()
        advanceTimeBy(10_000)
        runCurrent()

        controller.pause()
        assertEquals(AutoReadState.PAUSED, controller.state.value)
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(0, host.turns)

        controller.start()
        assertEquals(AutoReadState.RUNNING, controller.state.value)
        advanceTimeBy(19_999)
        runCurrent()
        assertEquals(0, host.turns)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, host.turns)
    }

    @Test
    fun stopCancelsTimerImmediately() = runTest {
        val host = FakeHost(pageSeconds = 20)
        val controller = AutoReadController(backgroundScope, host)
        controller.start()
        advanceTimeBy(10_000)
        controller.stop()
        assertEquals(AutoReadState.OFF, controller.state.value)
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(0, host.turns)
        assertTrue(host.scrollStops >= 1)
    }

    @Test
    fun repeatedStopAndPauseAreIdempotent() = runTest {
        val host = FakeHost(pageSeconds = 20)
        val controller = AutoReadController(backgroundScope, host)
        controller.pause()
        controller.stop()
        controller.release()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(0, host.turns)
        assertEquals(AutoReadState.OFF, controller.state.value)
    }

    @Test
    fun bookEndTurnsAutoReadOffWithNotice() = runTest {
        val host = FakeHost(pageSeconds = 20)
        host.turnResult = AutoTurnResult.BOOK_END
        val controller = AutoReadController(backgroundScope, host)
        controller.start()
        advanceTimeBy(20_000)
        runCurrent()

        assertEquals(AutoReadState.OFF, controller.state.value)
        assertEquals(1, host.turns)
        assertEquals("已读完最后一章", host.notices.last())
    }

    @Test
    fun chapterLoadFailureTurnsAutoReadOffWithNotice() = runTest {
        val host = FakeHost(pageSeconds = 20)
        host.turnResult = AutoTurnResult.LOAD_FAILED
        val controller = AutoReadController(backgroundScope, host)
        controller.start()
        advanceTimeBy(20_000)
        runCurrent()

        assertEquals(AutoReadState.OFF, controller.state.value)
        assertEquals("章节加载失败，自动阅读已停止", host.notices.last())
    }

    @Test
    fun modeChangeDuringIntervalPausesInsteadOfTurningPages() = runTest {
        val host = FakeHost(paged = true, pageSeconds = 20)
        val controller = AutoReadController(backgroundScope, host)
        controller.start()
        host.paged = false

        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(0, host.turns)
        assertEquals(AutoReadState.PAUSED, controller.state.value)
    }

    @Test
    fun scrollStartsLoopOnceAndSyncsSpeedOnlyOnChange() = runTest {
        val host = FakeHost(paged = false, scrollSeconds = 30)
        val controller = AutoReadController(backgroundScope, host)
        controller.start()
        assertEquals(1, host.scrollStarts)
        assertEquals(30, host.lastScrollSeconds)

        controller.start()
        assertEquals(1, host.scrollStarts)

        host.scrollSeconds = 60
        controller.onSpeedChanged()
        assertEquals(2, host.scrollStarts)
        assertEquals(60, host.lastScrollSeconds)
    }

    @Test
    fun scrollChapterEndDwellsOneIntervalThenAdvancesAndRestarts() = runTest {
        val host = FakeHost(paged = false, pageSeconds = 20)
        val controller = AutoReadController(backgroundScope, host)
        controller.start()
        controller.onScrollChapterEnd()

        advanceTimeBy(19_999)
        runCurrent()
        assertEquals(0, host.advances)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, host.advances)
        assertEquals(2, host.scrollStarts)
        assertEquals(AutoReadState.RUNNING, controller.state.value)
    }

    @Test
    fun duplicateScrollEndDuringDwellIsIgnored() = runTest {
        val host = FakeHost(paged = false, pageSeconds = 20)
        val controller = AutoReadController(backgroundScope, host)
        controller.start()
        controller.onScrollChapterEnd()
        advanceTimeBy(10_000)
        controller.onScrollChapterEnd()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(1, host.advances)
    }

    @Test
    fun pauseDuringDwellCancelsAdvance() = runTest {
        val host = FakeHost(paged = false, pageSeconds = 20)
        val controller = AutoReadController(backgroundScope, host)
        controller.start()
        controller.onScrollChapterEnd()
        advanceTimeBy(10_000)
        controller.pause()
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(0, host.advances)
        assertEquals(AutoReadState.PAUSED, controller.state.value)
    }

    @Test
    fun pauseDuringChapterWaitDoesNotRestartScrollLoop() = runTest {
        val host = FakeHost(paged = false, pageSeconds = 20)
        host.advanceDelayMs = 5_000
        val controller = AutoReadController(backgroundScope, host)
        controller.start()
        controller.onScrollChapterEnd()
        advanceTimeBy(20_000)
        runCurrent() // 已进入 advanceScrollChapter 的等待
        assertEquals(1, host.advances)
        controller.pause()
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(1, host.advances)
        assertEquals(1, host.scrollStarts)
        assertEquals(AutoReadState.PAUSED, controller.state.value)
    }

    @Test
    fun scrollBookEndTurnsAutoReadOff() = runTest {
        val host = FakeHost(paged = false, pageSeconds = 20)
        host.advanceResult = AutoAdvanceResult.BOOK_END
        val controller = AutoReadController(backgroundScope, host)
        controller.start()
        controller.onScrollChapterEnd()
        advanceTimeBy(20_000)
        runCurrent()

        assertEquals(AutoReadState.OFF, controller.state.value)
        assertEquals("已读完最后一章", host.notices.last())
    }

    @Test
    fun scrollLoadFailureTurnsAutoReadOff() = runTest {
        val host = FakeHost(paged = false, pageSeconds = 20)
        host.advanceResult = AutoAdvanceResult.LOAD_FAILED
        val controller = AutoReadController(backgroundScope, host)
        controller.start()
        controller.onScrollChapterEnd()
        advanceTimeBy(20_000)
        runCurrent()

        assertEquals(AutoReadState.OFF, controller.state.value)
        assertEquals("章节加载失败，自动阅读已停止", host.notices.last())
    }

    @Test
    fun releaseCancelsEverythingAndIgnoresLateEndCallback() = runTest {
        val host = FakeHost(paged = false, pageSeconds = 20)
        val controller = AutoReadController(backgroundScope, host)
        controller.start()
        controller.release()
        controller.onScrollChapterEnd()
        advanceTimeBy(120_000)
        runCurrent()

        assertEquals(AutoReadState.OFF, controller.state.value)
        assertEquals(0, host.advances)
        assertTrue(host.scrollStops >= 1)
    }

    @Test
    fun pausedControllerIgnoresStaleScrollEnd() = runTest {
        val host = FakeHost(paged = false, pageSeconds = 20)
        val controller = AutoReadController(backgroundScope, host)
        controller.start()
        controller.pause()
        controller.onScrollChapterEnd()
        advanceTimeBy(120_000)
        runCurrent()

        assertEquals(0, host.advances)
        assertEquals(AutoReadState.PAUSED, controller.state.value)
    }
}
