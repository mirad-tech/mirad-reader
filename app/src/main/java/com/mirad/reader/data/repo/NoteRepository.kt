package com.mirad.reader.data.repo

import com.mirad.reader.data.db.dao.BookmarkDao
import com.mirad.reader.data.db.dao.HighlightDao
import com.mirad.reader.data.db.dao.DeletedBookmark
import com.mirad.reader.data.db.dao.DeletedHighlight
import com.mirad.reader.data.db.entity.BookmarkEntity
import com.mirad.reader.data.db.entity.HighlightEntity
import kotlinx.coroutines.flow.Flow

/** 书签与划线笔记仓库。 */
class NoteRepository(
    private val highlightDao: HighlightDao,
    private val bookmarkDao: BookmarkDao
) {

    fun observeHighlights(bookId: Long): Flow<List<HighlightEntity>> = highlightDao.observeForBook(bookId)

    fun observeBookmarks(bookId: Long): Flow<List<BookmarkEntity>> = bookmarkDao.observeForBook(bookId)

    suspend fun highlightsForChapter(bookId: Long, chapterIndex: Int): List<HighlightEntity> =
        highlightDao.getForChapter(bookId, chapterIndex)

    suspend fun allHighlights(bookId: Long): List<HighlightEntity> = highlightDao.getAllForBook(bookId)

    suspend fun addHighlight(highlight: HighlightEntity): Long = highlightDao.insert(highlight)

    suspend fun deleteHighlight(highlight: HighlightEntity) = highlightDao.delete(highlight)
    internal suspend fun deleteHighlightForUndo(id: Long): DeletedHighlight? = highlightDao.deleteForUndo(id)
    internal suspend fun restoreHighlightForUndo(deleted: DeletedHighlight): Boolean = highlightDao.restoreForUndo(deleted)

    suspend fun addBookmark(bookmark: BookmarkEntity): Long = bookmarkDao.insert(bookmark)

    suspend fun deleteBookmark(bookmark: BookmarkEntity) = bookmarkDao.delete(bookmark)
    internal suspend fun deleteBookmarkForUndo(id: Long): DeletedBookmark? = bookmarkDao.deleteForUndo(id)
    internal suspend fun restoreBookmarkForUndo(deleted: DeletedBookmark): Boolean = bookmarkDao.restoreForUndo(deleted)

    suspend fun findBookmark(bookId: Long, chapterIndex: Int, charOffset: Int): BookmarkEntity? =
        bookmarkDao.find(bookId, chapterIndex, charOffset)

    suspend fun allBookmarks(): List<BookmarkEntity> = bookmarkDao.getAll()

    suspend fun allHighlights(): List<HighlightEntity> = highlightDao.getAll()
}
