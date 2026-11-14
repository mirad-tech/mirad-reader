package com.mirad.reader.data.repo

import androidx.room.withTransaction
import com.mirad.reader.data.AppPaths
import com.mirad.reader.data.db.AppDatabase
import com.mirad.reader.data.db.dao.BookDao
import com.mirad.reader.data.db.dao.ChapterDao
import com.mirad.reader.data.db.dao.PreferenceDao
import com.mirad.reader.data.db.dao.ProgressDao
import com.mirad.reader.data.db.dao.SessionDao
import com.mirad.reader.data.db.entity.BookEntity
import com.mirad.reader.data.db.entity.BookFormat
import com.mirad.reader.data.db.entity.ChapterEntity
import com.mirad.reader.data.db.entity.ProgressEntity
import com.mirad.reader.data.imports.BookCustomMetaStore
import com.mirad.reader.data.imports.BookSourceStampStore
import com.mirad.reader.data.imports.CoverGenerator
import com.mirad.reader.ui.shelf.CoverCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 书架条目：书籍 + 进度。 */
data class ShelfBook(
    val book: BookEntity,
    val progress: ProgressEntity?,
    val percent: Float,
    val lastReadAt: Long
)

/** 书籍与章节仓库。 */
class BookRepository(
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val progressDao: ProgressDao,
    private val paths: AppPaths,
    private val searchFtsDao: com.mirad.reader.data.db.dao.SearchFtsDao? = null,
    private val appDatabase: AppDatabase? = null,
    private val sessionDao: SessionDao? = null,
    private val preferenceDao: PreferenceDao? = appDatabase?.preferenceDao()
) {
    private val contentLocks = ConcurrentHashMap<Long, Mutex>()
    private val finishedWrites = Mutex()
    internal val customMetaStore = BookCustomMetaStore(preferenceDao)
    internal val sourceStampStore = BookSourceStampStore(preferenceDao)

    /** 阅读会话及改变正文或章节的操作共用同一本书的锁。 */
    internal fun contentLock(id: Long): Mutex = contentLocks.computeIfAbsent(id) { Mutex() }

    fun observeShelf(): Flow<List<ShelfBook>> = combine(
        bookDao.observeAll(),
        progressDao.observeAll()
    ) { books, progresses ->
        val byBook = progresses.associateBy { it.bookId }
        books.map { book ->
            val progress = byBook[book.id]
            ShelfBook(
                book = book,
                progress = progress,
                percent = progress?.percent ?: 0f,
                lastReadAt = maxOf(book.lastReadAt, progress?.updatedAt ?: 0L)
            )
        }
    }

    suspend fun getBook(id: Long): BookEntity? = bookDao.getById(id)

    internal suspend fun getAllBooks(): List<BookEntity> = bookDao.getAll()

    suspend fun getChapters(bookId: Long): List<ChapterEntity> = chapterDao.getForBook(bookId)

    suspend fun getChapter(bookId: Long, index: Int): ChapterEntity? = chapterDao.getChapter(bookId, index)

    suspend fun insertBook(book: BookEntity): Long = bookDao.insert(book)

    suspend fun updateBook(book: BookEntity) = bookDao.update(book)

    suspend fun updateMeta(id: Long, title: String, author: String?) {
        val snapshot = bookDao.getById(id) ?: return
        val preTitle = title.trim().ifBlank { snapshot.title }
        val preAuthor = author?.trim()?.ifBlank { null }

        // 若 TXT 书籍原已有占位封面且书名/作者发生变化，先在 IO 线程写入新的独立封面文件，
        // 使 BookEntity.coverPath 路径随之更新，触发 Compose BookCover 立即重读新图且无原地覆写竞态。
        var stagedCoverPath: String? = null
        if (snapshot.format == BookFormat.TXT &&
            !snapshot.coverPath.isNullOrBlank() &&
            (preTitle != snapshot.title || preAuthor != snapshot.author)
        ) {
            val coverFile = paths.coverFile(UUID.randomUUID().toString())
            val generated = withContext(Dispatchers.IO) {
                CoverGenerator.generatePlaceholder(preTitle, preAuthor, coverFile)
            }
            if (generated) {
                stagedCoverPath = coverFile.absolutePath
            }
        }

        var obsoleteCoverPath: String? = null
        var committed = false
        try {
            val block: suspend () -> Unit = {
                obsoleteCoverPath = null
                val current = bookDao.getById(id)
                if (current != null) {
                    val normTitle = title.trim().ifBlank { current.title }
                    val normAuthor = author?.trim()?.ifBlank { null }
                    customMetaStore.recordEdit(current, normTitle, normAuthor)
                    if (stagedCoverPath != null && current.format == BookFormat.TXT && !current.coverPath.isNullOrBlank()) {
                        obsoleteCoverPath = current.coverPath
                        bookDao.update(
                            current.copy(
                                title = normTitle,
                                author = normAuthor,
                                coverPath = stagedCoverPath
                            )
                        )
                    } else {
                        bookDao.updateMeta(id, normTitle, normAuthor)
                    }
                }
            }
            val db = appDatabase
            if (db != null) db.withTransaction { block() } else block()
            committed = true
        } finally {
            if (!committed && stagedCoverPath != null) {
                withContext(NonCancellable + Dispatchers.IO) {
                    paths.deleteBookFiles(null, stagedCoverPath)
                    CoverCache.evict(stagedCoverPath)
                }
            }
        }

        val oldCover = obsoleteCoverPath
        if (oldCover != null && oldCover != stagedCoverPath) {
            withContext(NonCancellable + Dispatchers.IO) {
                paths.deleteBookFiles(null, oldCover)
                CoverCache.evict(oldCover)
            }
        } else if (stagedCoverPath != null && oldCover == null) {
            withContext(NonCancellable + Dispatchers.IO) {
                paths.deleteBookFiles(null, stagedCoverPath)
                CoverCache.evict(stagedCoverPath)
            }
        }
    }

    suspend fun setPinned(id: Long, pinned: Boolean) = bookDao.setPinned(id, pinned)

    suspend fun setHidden(id: Long, hidden: Boolean) = bookDao.setHidden(id, hidden)

    suspend fun setGroup(id: Long, groupId: Long?) = bookDao.setGroup(id, groupId)

    internal suspend fun setFinished(id: Long, finished: Boolean, endChapterIndex: Int?): Boolean =
        finishedWrites.withLock { bookDao.setFinished(id, finished, endChapterIndex) > 0 }

    suspend fun touchLastRead(id: Long, time: Long = System.currentTimeMillis()) = bookDao.touchLastRead(id, time)

    suspend fun findDuplicateCandidates(title: String, fileSize: Long): List<BookEntity> =
        bookDao.findDuplicateCandidates(title, fileSize)

    suspend fun saveChapters(bookId: Long, chapters: List<ChapterEntity>) {
        // 删-插-更新必须同事务：中途崩溃会把一本正常书的章节全部删光。
        val block: suspend () -> Unit = {
            chapterDao.deleteForBook(bookId)
            searchFtsDao?.deleteForBook(bookId)
            if (chapters.isNotEmpty()) chapterDao.insertAll(chapters)
            bookDao.updateCounts(bookId, chapters.size, chapters.sumOf { it.charCount })
        }
        val db = appDatabase
        if (db != null) db.withTransaction { block() } else block()
    }

    /** 重新分章后同步书籍索引信息。 */
    suspend fun updateIndexInfo(
        bookId: Long,
        chapters: Int,
        chars: Long,
        ruleId: String?,
        regex: String?,
        encoding: String?
    ) = bookDao.updateIndexInfo(bookId, chapters, chars, ruleId, regex, encoding)

    suspend fun getProgress(bookId: Long): ProgressEntity? = progressDao.get(bookId)

    suspend fun saveProgress(progress: ProgressEntity) = progressDao.upsert(progress)

    /** 删除书籍及其全部数据与文件（阅读记录一并删除，与删除确认文案一致）。 */
    suspend fun deleteBook(book: BookEntity) = contentLock(book.id).withLock {
        withContext(Dispatchers.IO) { deleteCurrentBook(book.id) }
    }

    private suspend fun deleteCurrentBook(id: Long) {
        val book = bookDao.getById(id) ?: return
        // 先删数据库行（外键 CASCADE 清理章节/进度/划线/书签；另清 FTS、阅读会话与关联偏好标记），
        // 文件删除放最后：仅清理应用内部 books/<uuid> 托管副本与封面小图，绝不触碰用户手机原文件。
        val block: suspend () -> Unit = {
            searchFtsDao?.deleteForBook(book.id)
            sessionDao?.deleteForBook(book.id)
            customMetaStore.remove(book.id)
            sourceStampStore.remove(book.id)
            bookDao.delete(book)
        }
        val db = appDatabase
        if (db != null) db.withTransaction { block() } else block()
        paths.deleteManagedBookStorage(book.filePath, book.contentPath, book.coverPath)
        CoverCache.evict(book.coverPath)
    }

    fun tokenOf(book: BookEntity): String =
        com.mirad.reader.data.io.BookContentAccess.managedBookToken(paths, book.filePath)
            ?: com.mirad.reader.data.io.BookContentAccess.managedBookToken(paths, book.contentPath)
            ?: book.id.toString()

    fun originalFileOf(book: BookEntity): File = File(book.filePath)
}
