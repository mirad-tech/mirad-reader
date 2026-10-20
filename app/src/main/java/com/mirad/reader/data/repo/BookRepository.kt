package com.mirad.reader.data.repo

import com.mirad.reader.data.AppPaths
import com.mirad.reader.data.db.dao.BookDao
import com.mirad.reader.data.db.dao.ChapterDao
import com.mirad.reader.data.db.dao.ProgressDao
import com.mirad.reader.data.db.entity.BookEntity
import com.mirad.reader.data.db.entity.ChapterEntity
import com.mirad.reader.data.db.entity.ProgressEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.io.File

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
    private val searchFtsDao: com.mirad.reader.data.db.dao.SearchFtsDao? = null
) {

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

    fun observeBook(id: Long): Flow<BookEntity?> = bookDao.observeById(id)

    suspend fun getBook(id: Long): BookEntity? = bookDao.getById(id)

    suspend fun getChapters(bookId: Long): List<ChapterEntity> = chapterDao.getForBook(bookId)

    fun observeChapters(bookId: Long): Flow<List<ChapterEntity>> = chapterDao.observeForBook(bookId)

    suspend fun getChapter(bookId: Long, index: Int): ChapterEntity? = chapterDao.getChapter(bookId, index)

    suspend fun insertBook(book: BookEntity): Long = bookDao.insert(book)

    suspend fun updateBook(book: BookEntity) = bookDao.update(book)

    suspend fun updateMeta(id: Long, title: String, author: String?) = bookDao.updateMeta(id, title, author)

    suspend fun setPinned(id: Long, pinned: Boolean) = bookDao.setPinned(id, pinned)

    suspend fun setHidden(id: Long, hidden: Boolean) = bookDao.setHidden(id, hidden)

    suspend fun setGroup(id: Long, groupId: Long?) = bookDao.setGroup(id, groupId)

    suspend fun setFinished(id: Long, finished: Boolean) = bookDao.setFinished(id, finished)

    suspend fun touchLastRead(id: Long, time: Long = System.currentTimeMillis()) = bookDao.touchLastRead(id, time)

    suspend fun findDuplicate(title: String, fileSize: Long): BookEntity? = bookDao.findDuplicate(title, fileSize)

    suspend fun saveChapters(bookId: Long, chapters: List<ChapterEntity>) {
        chapterDao.deleteForBook(bookId)
        searchFtsDao?.deleteForBook(bookId)
        if (chapters.isNotEmpty()) chapterDao.insertAll(chapters)
        bookDao.updateCounts(bookId, chapters.size, chapters.sumOf { it.charCount })
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

    fun observeProgress(bookId: Long): Flow<ProgressEntity?> = progressDao.observe(bookId)

    suspend fun saveProgress(progress: ProgressEntity) = progressDao.upsert(progress)

    /** 删除书籍及其全部文件。 */
    suspend fun deleteBook(book: BookEntity) {
        paths.deleteBookFiles(tokenOf(book), book.coverPath)
        searchFtsDao?.deleteForBook(book.id)
        bookDao.delete(book)
    }

    fun tokenOf(book: BookEntity): String =
        File(book.filePath).parentFile?.name ?: book.id.toString()

    fun originalFileOf(book: BookEntity): File = File(book.filePath)
}
