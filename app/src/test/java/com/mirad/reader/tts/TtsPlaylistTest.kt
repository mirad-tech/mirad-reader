package com.mirad.reader.tts

import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 播放负载不再经 Intent 传递后，服务与界面之间只剩 token 这一条身份约定。
 *
 * 这些用例守住两条不变量：
 * 1. token 不匹配（服务被重建、界面已开始新请求）时不允许播放旧负载；
 * 2. 停止/销毁路径必须释放句子列表，避免整章文本长期驻留。
 */
class TtsPlaylistTest {

    @Before
    fun setUp() {
        TtsBus.reset()
        TtsBus.clearPlaylist(TtsBus.playlist?.token)
    }

    @After
    fun tearDown() {
        TtsBus.reset()
        TtsBus.clearPlaylist(TtsBus.playlist?.token)
    }

    private fun playlist(token: String = "t1", bookId: Long = 7L, chapterIndex: Int = 3) = TtsBus.Playlist(
        token = token,
        bookId = bookId,
        chapterIndex = chapterIndex,
        title = "第一章",
        texts = listOf("第一句。", "第二句。", "第三句。"),
        starts = intArrayOf(0, 4, 8),
        ends = intArrayOf(4, 8, 12)
    )

    @Test
    fun resolvesOnlyMatchingToken() {
        val payload = playlist()
        TtsBus.playlist = payload

        assertSame(payload, TtsBus.resolvePlaylist("t1"))
        assertNull("另一个播放请求的 token 不得取到旧负载", TtsBus.resolvePlaylist("t2"))
        assertNull("缺失 token 必须视为失效", TtsBus.resolvePlaylist(null))
    }

    @Test
    fun clearedPlaylistIsNotResolvable() {
        TtsBus.playlist = playlist()
        TtsBus.clearPlaylist("t1")

        assertNull("停止后旧的播放请求必须失效", TtsBus.resolvePlaylist("t1"))
    }

    @Test
    fun newRequestInvalidatesPreviousOne() {
        TtsBus.playlist = playlist("old")
        TtsBus.playlist = playlist("new")

        assertNull(TtsBus.resolvePlaylist("old"))
        assertTrue(TtsBus.resolvePlaylist("new") != null)
    }

    @Test
    fun delayedOldPlayIsSupersededAndLatestRequestRemainsReady() {
        TtsBus.playlist = playlist("old")
        val latest = playlist("new")
        TtsBus.playlist = latest

        // 服务先收到旧 Intent 时必须走忽略分支，不能按进程丢失负载处理。
        assertEquals(TtsBus.PlayRequest.Superseded, TtsBus.resolvePlayRequest("old"))
        assertEquals(TtsBus.PlayRequest.Ready(latest), TtsBus.resolvePlayRequest("new"))
        assertNull(TtsBus.state.value.error)
    }

    @Test
    fun missingPayloadIsDistinctFromAnInvalidTokenWithANewerPayload() {
        assertEquals(TtsBus.PlayRequest.Missing, TtsBus.resolvePlayRequest("lost"))
        TtsBus.playlist = playlist("new")
        assertEquals(TtsBus.PlayRequest.Superseded, TtsBus.resolvePlayRequest(null))
        assertEquals(TtsBus.PlayRequest.Superseded, TtsBus.resolvePlayRequest("lost"))
    }

    @Test
    fun oldServiceCleanupCannotClearANewerQueuedPlaylist() {
        TtsBus.playlist = playlist("old")
        val ownedToken = (TtsBus.resolvePlayRequest("old") as TtsBus.PlayRequest.Ready).playlist.token
        val latest = playlist("new")
        TtsBus.playlist = latest

        assertFalse(TtsBus.clearPlaylist(ownedToken))
        assertSame(latest, TtsBus.resolvePlaylist("new"))
    }

    @Test
    fun serviceWithoutAnAcceptedPlaylistCannotClearANewRequest() {
        val latest = playlist("new")
        TtsBus.playlist = latest
        assertFalse(TtsBus.clearPlaylist(null))
        assertSame(latest, TtsBus.resolvePlaylist("new"))
    }

    @Test
    fun matchingOwnerReleasesItsPlaylistAndRepeatedCleanupIsSafe() {
        TtsBus.playlist = playlist("current")
        assertTrue(TtsBus.clearPlaylist("current"))
        assertNull(TtsBus.resolvePlaylist("current"))
        assertTrue(TtsBus.clearPlaylist("current"))
    }

    @Test
    fun onlyNormallyCompletedLastSentenceEmitsChapterFinishedOnce() {
        val payload = playlist()
        TtsBus.playlist = payload
        var finished: TtsBus.Playlist? = null
        val first = TtsBus.beginUtterance("t1", 0)
        assertEquals(TtsBus.Completion.NextSentence(1), TtsBus.completeUtterance("t1", first) { finished = it })
        assertNull(finished)

        val last = TtsBus.beginUtterance("t1", 2)
        assertEquals(TtsBus.Completion.ChapterFinished, TtsBus.completeUtterance("t1", last) { finished = it })
        assertSame(payload, finished)
        val accepted = checkNotNull(finished)
        assertEquals(7L, accepted.bookId)
        assertEquals(3, accepted.chapterIndex)
        finished = null
        assertNull(TtsBus.completeUtterance("t1", last) { finished = it })
        assertNull("同一句的重复回调不得再次发出完成事件", finished)
    }

