package com.mirad.reader.tts

import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

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
        TtsBus.clearPlaylist()
    }

    @After
    fun tearDown() {
        TtsBus.reset()
        TtsBus.clearPlaylist()
    }

    private fun playlist(token: String = "t1") = TtsBus.Playlist(
        token = token,
        bookId = 7L,
        chapterIndex = 3,
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
        TtsBus.clearPlaylist()

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
}
