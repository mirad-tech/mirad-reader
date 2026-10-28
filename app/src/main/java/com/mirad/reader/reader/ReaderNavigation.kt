package com.mirad.reader.reader

import com.mirad.reader.core.model.ReaderConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 阅读器状态。 */
data class ReaderSessionState(
    val chapterIndex: Int = 0,
    val chapterCount: Int = 0,
    val chapterTitle: String = "",
    val page: Int = 0,
    val pages: Int = 1,
    val offset: Int = 0,
    val textLength: Int = 0,
    val percent: Float = 0f,
    val ready: Boolean = false,
    val selection: TextSelection? = null,
    val config: ReaderConfig = ReaderConfig.DEFAULT,
    val hasBookmarkHere: Boolean = false,
    val highlightCount: Int = 0
)

data class TextSelection(
    val start: Int,
    val end: Int,
    val text: String,
    val chapterIndex: Int = -1
)

/** 搜索命中跨章导航协调器：绑定真实文档身份与渲染就绪状态，隔离迟到回调与后续导航取消。 */
class SearchNavigationCoordinator(
    private val scope: CoroutineScope,
    private val onRequestGotoChapter: (chapterIndex: Int, charOffset: Int) -> Unit,
    private val onExecuteJump: (needle: String, charOffset: Int) -> Unit
) {
    private var searchNavJob: Job? = null
    var navEpoch: Long = 0L
        private set

    fun cancel() {
        searchNavJob?.cancel()
        searchNavJob = null
        navEpoch++
    }

    fun jumpToSearchHit(
        targetChapter: Int,
        needle: String,
        charOffset: Int,
        currentChapter: Int,
        isRenderedAndReady: Boolean,
        renderedChapterFlow: StateFlow<Int>,
        stateFlow: StateFlow<ReaderSessionState>
    ) {
        searchNavJob?.cancel()
        val epoch = ++navEpoch
        if (targetChapter == currentChapter && isRenderedAndReady) {
            onExecuteJump(needle, charOffset)
        } else {
            onRequestGotoChapter(targetChapter, charOffset)
            searchNavJob = scope.launch {
                try {
                    renderedChapterFlow.first { it == targetChapter }
                    stateFlow.first { it.ready && it.chapterIndex == targetChapter }
                    if (epoch == navEpoch && needle.isNotBlank()) {
                        onExecuteJump(needle, charOffset)
                    }
                } catch (_: CancellationException) {
                }
            }
        }
    }
}
