package com.mirad.reader.tts

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * 界面侧的朗读控制门面：把播放列表发给 [TtsService]，并暴露朗读状态。
 */
class TtsController(private val context: Context) {

    val state: StateFlow<TtsBus.TtsState> = TtsBus.state

    /** 当前是否正在发声播放（暂停态为 false）。 */
    val isSpeaking: Boolean get() = TtsBus.state.value.playing
    val isPlaying: Boolean get() = TtsBus.state.value.playing
    /** 是否存在活跃的朗读任务（包含暂停态）。 */
    val isActive: Boolean get() = TtsBus.state.value.active

    fun play(
        bookId: Long,
        chapterIndex: Int,
        title: String,
        sentences: List<SentenceSplitter.Sentence>,
        startIndex: Int,
        rate: Float,
        pitch: Float
    ) {
        if (sentences.isEmpty()) return
        // 大负载留在进程内，Intent 只传 token 与索引：见 TtsBus.Playlist 的注释。
        val playlist = TtsBus.Playlist(
            token = UUID.randomUUID().toString(),
            bookId = bookId,
            chapterIndex = chapterIndex,
            title = title,
            texts = sentences.map { it.text },
            starts = IntArray(sentences.size) { sentences[it].start },
            ends = IntArray(sentences.size) { sentences[it].end }
        )
        TtsBus.playlist = playlist
        val intent = Intent(context, TtsService::class.java)
            .setAction(TtsBus.ACTION_PLAY)
            .putExtra(TtsBus.EXTRA_TOKEN, playlist.token)
            .putExtra(TtsBus.EXTRA_INDEX, startIndex.coerceIn(0, sentences.size - 1))
            .putExtra(TtsBus.EXTRA_RATE, rate)
            .putExtra(TtsBus.EXTRA_PITCH, pitch)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        }.onFailure {
            // 后台启动限制等情况下服务可能根本没起来：不能留下"界面以为在朗读"的假状态。
            if (TtsBus.clearPlaylist(playlist.token)) {
                TtsBus.update { state ->
                    state.copy(active = false, playing = false, error = "无法启动朗读服务")
                }
            }
        }
    }

    fun pause() = send(TtsBus.ACTION_PAUSE)

    fun resume() = send(TtsBus.ACTION_RESUME)

    fun next() = send(TtsBus.ACTION_NEXT)

    fun previous() = send(TtsBus.ACTION_PREV)

    fun stop() = send(TtsBus.ACTION_STOP)

    fun consumeChapterFinished() = TtsBus.consumeChapterFinished()

    private fun send(action: String) {
        runCatching { context.startService(TtsService.intent(context, action)) }
    }
}
