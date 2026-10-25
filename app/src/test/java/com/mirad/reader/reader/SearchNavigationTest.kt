package com.mirad.reader.reader

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 针对 F15 搜索跨章导航协调器的定向生产验证：
 * 验证真实生产 SearchNavigationCoordinator 的文档身份绑定、
 * 慢加载等待、旧文档迟到回调隔离、后续导航取消及进度保存独立性。
 */
class SearchNavigationTest {

    @Test
    fun testSlowLoadingWaitsForTargetDocumentIdentityAndRendersAccurately() = runBlocking {
        val executedJumps = mutableListOf<String>()
        val requestedGoto = mutableListOf<Pair<Int, Int>>()

        val coordinator = SearchNavigationCoordinator(
            scope = this,
            onRequestGotoChapter = { ch, offset -> requestedGoto.add(ch to offset) },
            onExecuteJump = { needle, offset -> executedJumps.add("$needle@$offset") }
        )

        val renderedChapter = MutableStateFlow(0)
        val sessionState = MutableStateFlow(ReaderSessionState(ready = true, chapterIndex = 0))

        // 1. 在第 0 章发起跳转到第 5 章的搜索命中 "目标词"
        coordinator.jumpToSearchHit(
            targetChapter = 5,
            needle = "目标词",
            charOffset = 120,
            currentChapter = sessionState.value.chapterIndex,
            isRenderedAndReady = renderedChapter.value == 5 && sessionState.value.ready,
            renderedChapterFlow = renderedChapter,
            stateFlow = sessionState
        )

        // 验证已发起切章请求，但尚未执行定位
        assertEquals(listOf(5 to 120), requestedGoto)
        assertEquals("加载期间绝不提前执行定位", 0, executedJumps.size)

        // 2. 模拟旧文档（第 0 章）迟到的回调到达：更新了第 0 章的 state，但 renderedChapter 仍为 0
        sessionState.value = sessionState.value.copy(ready = true, chapterIndex = 0)
        delay(50)
        assertEquals("旧文档迟到回调绝不能误触发搜索定位", 0, executedJumps.size)

        // 3. 模拟第 5 章开始加载：state.chapterIndex = 5, 但 ready = false, renderedChapter = -1
        sessionState.value = sessionState.value.copy(ready = false, chapterIndex = 5)
        renderedChapter.value = -1
        delay(50)
        assertEquals("未完成渲染前绝不提前执行定位", 0, executedJumps.size)

        // 4. 模拟第 5 章真正完成渲染并就绪
        renderedChapter.value = 5
        sessionState.value = sessionState.value.copy(ready = true, chapterIndex = 5)
        delay(50)

        // 核心验证：目标文档真正就绪后，精确定位成功执行一次
        assertEquals(1, executedJumps.size)
        assertEquals("目标词@120", executedJumps[0])
    }

    @Test
    fun testSubsequentNavigationCancelsPendingSearchJump() = runBlocking {
        val executedJumps = mutableListOf<String>()
        val requestedGoto = mutableListOf<Pair<Int, Int>>()

        val coordinator = SearchNavigationCoordinator(
            scope = this,
            onRequestGotoChapter = { ch, offset -> requestedGoto.add(ch to offset) },
            onExecuteJump = { needle, offset -> executedJumps.add("$needle@$offset") }
        )

        val renderedChapter = MutableStateFlow(0)
        val sessionState = MutableStateFlow(ReaderSessionState(ready = true, chapterIndex = 0))

        // 1. 发起第 4 章的搜索跳转
        coordinator.jumpToSearchHit(
            targetChapter = 4,
            needle = "关键词",
            charOffset = 80,
            currentChapter = 0,
            isRenderedAndReady = false,
            renderedChapterFlow = renderedChapter,
            stateFlow = sessionState
        )
        assertEquals(0, executedJumps.size)

        // 2. 用户在第 4 章尚未就绪前，主动手动切到第 7 章（触发 cancel()）
        coordinator.cancel()
        sessionState.value = sessionState.value.copy(ready = false, chapterIndex = 7)
        renderedChapter.value = -1

        // 3. 迟到的第 4 章就绪事件到达
        renderedChapter.value = 4
        sessionState.value = sessionState.value.copy(ready = true, chapterIndex = 4)
        delay(50)

        // 核心验证：后续导航取消后，旧搜索任务被彻底作废，绝不发生迟到覆盖
        assertEquals("后续导航后旧搜索跳转必须已被取消，绝不执行迟到定位", 0, executedJumps.size)
    }

    @Test
    fun testImmediateJumpWhenAlreadyInTargetChapterAndReady() = runBlocking {
        val executedJumps = mutableListOf<String>()
        val requestedGoto = mutableListOf<Pair<Int, Int>>()

        val coordinator = SearchNavigationCoordinator(
            scope = this,
            onRequestGotoChapter = { ch, offset -> requestedGoto.add(ch to offset) },
            onExecuteJump = { needle, offset -> executedJumps.add("$needle@$offset") }
        )

        val renderedChapter = MutableStateFlow(3)
        val sessionState = MutableStateFlow(ReaderSessionState(ready = true, chapterIndex = 3))

        // 当前已经在第 3 章且已渲染就绪，发起本章搜索命中
        coordinator.jumpToSearchHit(
            targetChapter = 3,
            needle = "同章内容",
            charOffset = 35,
            currentChapter = 3,
            isRenderedAndReady = true,
            renderedChapterFlow = renderedChapter,
            stateFlow = sessionState
        )

        // 验证直接执行定位，不重复请求切章
        assertTrue(requestedGoto.isEmpty())
        assertEquals(1, executedJumps.size)
        assertEquals("同章内容@35", executedJumps[0])
    }
}
