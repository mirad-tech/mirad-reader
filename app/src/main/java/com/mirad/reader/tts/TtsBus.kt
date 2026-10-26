package com.mirad.reader.tts

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicReference

/** TTS 状态（服务与界面之间的桥）。 */
object TtsBus {

    const val ACTION_PLAY = "com.mirad.reader.tts.PLAY"
    const val ACTION_PAUSE = "com.mirad.reader.tts.PAUSE"
    const val ACTION_RESUME = "com.mirad.reader.tts.RESUME"
    const val ACTION_NEXT = "com.mirad.reader.tts.NEXT"
    const val ACTION_PREV = "com.mirad.reader.tts.PREV"
    const val ACTION_STOP = "com.mirad.reader.tts.STOP"

    const val EXTRA_TOKEN = "playlistToken"
    const val EXTRA_INDEX = "index"
    const val EXTRA_RATE = "rate"
    const val EXTRA_PITCH = "pitch"

    /**
     * 一次朗读请求的完整负载。
     *
     * 句子文本、起止偏移**不能**走 Intent extras：整章句子序列在 EPUB「一个 spine 就是整本书」
     * 的排版下很容易超过 Binder 事务上限（约 1MB），抛 TransactionTooLargeException 直接崩溃。
     * 这里改成进程内持有，Intent 只带 token 做身份校验。
     */
    class Playlist(
        val token: String,
        val bookId: Long,
        val chapterIndex: Int,
        val title: String,
        val texts: List<String>,
        val starts: IntArray,
        val ends: IntArray
    )

    /**
     * 当前待播放的负载。服务可能因进程重建而丢失它，此时按 token 校验失败、
     * 提示"朗读已失效"，而不是朗读一份空列表。
     */
    private val playlistReference = AtomicReference<Playlist?>(null)
    internal var playlist: Playlist?
        get() = playlistReference.get()
        set(value) { playlistReference.set(value) }

    internal sealed interface PlayRequest {
        data class Ready(val playlist: Playlist) : PlayRequest
        data object Superseded : PlayRequest
        data object Missing : PlayRequest
    }

    /** 旧 token 被替代时只忽略该请求，不能走负载丢失的停止路径。 */
    internal fun resolvePlayRequest(token: String?): PlayRequest {
        val current = playlist ?: return PlayRequest.Missing
        return if (current.token == token) PlayRequest.Ready(current) else PlayRequest.Superseded
    }

    /**
     * 按 token 取回播放负载。token 为 null / 不匹配都视为请求失效
     * （服务被重建、或界面已经开始了新的朗读请求）。
     */
    internal fun resolvePlaylist(token: String?): Playlist? =
        playlist?.takeIf { token != null && it.token == token }

    /** 仅测试与停止路径使用：释放可能很大的句子列表。 */
    internal fun clearPlaylist() {
        playlist = null
    }

    /** 旧服务销毁或旧请求启动失败时，不得释放后来发布的新负载。 */
    internal fun clearPlaylist(token: String?): Boolean {
        val current = playlistReference.get()
        if (current != null && current.token != token) return false
        return playlistReference.compareAndSet(current, null)
    }

    data class TtsState(
        val active: Boolean = false,
        val playing: Boolean = false,
        val bookId: Long = 0L,
        val chapterIndex: Int = -1,
        val index: Int = 0,
        val total: Int = 0,
        val sentenceStart: Int = 0,
        val sentenceEnd: Int = 0,
        /** 当前章节的最后一句朗读完成（供自动下一章使用）。 */
        val chapterFinished: Boolean = false,
        val error: String? = null
    )

    private val _state = MutableStateFlow(TtsState())
    val state: StateFlow<TtsState> = _state

    internal fun update(transform: (TtsState) -> TtsState) {
        _state.value = transform(_state.value)
    }

    internal fun reset() {
        _state.value = TtsState()
    }

    /** 通知「本章读完」一次，避免重复触发自动下一章。 */
    internal fun markChapterFinished() {
        _state.value = _state.value.copy(chapterFinished = true, playing = false)
    }

    internal fun consumeChapterFinished() {
        if (_state.value.chapterFinished) {
            _state.value = _state.value.copy(chapterFinished = false)
        }
    }
}
