package com.mirad.reader.ui.notes

import android.app.Application
import com.mirad.reader.data.AppPaths
import com.mirad.reader.data.db.dao.BookDao
import com.mirad.reader.data.db.dao.BookmarkDao
import com.mirad.reader.data.db.dao.ChapterDao
import com.mirad.reader.data.db.dao.HighlightDao
import com.mirad.reader.data.db.dao.ProgressDao
import com.mirad.reader.data.db.entity.BookEntity
import com.mirad.reader.data.db.entity.BookmarkEntity
import com.mirad.reader.data.db.entity.HighlightEntity
import com.mirad.reader.data.repo.BookRepository
import com.mirad.reader.data.repo.NoteRepository
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotesExportTest {
    @Test fun exportingAgainReadsTheUpdatedBookNameAndCurrentNotesWithoutRecreatingTheRepositories() = runBlocking {
        var book = BookEntity(id = 1, title = "Chronicle1-3", format = "TXT", filePath = "same-source.txt")
        var highlights = listOf(
            HighlightEntity(id = 2, bookId = 1, chapterIndex = 0,
                startOffset = 10, endOffset = 14, text = "原有摘录", color = 123, note = "更新前备注", createdAt = 1),
            HighlightEntity(id = 4, bookId = 1, chapterIndex = 0,
                startOffset = 20, endOffset = 24, text = "较新摘录", color = 123, createdAt = 2)
        )
        var bookmarks = emptyList<BookmarkEntity>()
        val books = BookRepository(
            bookDao = proxy<BookDao> { method -> when (method) {
                "getAll" -> listOf(book)
                else -> error(method)
            } },
            chapterDao = proxy<ChapterDao> { error(it) },
            progressDao = proxy<ProgressDao> { error(it) },
            paths = AppPaths(Application())
        )
        val notes = NoteRepository(
            proxy<HighlightDao> { method -> when (method) {
                "getAll" -> highlights
                else -> error(method)
            } },
            proxy<BookmarkDao> { method -> when (method) {
                "getAll" -> bookmarks
                else -> error(method)
            } }
        )
        val before = checkNotNull(buildCurrentFullExport(books, notes))
        assertTrue(before.contains("## Chronicle1-3"))
        assertTrue(before.contains("更新前备注"))
        assertTrue("同章摘录保留原有的创建时间倒序", before.indexOf("较新摘录") < before.indexOf("原有摘录"))

        // 同屏导入新版并撤销书签后，界面的旧列表尚未重组也必须导出当前存储内容。
        book = book.copy(title = "Chronicle1-4", totalChapters = 4)
        highlights = highlights.map { it.copy(note = "更新后备注") }
        bookmarks = listOf(BookmarkEntity(id = 3, bookId = 1, chapterIndex = -1, charOffset = 20,
            chapterTitle = "原章节", snippet = "刚恢复的书签"))
        val after = checkNotNull(buildCurrentFullExport(books, notes))
        assertTrue(after.contains("## Chronicle1-4"))
        assertTrue(after.contains("更新后备注"))
        assertTrue(after.contains("刚恢复的书签"))
        assertFalse(after.contains("Chronicle1-3"))
        assertFalse(after.contains("更新前备注"))

        highlights = emptyList()
        bookmarks = emptyList()
        assertNull(buildCurrentFullExport(books, notes))
    }

    companion object {
        private inline fun <reified T> proxy(crossinline read: (String) -> Any): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
                read(method.name)
            } as T
    }
}
