package com.mirad.reader.data.repo

import android.app.Application
import com.mirad.reader.data.AppPaths
import com.mirad.reader.data.db.bookContentGenerationKey
import com.mirad.reader.data.db.dao.BookDao
import com.mirad.reader.data.db.dao.BookmarkDao
import com.mirad.reader.data.db.dao.ChapterDao
import com.mirad.reader.data.db.dao.HighlightDao
import com.mirad.reader.data.db.dao.PreferenceDao
import com.mirad.reader.data.db.dao.ProgressDao
import com.mirad.reader.data.db.entity.BookmarkEntity
import com.mirad.reader.data.db.entity.ChapterEntity
import com.mirad.reader.data.db.entity.HighlightEntity
import com.mirad.reader.data.db.entity.PreferenceEntity
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteUndoTest {
    private val bookmark = BookmarkEntity(11, 7, 2, 36, "旧章节", "摘录", "书签笔记", 100)
    private val highlight = HighlightEntity(12, 7, 2, 36, 42, "划线摘录", 123, "划线笔记", 100, 200)

    @Test fun unchangedContentRestoresBothEntitiesIncludingTheirOriginalIds() = runBlocking {
        val fixture = Fixture()
        fixture.bookmarks.insert(bookmark)
        fixture.highlights.insert(highlight)
        val removedBookmark = checkNotNull(fixture.notes.deleteBookmarkForUndo(bookmark.id))
        val removedHighlight = checkNotNull(fixture.notes.deleteHighlightForUndo(highlight.id))
        assertNull(fixture.bookmarks.getById(bookmark.id))
        assertNull(fixture.highlights.getById(highlight.id))
        assertFalse(fixture.notes.restoreBookmarkForUndo(removedBookmark))
        assertFalse(fixture.notes.restoreHighlightForUndo(removedHighlight))
        assertEquals(bookmark, fixture.bookmarks.getById(bookmark.id))
        assertEquals(highlight, fixture.highlights.getById(highlight.id))
    }

    @Test fun publishingUnchangedChapterCountsStillInvalidatesDeletedLocations() = runBlocking {
        val fixture = Fixture()
        val chapters = listOf(ChapterEntity(bookId = 7, index = 0, title = "同名同字数", charCount = 500))
        fixture.books.saveChapters(7, chapters)
        val firstGeneration = fixture.preferences.get(bookContentGenerationKey(7))
        assertNotNull(firstGeneration)
        fixture.bookmarks.insert(bookmark)
        fixture.highlights.insert(highlight)
        val removedBookmark = checkNotNull(fixture.notes.deleteBookmarkForUndo(bookmark.id))
        val removedHighlight = checkNotNull(fixture.notes.deleteHighlightForUndo(highlight.id))

        // 正文可为同 URI 等长修订；不能由相同章节结构推断定位仍然有效。
        fixture.books.saveChapters(7, chapters)
        assertNotEquals(firstGeneration, fixture.preferences.get(bookContentGenerationKey(7)))
        assertTrue(fixture.notes.restoreBookmarkForUndo(removedBookmark))
        assertTrue(fixture.notes.restoreHighlightForUndo(removedHighlight))
        assertEquals(bookmark.copy(chapterIndex = -1), fixture.bookmarks.getById(bookmark.id))
        assertEquals(highlight.copy(chapterIndex = -1), fixture.highlights.getById(highlight.id))
    }

    @Test fun firstPublicationAfterLegacyDeletionInvalidatesTheOldLocation() = runBlocking {
        val fixture = Fixture()
        fixture.bookmarks.insert(bookmark)
        val removed = checkNotNull(fixture.notes.deleteBookmarkForUndo(bookmark.id))
        assertNull(removed.generation)
        fixture.books.saveChapters(7, listOf(ChapterEntity(bookId = 7, index = 0, title = "新版", charCount = 500)))
        assertTrue(fixture.notes.restoreBookmarkForUndo(removed))
        assertEquals(bookmark.copy(chapterIndex = -1), fixture.bookmarks.getById(bookmark.id))
    }

    @Test fun missingRecordsHaveNoUndoSnapshot() = runBlocking {
        val fixture = Fixture()
        assertNull(fixture.notes.deleteBookmarkForUndo(bookmark.id))
        assertNull(fixture.notes.deleteHighlightForUndo(highlight.id))
    }

    private class Fixture {
        val preferences = Preferences()
        val bookmarks = Bookmarks(preferences)
        val highlights = Highlights(preferences)
        val notes = NoteRepository(highlights, bookmarks)
        val books = BookRepository(
            bookDao = proxy<BookDao>(), chapterDao = proxy<ChapterDao>(), progressDao = proxy<ProgressDao>(),
            paths = AppPaths(Application()), preferenceDao = preferences
        )
    }

    private class Preferences : PreferenceDao {
        private val rows = linkedMapOf<String, PreferenceEntity>()
        override suspend fun put(preference: PreferenceEntity) { rows[preference.key] = preference }
        override suspend fun get(key: String): String? = rows[key]?.value
        override fun observe(key: String): Flow<String?> = MutableStateFlow(rows[key]?.value)
        override suspend fun remove(key: String) { rows.remove(key) }
        override suspend fun removeByPrefix(prefix: String) { rows.keys.removeAll { it.startsWith(prefix) } }
        override suspend fun getAll(): List<PreferenceEntity> = rows.values.toList()
    }

    // 只替代存储原语；删除快照与撤销比较执行生产 DAO 的默认方法。
    private class Bookmarks(private val preferences: Preferences) : BookmarkDao {
        private val rows = linkedMapOf<Long, BookmarkEntity>()
        override suspend fun insert(bookmark: BookmarkEntity): Long { rows[bookmark.id] = bookmark; return bookmark.id }
        override suspend fun update(bookmark: BookmarkEntity) { rows[bookmark.id] = bookmark }
        override suspend fun delete(bookmark: BookmarkEntity) { rows.remove(bookmark.id) }
        override suspend fun getById(id: Long): BookmarkEntity? = rows[id]
        override suspend fun contentGeneration(bookId: Long): String? = preferences.get(bookContentGenerationKey(bookId))
        override suspend fun deleteForBook(bookId: Long) { rows.values.removeAll { it.bookId == bookId } }
        override fun observeForBook(bookId: Long): Flow<List<BookmarkEntity>> = MutableStateFlow(rows.values.filter { it.bookId == bookId })
        override suspend fun find(bookId: Long, chapterIndex: Int, charOffset: Int): BookmarkEntity? =
            rows.values.firstOrNull { it.bookId == bookId && it.chapterIndex == chapterIndex && it.charOffset == charOffset }
        override suspend fun getAllForBook(bookId: Long): List<BookmarkEntity> = rows.values.filter { it.bookId == bookId }
        override suspend fun getAll(): List<BookmarkEntity> = rows.values.toList()
    }

    private class Highlights(private val preferences: Preferences) : HighlightDao {
        private val rows = linkedMapOf<Long, HighlightEntity>()
        override suspend fun insert(highlight: HighlightEntity): Long { rows[highlight.id] = highlight; return highlight.id }
        override suspend fun update(highlight: HighlightEntity) { rows[highlight.id] = highlight }
        override suspend fun delete(highlight: HighlightEntity) { rows.remove(highlight.id) }
        override suspend fun getById(id: Long): HighlightEntity? = rows[id]
        override suspend fun contentGeneration(bookId: Long): String? = preferences.get(bookContentGenerationKey(bookId))
        override suspend fun deleteForBook(bookId: Long) { rows.values.removeAll { it.bookId == bookId } }
        override fun observeForBook(bookId: Long): Flow<List<HighlightEntity>> = MutableStateFlow(rows.values.filter { it.bookId == bookId })
        override suspend fun getForChapter(bookId: Long, chapterIndex: Int): List<HighlightEntity> =
            rows.values.filter { it.bookId == bookId && it.chapterIndex == chapterIndex }
        override suspend fun getAllForBook(bookId: Long): List<HighlightEntity> = rows.values.filter { it.bookId == bookId }
        override suspend fun getAll(): List<HighlightEntity> = rows.values.toList()
    }

    companion object {
        private inline fun <reified T> proxy(): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            when (method.name) {
                "deleteForBook", "insertAll", "updateCounts" -> Unit
                else -> error("未预期的存储操作：${method.name}")
            }
        } as T
    }
}