    @Test
    fun oldSentenceAndOldPlaylistCallbacksCannotFinishCurrentBook() {
        TtsBus.playlist = playlist("old")
        val first = TtsBus.beginUtterance("old", 0)
        val later = TtsBus.beginUtterance("old", 2)
        var finished = false
        assertNull(TtsBus.completeUtterance("old", first) { finished = true })

        val current = playlist("new", bookId = 42L, chapterIndex = 9)
        TtsBus.playlist = current
        val currentLast = TtsBus.beginUtterance("new", 2)
        assertNull(TtsBus.completeUtterance("old", later) { finished = true })
        assertFalse("旧页面不能撤销新播放列表", TtsBus.blockPlayback("old"))
        assertFalse(finished)
        assertEquals(TtsBus.Completion.ChapterFinished, TtsBus.completeUtterance("new", currentLast) {
            assertEquals(42L, it.bookId)
            assertEquals(9, it.chapterIndex)
            finished = true
        })
        assertTrue(finished)
    }

    @Test
    fun cancelOnAnotherThreadRejectsLateDoneAndResumeGetsNewIdentity() {
        TtsBus.playlist = playlist()
        val oldId = TtsBus.beginUtterance("t1", 2)
        val cancelled = CountDownLatch(1)
        val worker = thread {
            TtsBus.blockPlayback("t1")
            cancelled.countDown()
        }
        assertTrue(cancelled.await(5, TimeUnit.SECONDS))
        worker.join()
        var finished = false
        assertNull(TtsBus.completeUtterance("t1", oldId) { finished = true })
        assertNull("暂停或停止命令送达前不能重新发句", TtsBus.beginUtterance("t1", 2))
        val revision = checkNotNull(TtsBus.prepareControl("t1", TtsBus.ACTION_RESUME))
        assertTrue(TtsBus.acceptControl("t1", revision, resume = true) == true)
        val resumed = TtsBus.beginUtterance("t1", 2)
        assertTrue(resumed != oldId)
        assertNull(TtsBus.completeUtterance("t1", oldId) { finished = true })
        assertFalse(finished)
        assertEquals(TtsBus.Completion.ChapterFinished, TtsBus.completeUtterance("t1", resumed) { finished = true })
        assertTrue(finished)
    }

    @Test
    fun failedOrStoppedUtteranceCannotLaterReportDone() {
        TtsBus.playlist = playlist()
        val id = TtsBus.beginUtterance("t1", 2)
        assertTrue(TtsBus.consumeUtterance("t1", id) { _, _ -> TtsBus.blockPlayback("t1") })
        var finished = false
        assertNull(TtsBus.completeUtterance("t1", id) { finished = true })
        assertFalse(finished)
    }

    @Test
    fun queuedOlderResumeAndNextCannotUndoLaterStop() {
        TtsBus.playlist = playlist()
        val currentId = TtsBus.beginUtterance("t1", 1)
        val next = checkNotNull(TtsBus.prepareControl("t1", TtsBus.ACTION_NEXT))
        val resume = checkNotNull(TtsBus.prepareControl("t1", TtsBus.ACTION_RESUME))
        val stop = checkNotNull(TtsBus.prepareControl("t1", TtsBus.ACTION_STOP))
        assertNull("停止请求发出后，旧通知的继续不能重新生成有效控制", TtsBus.prepareControl("t1", TtsBus.ACTION_RESUME))
        assertNull("停止请求发出后，旧通知的下一句不能重新生成有效控制", TtsBus.prepareControl("t1", TtsBus.ACTION_NEXT))
        assertNull(TtsBus.acceptControl("t1", next, resume = true))
        assertNull(TtsBus.acceptControl("t1", resume, resume = true))
        assertTrue(TtsBus.isPlaybackBlocked("t1"))
        assertNull(TtsBus.beginUtterance("t1", 2))
        var finished = false
        assertNull(TtsBus.completeUtterance("t1", currentId) { finished = true })
        assertFalse(finished)
        assertTrue(TtsBus.acceptControl("t1", stop, resume = false) == true)
        TtsBus.playlist = playlist("new")
        assertTrue("新播放列表应能接收自己的控制", TtsBus.prepareControl("new", TtsBus.ACTION_RESUME) != null)
    }

    @Test
    fun consecutiveNavigationRequestsRemainOrderedWithoutReopeningBetweenThem() {
        TtsBus.playlist = playlist()
        val currentId = TtsBus.beginUtterance("t1", 0)
        val first = checkNotNull(TtsBus.prepareControl("t1", TtsBus.ACTION_NEXT))
        val second = checkNotNull(TtsBus.prepareControl("t1", TtsBus.ACTION_NEXT))
        assertTrue(TtsBus.acceptControl("t1", first, resume = true) == true)
        assertTrue(TtsBus.isPlaybackBlocked("t1"))
        assertNull(TtsBus.completeUtterance("t1", currentId) { error("旧句不能结束章节") })
        assertNull(TtsBus.beginUtterance("t1", 1))
        assertTrue(TtsBus.acceptControl("t1", second, resume = true) == true)
        assertFalse(TtsBus.isPlaybackBlocked("t1"))
        assertTrue(TtsBus.beginUtterance("t1", 2) != null)
    }

    @Test
    fun outOfBoundsNextAndPreviousKeepCurrentUtterance() {
        TtsBus.playlist = playlist()
        val first = TtsBus.beginUtterance("t1", 0)
        assertNull(TtsBus.prepareControl("t1", TtsBus.ACTION_PREV))
        assertEquals(TtsBus.Completion.NextSentence(1), TtsBus.completeUtterance("t1", first) { error("不应结束章节") })

        val last = TtsBus.beginUtterance("t1", 2)
        assertNull(TtsBus.prepareControl("t1", TtsBus.ACTION_NEXT))
        var finished = false
        assertEquals(TtsBus.Completion.ChapterFinished, TtsBus.completeUtterance("t1", last) { finished = true })
        assertTrue(finished)
    }
}
