package com.mirad.reader.tts

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

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
    const val EXTRA_CONTROL_REVISION = "controlRevision"

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
    private val playlistLock = Any()
    private var currentPlaylist: Playlist? = null
    private var playbackBlocked = false
    private var currentUtterance: Utterance? = null
    private var controlRevision = 0L
    private var latestCancelRevision = 0L
    private var latestBlockingRevision = 0L
    private var acceptedControlRevision = 0L
    private var stopRequested = false

    private data class Utterance(val id: String, val token: String, val index: Int)

    internal var playlist: Playlist?
        get() = synchronized(playlistLock) { currentPlaylist }
        set(value) = synchronized(playlistLock) {
            currentPlaylist = value
            currentUtterance = null
            playbackBlocked = false
            controlRevision = 0L
            latestCancelRevision = 0L
            latestBlockingRevision = 0L
            acceptedControlRevision = 0L
            stopRequested = false
        }

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

    /** 旧服务销毁或旧请求启动失败时，不得释放后来发布的新负载。 */
    internal fun clearPlaylist(token: String?): Boolean = synchronized(playlistLock) {
        if (currentPlaylist != null && currentPlaylist?.token != token) return@synchronized false
        currentPlaylist = null
        currentUtterance = null
        playbackBlocked = false
        controlRevision = 0L
        latestCancelRevision = 0L
        latestBlockingRevision = 0L
        acceptedControlRevision = 0L
        stopRequested = false
        true
    }

    /** 新句、换章和换书都拥有独立标识；阻止取消后的延迟回调。 */
    internal fun beginUtterance(token: String?, index: Int): String? = synchronized(playlistLock) {
        val current = currentPlaylist ?: return@synchronized null
        if (current.token != token || playbackBlocked || index !in current.texts.indices) return@synchronized null
        UUID.randomUUID().toString().also { currentUtterance = Utterance(it, current.token, index) }
    }

    /**
     * 在同一把锁内核验并消费回调。完成动作只负责排队，不做数据库 I/O；
     * 这样不同线程发起的停止与完成接纳有明确先后，不能在撤销后补写完成状态。
     */
    internal fun consumeUtterance(
        token: String?,
        id: String?,
        onAccepted: (Playlist, Int) -> Unit
    ): Boolean = synchronized(playlistLock) {
        val current = currentPlaylist ?: return@synchronized false
        val utterance = currentUtterance ?: return@synchronized false
        if (playbackBlocked || token != current.token || utterance.token != current.token || utterance.id != id) {
            return@synchronized false
        }
        currentUtterance = null
        onAccepted(current, utterance.index)
        true
    }

    internal sealed interface Completion {
        data class NextSentence(val index: Int) : Completion
        data object ChapterFinished : Completion
    }

    /** 接纳正常完成时才前进；最后一句接纳时同步排队完成事件。 */
    internal fun completeUtterance(
        token: String?,
        id: String?,
        onChapterFinished: (Playlist) -> Unit
    ): Completion? {
        var completion: Completion? = null
        consumeUtterance(token, id) { playlist, index ->
            completion = if (index == playlist.texts.lastIndex) {
                onChapterFinished(playlist)
                Completion.ChapterFinished
            } else {
                Completion.NextSentence(index + 1)
            }
        }
        return completion
    }

    /** 立即阻止当前句的所有回调，令异步送达的暂停、停止或换句不能被旧回调抢先。 */
    internal fun blockPlayback(token: String?): Boolean = synchronized(playlistLock) {
        if (token == null || currentPlaylist?.token != token) return@synchronized false
        controlRevision++
        latestCancelRevision = controlRevision
        latestBlockingRevision = controlRevision
        playbackBlocked = true
        currentUtterance = null
        true
    }

    /** 给控制请求分配代次；播放中越界换句保持原句播放，不重新发句。 */
    internal fun prepareControl(token: String?, action: String): Long? = synchronized(playlistLock) {
        val current = currentPlaylist ?: return@synchronized null
        if (token == null || current.token != token) return@synchronized null
        if (stopRequested) return@synchronized null
        val utterance = currentUtterance
        if (!playbackBlocked && utterance != null &&
            ((action == ACTION_NEXT && utterance.index == current.texts.lastIndex) ||
                (action == ACTION_PREV && utterance.index == 0))
        ) return@synchronized null
        controlRevision++
        if (action == ACTION_PAUSE || action == ACTION_STOP) latestCancelRevision = controlRevision
        if (action == ACTION_STOP) stopRequested = true
        if (action != ACTION_RESUME) {
            latestBlockingRevision = controlRevision
            playbackBlocked = true
            currentUtterance = null
        }
        controlRevision
    }

    /** 逐个接纳换句；暂停或停止之前的旧指令不得重新开放播放。 */
    internal fun acceptControl(token: String?, revision: Long, resume: Boolean): Boolean? = synchronized(playlistLock) {
        if (token == null || currentPlaylist?.token != token || revision > controlRevision ||
            revision < latestCancelRevision || revision <= acceptedControlRevision
        ) return@synchronized null
        acceptedControlRevision = revision
        val wasBlocked = playbackBlocked
        if (resume && revision >= latestBlockingRevision) playbackBlocked = false
        wasBlocked
    }

    internal fun isPlaybackBlocked(token: String?): Boolean = synchronized(playlistLock) {
        token == null || currentPlaylist?.token != token || playbackBlocked
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
