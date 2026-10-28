package com.mirad.reader.reader

import com.mirad.reader.AppContainer
import com.mirad.reader.core.model.EpubLayoutMode
import com.mirad.reader.core.model.PageMode
import com.mirad.reader.data.db.entity.BookEntity
import com.mirad.reader.data.db.entity.BookFormat
import com.mirad.reader.data.db.entity.BookmarkEntity
import com.mirad.reader.data.db.entity.ChapterEntity
import com.mirad.reader.data.db.entity.HighlightEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.mirad.reader.data.db.entity.ProgressEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 标注和书签的会话操作，保留请求时快照及原有写入时序。 */
internal class ReaderAnnotations(
    private val container: AppContainer,
    private val book: BookEntity,
    private val scope: CoroutineScope,
    private val _state: MutableStateFlow<ReaderSessionState>,
    private val chapters: () -> List<ChapterEntity>,
    private val evaluate: (String) -> Unit
) {
    private val notes = container.notes

    fun renderHighlights(chapterIndex: Int = -1) {
        scope.launch {
            val explicitChapter = chapterIndex >= 0
            val activeCh = _state.value.chapterIndex
            val config = _state.value.config
            val allChapters = !explicitChapter && config.pageMode == PageMode.SCROLL &&
                !(book.format == BookFormat.EPUB && config.epubLayoutMode == EpubLayoutMode.ORIGINAL)
            val list = withContext(Dispatchers.IO) {
                if (allChapters) notes.allHighlights(book.id)
                else notes.highlightsForChapter(book.id, if (explicitChapter) chapterIndex else activeCh)
            }
            val payload = list.joinToString(prefix = "[", postfix = "]") { highlight ->
                val chapter = if (explicitChapter) chapterIndex else highlight.chapterIndex
                """{"start":${highlight.startOffset},"end":${highlight.endOffset},"note":${highlight.note != null},"chapter":$chapter}"""
            }
            val target = if (explicitChapter) ", $chapterIndex" else ""
            evaluate("MiradReader.renderHighlights($payload$target);")
            if (!explicitChapter) {
                _state.update { it.copy(highlightCount = list.count { h -> h.chapterIndex == activeCh }) }
            }
        }
    }

    fun addHighlight(
        start: Int,
        end: Int,
        text: String,
        color: Int = ReaderSession.DEFAULT_HIGHLIGHT_COLOR,
        note: String? = null,
        chapterIndex: Int? = null,
        onResult: (Boolean) -> Unit = {}
    ) {
        if (end <= start || text.isBlank()) {
            onResult(false)
            return
        }
        val targetChapter = if (chapterIndex != null && chapterIndex >= 0) chapterIndex else _state.value.chapterIndex
        scope.launch {
            withContext(Dispatchers.IO) {
                notes.addHighlight(
                    HighlightEntity(
                        bookId = book.id,
                        chapterIndex = targetChapter,
                        startOffset = start,
                        endOffset = end,
                        text = text,
                        color = color,
                        note = note,
                        createdAt = System.currentTimeMillis()
                    )
                )
            }
            renderHighlights(targetChapter)
            _state.update { it.copy(selection = null) }
            onResult(true)
        }
    }

    suspend fun deleteHighlight(highlight: HighlightEntity) {
        withContext(Dispatchers.IO) { notes.deleteHighlight(highlight) }
        renderHighlights(highlight.chapterIndex)
    }

    // ------------------------------------------------------------------ 书签

    fun toggleBookmark(onResult: (Boolean) -> Unit = {}) {
        val current = _state.value
        val offset = current.offset
        scope.launch {
            val existing = withContext(Dispatchers.IO) {
                notes.findBookmark(book.id, current.chapterIndex, offset)
            }
            if (existing != null) {
                withContext(Dispatchers.IO) { notes.deleteBookmark(existing) }
                _state.update { it.copy(hasBookmarkHere = false) }
                onResult(false)
            } else {
                val snippet = withContext(Dispatchers.IO) { snippetAt(current.chapterIndex, offset) }
                withContext(Dispatchers.IO) {
                    notes.addBookmark(
                        BookmarkEntity(
                            bookId = book.id,
                            chapterIndex = current.chapterIndex,
                            charOffset = offset,
                            chapterTitle = current.chapterTitle,
                            snippet = snippet,
                            createdAt = System.currentTimeMillis()
                        )
                    )
                }
                _state.update { it.copy(hasBookmarkHere = true) }
                onResult(true)
            }
        }
    }

    fun refreshBookmarkFlag() {
        scope.launch { refreshBookmarkFlagNow() }
    }

    suspend fun refreshBookmarkFlagNow() {
        val current = _state.value
        val existing = withContext(Dispatchers.IO) {
            notes.findBookmark(book.id, current.chapterIndex, current.offset)
        }
        _state.update { it.copy(hasBookmarkHere = existing != null) }
    }

    private fun snippetAt(chapterIndex: Int, offset: Int): String {
        val chapter = chapters().firstOrNull { it.index == chapterIndex } ?: return ""
        val text = container.textProvider.readText(book, chapter).replace('\n', ' ')
        if (text.isEmpty()) return ""
        val start = (offset - 20).coerceAtLeast(0)
        val end = (offset + 40).coerceAtMost(text.length)
        return text.substring(start, end).trim()
    }

}


