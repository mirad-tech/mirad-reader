package com.mirad.reader.ui.shelf

import com.mirad.reader.data.db.entity.BookEntity
import com.mirad.reader.data.db.entity.BookFormat
import com.mirad.reader.data.repo.ShelfBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShelfQueryTest {

    private fun book(
        id: Long,
        title: String,
        author: String? = null,
        addedAt: Long = 0L,
        isPinned: Boolean = false,
        isHidden: Boolean = false,
        groupId: Long? = null,
        size: Long = 0L
    ) = BookEntity(
        id = id,
        title = title,
        author = author,
        format = BookFormat.TXT,
        filePath = "/tmp/$id",
        fileSize = size,
        groupId = groupId,
        isPinned = isPinned,
        isHidden = isHidden,
        addedAt = addedAt
    )

    private fun shelf(id: Long, book: BookEntity, lastReadAt: Long = 0L) =
        ShelfBook(book = book, progress = null, percent = 0f, lastReadAt = lastReadAt)

    private val all = listOf(
        shelf(1, book(1, "风雪归人", "张三", addedAt = 100, groupId = 7L), lastReadAt = 500),
        shelf(2, book(2, "星辰之下", "李四", addedAt = 300, isPinned = true), lastReadAt = 0),
        shelf(3, book(3, "旧书残卷", null, addedAt = 200, isHidden = true), lastReadAt = 0),
        shelf(4, book(4, "雪夜行", "张三", addedAt = 400, size = 999), lastReadAt = 100)
    )

    @Test
    fun hiddenBooksAreExcludedByDefault() {
        val result = ShelfQuery.apply(all, ShelfSort.RECENT)
        assertEquals(3, result.size)
        assertTrue(result.none { it.book.isHidden })
    }

    @Test
    fun includeHiddenShowsThem() {
        val result = ShelfQuery.apply(all, ShelfSort.RECENT, includeHidden = true)
        assertEquals(4, result.size)
        assertTrue(result.any { it.book.isHidden })
    }

    @Test
    fun pinnedBooksComeFirstThenByLastRead() {
        val result = ShelfQuery.apply(all, ShelfSort.RECENT)
        assertEquals(2L, result.first().book.id)
        assertEquals(listOf(1L, 4L), result.drop(1).map { it.book.id })
    }

    @Test
    fun groupFilterSupportsSingleGroupAndUngrouped() {
        assertEquals(listOf(1L), ShelfQuery.apply(all, ShelfSort.ADDED, groupId = 7L).map { it.book.id })
        val ungrouped = ShelfQuery.apply(all, ShelfSort.ADDED, groupId = ShelfQuery.GROUP_NONE).map { it.book.id }
        assertTrue(ungrouped.contains(2L))
        assertTrue(!ungrouped.contains(1L))
    }

    @Test
    fun keywordMatchesTitleOrAuthorCaseInsensitively() {
        assertEquals(2, ShelfQuery.apply(all, ShelfSort.TITLE, query = "雪").size)
        assertEquals(2, ShelfQuery.apply(all, ShelfSort.TITLE, query = "张三").size)
        assertEquals(0, ShelfQuery.apply(all, ShelfSort.TITLE, query = "不存在的书").size)
    }

    @Test
    fun sortModesAreStableAndPinnedFirst() {
        // 添加时间倒序：置顶的 2 在前，其余 4(400) → 3(200) → 1(100)
        assertEquals(
            listOf(2L, 4L, 3L, 1L),
            ShelfQuery.apply(all, ShelfSort.ADDED, includeHidden = true).map { it.book.id }
        )
        // 文件大小倒序：置顶的 2 在前，4(999) 之后是同为 0 的 1、3（保持原有顺序）
        assertEquals(
            listOf(2L, 4L, 1L, 3L),
            ShelfQuery.apply(all, ShelfSort.SIZE, includeHidden = true).map { it.book.id }
        )
        val byTitle = ShelfQuery.apply(all, ShelfSort.TITLE)
        assertEquals(2L, byTitle.first().book.id)
        assertEquals(3, byTitle.size)
    }
}
