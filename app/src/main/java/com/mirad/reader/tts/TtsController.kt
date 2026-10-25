package com.mirad.reader.tts

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.StateFlow

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
        val intent = Intent(context, TtsService::class.java)
            .setAction(TtsBus.ACTION_PLAY)
            .putExtra(TtsBus.EXTRA_BOOK_ID, bookId)
            .putExtra(TtsBus.EXTRA_CHAPTER_INDEX, chapterIndex)
            .putExtra(TtsBus.EXTRA_TITLE, title)
            .putExtra(TtsBus.EXTRA_INDEX, startIndex.coerceIn(0, sentences.size - 1))
            .putExtra(TtsBus.EXTRA_RATE, rate)
            .putExtra(TtsBus.EXTRA_PITCH, pitch)
            .putStringArrayListExtra(TtsBus.EXTRA_TEXTS, ArrayList(sentences.map { it.text }))
            .putIntegerArrayListExtra(TtsBus.EXTRA_STARTS, ArrayList(sentences.map { it.start }))
            .putIntegerArrayListExtra(TtsBus.EXTRA_ENDS, ArrayList(sentences.map { it.end }))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(context, intent)
        } else {
            context.startService(intent)
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