/** 阅读进度与时长结算，应用级作用域负责退出后的落库。 */
internal class ReaderProgress(
    private val container: AppContainer,
    private val bookId: Long,
    private val scope: CoroutineScope,
    private val _state: StateFlow<ReaderSessionState>,
    private val chapterCounts: () -> List<Long>,
    private val canSave: () -> Boolean
) {
    private val books = container.books
    private val stats = container.stats
    private val progressWrites = Mutex()
    private var saveJob: Job? = null
    private var sessionStartedAt = 0L
    private var sessionStartAbsolute = 0L

    fun cancelPendingSave() { saveJob?.cancel() }

    fun scheduleProgressSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(600)
            saveProgressNow()
        }
    }

    fun saveProgressNow() {
        val current = _state.value
        // 半初始化的会话（prepare 失败后被丢弃）没有可信位置，写库会覆盖用户进度。
        if (!canSave()) return
        // 用应用级作用域：退出阅读页时组合作用域会被取消，进度与统计不能丢；
        // 互斥串行化保证快速连续跨章时旧快照不会后写覆盖新位置。
        container.appScope.launch {
            progressWrites.withLock {
                withContext(Dispatchers.IO) {
                    books.saveProgress(
                        ProgressEntity(
                            bookId = bookId,
                            chapterIndex = current.chapterIndex,
                            charOffset = current.offset,
                            pageIndex = current.page,
                            pageCount = current.pages,
                            percent = current.percent,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                    books.touchLastRead(bookId)
                }
            }
        }
    }

    private fun absoluteOffset(): Long {
        val chapterCharCounts = chapterCounts()
        val current = _state.value
        var sum = 0L
        for (i in 0 until current.chapterIndex.coerceAtMost(chapterCharCounts.size)) {
            sum += chapterCharCounts[i]
        }
        return sum + current.offset
    }

    fun startSession() {
        sessionStartedAt = System.currentTimeMillis()
        sessionStartAbsolute = absoluteOffset()
    }

    /** 进入后台或退出阅读时结算统计。 */
    fun endSession() {
        val started = sessionStartedAt
        if (started <= 0L) return
        sessionStartedAt = 0L
        val chars = (absoluteOffset() - sessionStartAbsolute).coerceAtLeast(0L)
        val endedAt = System.currentTimeMillis()
        // 同样使用应用级作用域，保证退出时也写入
        container.appScope.launch { stats.record(bookId, started, endedAt, chars) }
    }

}
