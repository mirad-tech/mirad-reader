package com.mirad.reader.data.imports

import androidx.room.withTransaction
import com.mirad.reader.core.model.ProgressMath
import com.mirad.reader.data.db.AppDatabase
import com.mirad.reader.data.db.dao.BookmarkDao
import com.mirad.reader.data.db.dao.HighlightDao
import com.mirad.reader.data.db.entity.BookEntity
import com.mirad.reader.data.db.entity.BookFormat
import com.mirad.reader.data.db.entity.BookmarkEntity
import com.mirad.reader.data.db.entity.ChapterEntity
import com.mirad.reader.data.db.entity.HighlightEntity
import com.mirad.reader.data.db.entity.ProgressEntity
import com.mirad.reader.data.io.BookContentAccess
import com.mirad.reader.data.repo.BookRepository
import com.mirad.reader.data.repo.ChapterTextProvider
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

internal class EditionChanged : Exception("旧书或阅读记录已变化，请重新核对")

internal class BookEditionUpdater internal constructor(
    private val books: BookRepository,
    private val bookmarkDao: BookmarkDao,
    private val highlightDao: HighlightDao,
    private val db: AppDatabase? = null,
    private val contentAccess: BookContentAccess = BookContentAccess()
) {
    constructor(
        db: AppDatabase,
        books: BookRepository,
        contentAccess: BookContentAccess = BookContentAccess()
    ) : this(
        books = books,
        bookmarkDao = db.bookmarkDao(),
        highlightDao = db.highlightDao(),
        db = db,
        contentAccess = contentAccess
    )

    private suspend fun <T> inTransaction(block: suspend () -> T): T {
        val database = db
        return if (database != null) database.withTransaction { block() } else block()
    }

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
            bookmarkDao.getAllForBook(id).sortedBy { it.id },
            highlightDao.getAllForBook(id).sortedBy { it.id }
        )
    }

    suspend fun inspect(candidate: EditionCandidate, incoming: BookEntity, chapters: List<ChapterEntity>): Update {
        val old = inTransaction { read(candidate.book.id) } ?: throw EditionChanged()
        if (!sameContentVersion(old.book, candidate.book) ||
            EditionMatcher.versionModified(contentAccess, old.book) != candidate.normalizedModified) throw EditionChanged()
        return map(
            old = old,
            incoming = incoming,
            chapters = chapters,
            relation = candidate.relation,
            textProvider = ChapterTextProvider(contentAccess = contentAccess),
            exactTxtCharPrefix = candidate.exactCharPrefix
        )
    }

    /** 文件已完全准备好，单个事务发布新文件引用、同步书名/封面/已读完状态并迁移定位。 */
    suspend fun publish(
        update: Update,
        incoming: BookEntity,
        chapters: List<ChapterEntity>,
        sourceStamp: BookSourceStampStore.SourceStamp? = null,
        downloadTaskId: String? = null,
        downloadReceipts: DownloadImportReceipts? = null,
        stagedCoverFile: File? = incoming.coverPath?.let(::File)
    ): BookEntity {
        var hybridCoverPath: String? = null
        if (update.old.book.format == BookFormat.TXT && stagedCoverFile != null) {
            val snapMeta = books.customMetaStore.resolve(update.old.book, incoming.title, incoming.author)
            val differsFromOld = snapMeta.title != update.old.book.title || snapMeta.author != update.old.book.author
            val differsFromIncoming = snapMeta.title != incoming.title || snapMeta.author != incoming.author
            if (differsFromOld && differsFromIncoming) {
                if (CoverGenerator.generatePlaceholder(snapMeta.title, snapMeta.author, stagedCoverFile)) {
                    hybridCoverPath = stagedCoverFile.absolutePath
                }
            }
        }

        return inTransaction {
            check(update.preview.resume != ResumeImpact.UNAVAILABLE)
            val id = update.old.book.id
            val current = read(id) ?: throw EditionChanged()
            if (!sameContentVersion(current.book, update.old.book) || current.chapters != update.old.chapters ||
                current.progress != update.old.progress || current.bookmarks != update.old.bookmarks ||
                current.highlights != update.old.highlights) throw EditionChanged()

            val meta = books.customMetaStore.resolve(current.book, incoming.title, incoming.author)
            val resolvedCoverPath = if (current.book.format == BookFormat.EPUB) {
                incoming.coverPath ?: current.book.coverPath
            } else {
                when {
                    meta.title == current.book.title && meta.author == current.book.author ->
                        current.book.coverPath
                    hybridCoverPath != null ->
                        hybridCoverPath
                    meta.title == incoming.title && meta.author == incoming.author ->
                        incoming.coverPath ?: current.book.coverPath
                    else ->
                        incoming.coverPath ?: current.book.coverPath
                }
            }
            val resolvedFinished = resolveFinishedAfterUpdate(
                wasFinished = current.book.finished,
                oldChaptersCount = current.book.totalChapters,
                oldTotalChars = current.book.totalChars,
                newChapters = chapters,
                newTotalChars = incoming.totalChars,
                mappedProgress = update.progress
            )
            val updatedBook = current.book.copy(
                title = meta.title,
                author = meta.author,
                coverPath = resolvedCoverPath,
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
                language = incoming.language ?: current.book.language,
                publisher = incoming.publisher ?: current.book.publisher,
                description = incoming.description ?: current.book.description,
                finished = resolvedFinished,
                addedAt = maxOf(current.book.addedAt, incoming.addedAt)
            )

            books.saveChapters(id, chapters.map { it.copy(bookId = id) })
            books.updateBook(updatedBook)
            update.progress?.let { books.saveProgress(it) }
            for (bookmark in update.bookmarks) bookmarkDao.update(bookmark)
            for (highlight in update.highlights) {
                if (highlight.id == 0L) highlightDao.insert(highlight) else highlightDao.update(highlight)
            }
            books.sourceStampStore.save(id, sourceStamp)
            if (downloadTaskId != null) {
                downloadReceipts?.record(downloadTaskId, id)
            }
            updatedBook
        }
    }

    private fun sameContentVersion(a: BookEntity, b: BookEntity): Boolean =
        a.filePath == b.filePath && a.contentPath == b.contentPath && a.format == b.format &&
            editionKey(a.author.orEmpty()) == editionKey(b.author.orEmpty()) &&
            a.encoding == b.encoding && a.totalChapters == b.totalChapters && a.totalChars == b.totalChars &&
            a.chapterRuleId == b.chapterRuleId && a.chapterRegex == b.chapterRegex

    companion object {
        internal suspend fun map(
            old: Records,
            incoming: BookEntity,
            chapters: List<ChapterEntity>,
            relation: EditionRelation,
            textProvider: ChapterTextProvider = ChapterTextProvider(),
            exactTxtCharPrefix: Boolean = (old.book.format == BookFormat.TXT && relation == EditionRelation.EXTENDS)
        ): Update {
            val anchors = EditionAnchors(
                oldBook = old.book,
                oldChapters = old.chapters,
                newBook = incoming,
                newChapters = chapters,
                relation = relation,
                textProvider = textProvider,
                exactTxtCharPrefix = exactTxtCharPrefix
            )
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
