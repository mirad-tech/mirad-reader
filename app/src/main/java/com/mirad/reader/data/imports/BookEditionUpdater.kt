package com.mirad.reader.data.imports

import androidx.room.withTransaction
import com.mirad.reader.core.model.ProgressMath
import com.mirad.reader.data.db.AppDatabase
import com.mirad.reader.data.db.entity.BookEntity
import com.mirad.reader.data.db.entity.BookmarkEntity
import com.mirad.reader.data.db.entity.ChapterEntity
import com.mirad.reader.data.db.entity.HighlightEntity
import com.mirad.reader.data.db.entity.ProgressEntity
import com.mirad.reader.data.repo.BookRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

internal class EditionChanged : Exception("旧书或阅读记录已变化，请重新核对")

internal class BookEditionUpdater(private val db: AppDatabase, private val books: BookRepository) {
    internal data class Records(
        val book: BookEntity,
        val chapters: List<ChapterEntity>,
        val progress: ProgressEntity?,
        val bookmarks: List<BookmarkEntity>,
        val highlights: List<HighlightEntity>
    )

    internal data class Update(
        val old: Records,
        val preview: ImportUpdatePreview,
        val progress: ProgressEntity?,
        val bookmarks: List<BookmarkEntity>,
        val highlights: List<HighlightEntity>
    )

    private suspend fun read(id: Long): Records? {
        val book = books.getBook(id) ?: return null
        return Records(
            book, books.getChapters(id), books.getProgress(id),
            db.bookmarkDao().getAllForBook(id).sortedBy { it.id },
            db.highlightDao().getAllForBook(id).sortedBy { it.id }
        )
    }

    suspend fun inspect(candidate: EditionCandidate, incoming: BookEntity, chapters: List<ChapterEntity>): Update {
        val old = db.withTransaction { read(candidate.book.id) } ?: throw EditionChanged()
        if (!sameContentVersion(old.book, candidate.book) ||
            old.book.contentPath?.let { File(it).lastModified() } != candidate.normalizedModified) throw EditionChanged()
        return map(old, incoming, chapters, candidate.relation)
    }

    /** 文件已完全准备好，单个事务只发布新文件引用和对应的索引、定位。 */
    suspend fun publish(update: Update, incoming: BookEntity, chapters: List<ChapterEntity>) = db.withTransaction {
        check(update.preview.resume != ResumeImpact.UNAVAILABLE)
        val id = update.old.book.id
        val current = read(id) ?: throw EditionChanged()
        if (!sameContentVersion(current.book, update.old.book) || current.chapters != update.old.chapters ||
            current.progress != update.old.progress || current.bookmarks != update.old.bookmarks ||
            current.highlights != update.old.highlights) throw EditionChanged()
        books.saveChapters(id, chapters.map { it.copy(bookId = id) })
        books.updateBook(current.book.copy(
            filePath = incoming.filePath,
            contentPath = incoming.contentPath,
            sourceUri = incoming.sourceUri,
            fileSize = incoming.fileSize,
            encoding = incoming.encoding,
            chapterRuleId = incoming.chapterRuleId,
            chapterRegex = incoming.chapterRegex,
            totalChapters = incoming.totalChapters,
            totalChars = incoming.totalChars,
            tocJson = incoming.tocJson,
            language = incoming.language,
            publisher = incoming.publisher,
            description = incoming.description
        ))
        update.progress?.let { books.saveProgress(it) }
        for (bookmark in update.bookmarks) db.bookmarkDao().update(bookmark)
        for (highlight in update.highlights) {
            if (highlight.id == 0L) db.highlightDao().insert(highlight) else db.highlightDao().update(highlight)
        }
    }

    private fun sameContentVersion(a: BookEntity, b: BookEntity): Boolean =
        a.filePath == b.filePath && a.contentPath == b.contentPath && a.format == b.format &&
            editionKey(a.author.orEmpty()) == editionKey(b.author.orEmpty()) &&
            a.encoding == b.encoding && a.totalChapters == b.totalChapters && a.totalChars == b.totalChars &&
            a.chapterRuleId == b.chapterRuleId && a.chapterRegex == b.chapterRegex

    companion object {
        internal suspend fun map(
            old: Records, incoming: BookEntity, chapters: List<ChapterEntity>, relation: EditionRelation
        ): Update {
            val anchors = EditionAnchors(old.book, old.chapters, incoming, chapters, relation)
            val progress = old.progress
            val exact = progress?.let { anchors.point(it.chapterIndex, it.charOffset) }
            val position = exact ?: progress?.let { anchors.chapterStart(it.chapterIndex) }
            val resume = when {
                progress == null -> ResumeImpact.UNREAD
                exact != null -> ResumeImpact.EXACT
                position != null -> ResumeImpact.CHAPTER_START
                else -> ResumeImpact.UNAVAILABLE
            }
            val newProgress = if (progress != null && position != null) {
                progress.copy(
                    chapterIndex = position.chapter, charOffset = position.offset, pageIndex = 0, pageCount = 0,
                    percent = ProgressMath.percent(chapters.map { it.charCount.coerceAtLeast(1L) }, position.chapter, position.offset)
                )
            } else null
            var lost = 0
            val bookmarks = old.bookmarks.sortedBy { it.chapterIndex }.map { bookmark ->
                currentCoroutineContext().ensureActive()
                if (bookmark.chapterIndex < 0) return@map bookmark
                val mapped = anchors.point(bookmark.chapterIndex, bookmark.charOffset)
                if (mapped == null) {
                    lost++
                    bookmark.copy(chapterIndex = -1)
                } else bookmark.copy(
                    chapterIndex = mapped.chapter, charOffset = mapped.offset, chapterTitle = chapters[mapped.chapter].title
                )
            }
            val highlights = old.highlights.flatMap { highlight ->
                currentCoroutineContext().ensureActive()
                if (highlight.chapterIndex < 0) return@flatMap listOf(highlight)
                val ranges = anchors.ranges(highlight.chapterIndex, highlight.startOffset, highlight.endOffset, highlight.text)
                if (ranges == null) {
                    lost++
                    listOf(highlight.copy(chapterIndex = -1))
                } else ranges.mapIndexed { index, range ->
                    highlight.copy(
                        id = if (index == 0) highlight.id else 0L, chapterIndex = range.chapter,
                        startOffset = range.start, endOffset = range.end, text = range.text
                    )
                }
            }
            return Update(
                old, ImportUpdatePreview(old.book.id, resume, position?.let { chapters[it.chapter].title }, lost),
                newProgress, bookmarks, highlights
            )
        }
    }
}
