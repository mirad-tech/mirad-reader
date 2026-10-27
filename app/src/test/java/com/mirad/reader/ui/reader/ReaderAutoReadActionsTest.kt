package com.mirad.reader.ui.reader

import com.mirad.reader.reader.AutoAdvanceResult
import com.mirad.reader.reader.AutoReadController
import com.mirad.reader.reader.AutoReadState
import com.mirad.reader.reader.AutoTurnResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderAutoReadActionsTest {
    private class Host : AutoReadController.Host {
        var turns = 0
        var paged = true
        var scrollStarts = 0
        var scrollSpeed = 0
        override fun isPaged() = paged
        override fun pageIntervalSeconds() = 7
        override fun scrollSecondsPerScreen() = 18
        override suspend fun turnPageForAuto(): AutoTurnResult { turns++; return AutoTurnResult.TURNED }
        override suspend fun advanceScrollChapter() = AutoAdvanceResult.BOOK_END
        override fun startAutoScroll(secondsPerScreen: Int) { scrollStarts++; scrollSpeed = secondsPerScreen }
        override fun stopAutoScroll() = Unit
        override fun notify(message: String) = Unit
    }
    @Test fun quickStartPausesTtsAndHidesControlsBeforeStartingTheProductionController() = runTest {
        val host = Host()
        val controller = AutoReadController(this, host)
        val calls = mutableListOf<String>()
        ReaderAutoReadActions.start(true, { calls += "pause-tts" }, { calls += "hide" }, {
            calls += "start"
            controller.start()
        })
        assertEquals(listOf("pause-tts", "hide", "start"), calls)
        assertEquals(AutoReadState.RUNNING, controller.state.value)
        runCurrent()
        advanceTimeBy(7000); runCurrent()
        assertEquals(1, host.turns)
        controller.release()
    }
    @Test fun repeatedQuickStartDoesNotDuplicateTasksAndPausedReadingResumes() = runTest {
        val host = Host().apply { paged = false }
        val controller = AutoReadController(this, host)
        repeat(2) { ReaderAutoReadActions.start(false, { fail("TTS 未运行时不能暂停") }, {}, controller::start) }
        assertEquals(1, host.scrollStarts)
        assertEquals(18, host.scrollSpeed)
        controller.pause()
        ReaderAutoReadActions.start(false, {}, {}, controller::start)
        assertEquals(AutoReadState.RUNNING, controller.state.value)
        assertEquals(2, host.scrollStarts)
        controller.release()
    }
}
