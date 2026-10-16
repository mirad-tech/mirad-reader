package com.mirad.reader.tts

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** TTS 状态（服务与界面之间的桥）。 */
object TtsBus {

    const val ACTION_PLAY = "com.mirad.reader.tts.PLAY"
    const val ACTION_PAUSE = "com.mirad.reader.tts.PAUSE"
    const val ACTION_RESUME = "com.mirad.reader.tts.RESUME"
    const val ACTION_NEXT = "com.mirad.reader.tts.NEXT"
    const val ACTION_PREV = "com.mirad.reader.tts.PREV"
    const val ACTION_STOP = "com.mirad.reader.tts.STOP"

    const val EXTRA_BOOK_ID = "bookId"
    const val EXTRA_CHAPTER_INDEX = "chapterIndex"
    const val EXTRA_TITLE = "title"
    const val EXTRA_TEXTS = "texts"
    const val EXTRA_STARTS = "starts"
    const val EXTRA_ENDS = "ends"
    const val EXTRA_INDEX = "index"
    const val EXTRA_RATE = "rate"
    const val EXTRA_PITCH = "pitch"

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
