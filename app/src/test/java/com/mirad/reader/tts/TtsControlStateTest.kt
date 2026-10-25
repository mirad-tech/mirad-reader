package com.mirad.reader.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 针对 F06、F07、F12、F13 的朗读状态、生产动作与控制协调回归测试。
 *
 * 直接调用生产单元 [ReaderTtsActions] 与 [TtsBus]，不自写本地伪实现。
 */
class TtsControlStateTest {

    @Before
    fun setUp() {
        TtsBus.reset()
    }

    @Test
    fun testF07ToolbarClickDispatchesCorrectly() {
        // 1. 播放中：点击必须触发暂停，不得打开控制面板
        var paused = false
        var openedPanel = false
        ReaderTtsActions.onToolbarClick(
            isPlaying = true,
            onPause = { paused = true },
            onOpenPanel = { openedPanel = true }
        )
        assertTrue("播放中点击必须暂停", paused)
        assertFalse("播放中点击不得打开面板", openedPanel)

        // 2. 暂停中 (active=true, playing=false)：点击必须打开控制面板恢复/设置，绝不能重复触发暂停
        paused = false
        openedPanel = false
        ReaderTtsActions.onToolbarClick(
            isPlaying = false,
            onPause = { paused = true },
            onOpenPanel = { openedPanel = true }
        )
        assertFalse("未在播放时不得触发暂停", paused)
        assertTrue("未在播放时必须打开面板", openedPanel)
    }

    @Test
    fun testF13StartPlanResolution() {
        // 1. 继续播放已有任务（未从开头且已有 active）
        val resumePlan = ReaderTtsActions.resolveStartPlan(
            resume = true,
            active = true,
            fromBeginning = false
        )
        assertEquals(ReaderTtsActions.StartPlan.ResumeExisting, resumePlan)

        // 2. 从本章开头播放（无论是否有 active，必须从第 0 句重新开始）
        val fromBeginningPlan = ReaderTtsActions.resolveStartPlan(
            resume = true,
            active = true,
            fromBeginning = true
        )
        assertEquals(ReaderTtsActions.StartPlan.StartFromBeginning, fromBeginningPlan)

        // 3. 从当前阅读位置开始（首次启动或非继续模式）
        val currentPlan = ReaderTtsActions.resolveStartPlan(
            resume = false,
            active = false,
            fromBeginning = false
        )
        assertEquals(ReaderTtsActions.StartPlan.StartFromCurrent, currentPlan)
    }

    @Test
    fun testF13ComputeStartIndex() {
        val sentences = listOf(
            SentenceSplitter.Sentence("第一句。", 0, 4),
            SentenceSplitter.Sentence("第二句。", 5, 9),
            SentenceSplitter.Sentence("第三句。", 10, 14)
        )

        // 从本章开头：强制为 0
        assertEquals(0, ReaderTtsActions.computeStartIndex(sentences, currentOffset = 7, fromBeginning = true))

        // 当前位置偏移 2（第一句中）：返回 0
        assertEquals(0, ReaderTtsActions.computeStartIndex(sentences, currentOffset = 2, fromBeginning = false))

        // 当前位置偏移 7（第二句中）：返回 1
        assertEquals(1, ReaderTtsActions.computeStartIndex(sentences, currentOffset = 7, fromBeginning = false))

        // 当前位置偏移 12（第三句中）：返回 2
        assertEquals(2, ReaderTtsActions.computeStartIndex(sentences, currentOffset = 12, fromBeginning = false))

        // 空列表容错
        assertEquals(0, ReaderTtsActions.computeStartIndex(emptyList(), currentOffset = 10, fromBeginning = false))
    }

    @Test
    fun testF06InFlightRequestValidation() {
        val bookA = 100L
        val bookB = 200L

        // 请求发起时为 bookA, chapter 0
        val targetBook = bookA
        val targetChapter = 0

        // 异步等待返回后，仍处于原书原章 -> 有效
        assertTrue(
            "当前处于原书原章时请求有效",
            ReaderTtsActions.isRequestValid(targetBook, bookA, targetChapter, 0)
        )

        // 异步等待期间用户跳到了 chapter 1 -> 无效作废
        assertFalse(
            "章节发生改变时请求作废",
            ReaderTtsActions.isRequestValid(targetBook, bookA, targetChapter, 1)
        )

        // 异步等待期间用户换了一本书 -> 无效作废
        assertFalse(
            "换书后旧请求作废",
            ReaderTtsActions.isRequestValid(targetBook, bookB, targetChapter, 0)
        )
    }

    @Test
    fun testF06ChapterAutoAdvanceGuard() {
        val currentBook = 100L
        val currentChapter = 2

        // 正常完结：书号匹配、章号匹配、配置开启 -> 允许自动接章
        assertTrue(
            "匹配的完结事件允许接章",
            ReaderTtsActions.canAutoAdvanceChapter(
                finishedBookId = currentBook,
                currentBookId = currentBook,
                finishedChapter = currentChapter,
                currentChapter = currentChapter,
                autoNextChapterEnabled = true
            )
        )

        // 配置关闭 -> 禁止接章
        assertFalse(
            "配置禁用时不接章",
            ReaderTtsActions.canAutoAdvanceChapter(
                finishedBookId = currentBook,
                currentBookId = currentBook,
                finishedChapter = currentChapter,
                currentChapter = currentChapter,
                autoNextChapterEnabled = false
            )
        )

        // 用户在朗读末尾前已手动跳到 chapter 3，迟到的 chapter 2 完成事件到达 -> 禁止误接章
        assertFalse(
            "迟到的旧章完成事件绝不能接章",
            ReaderTtsActions.canAutoAdvanceChapter(
                finishedBookId = currentBook,
                currentBookId = currentBook,
                finishedChapter = 2,
                currentChapter = 3,
                autoNextChapterEnabled = true
            )
        )

        // 换书后收到旧书的完成事件 -> 禁止接章
        assertFalse(
            "旧书的完成事件绝不能接章",
            ReaderTtsActions.canAutoAdvanceChapter(
                finishedBookId = 99L,
                currentBookId = currentBook,
                finishedChapter = currentChapter,
                currentChapter = currentChapter,
                autoNextChapterEnabled = true
            )
        )
    }

    @Test
    fun testF12ErrorConvergenceDoesNotLeaveFakePlayingState() {
        // 初始处于活跃播放态
        TtsBus.update {
            it.copy(
                active = true,
                playing = true,
                bookId = 1L,
                chapterIndex = 0,
                index = 1,
                total = 5
            )
        }
        assertTrue(TtsBus.state.value.playing)

        // 模拟引擎拒绝播放 / speak 返回错误
        TtsBus.update { it.copy(playing = false, error = "语音引擎拒绝播放") }
        assertFalse("引擎拒绝播放后 playing 必须置 false", TtsBus.state.value.playing)
        assertEquals("语音引擎拒绝播放", TtsBus.state.value.error)
        assertEquals("失败后不应改变当前句子序号", 1, TtsBus.state.value.index)

        // 模拟恢复后遇到 onError 回调
        TtsBus.update { it.copy(playing = true, error = null) }
        assertTrue(TtsBus.state.value.playing)

        TtsBus.update { it.copy(playing = false, error = "朗读出错（-1）") }
        assertFalse("onError 回调后 playing 必须收敛为 false", TtsBus.state.value.playing)
        assertEquals("朗读出错（-1）", TtsBus.state.value.error)
        assertFalse("出错时不应错误标记 chapterFinished", TtsBus.state.value.chapterFinished)
    }
}
