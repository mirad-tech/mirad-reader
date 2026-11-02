package com.mirad.reader.ui.shelf

import com.mirad.reader.data.db.entity.BookEntity
import com.mirad.reader.data.db.entity.BookFormat
import com.mirad.reader.data.repo.ShelfBook
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** 直接覆盖书架实际调用的动作协调函数；存储写入用受控回调，不构造业务数据库。 */
class ShelfInteractionRegressionTest {
    private fun book(id: Long, pinned: Boolean = false, groupId: Long? = null) = BookEntity(
        id = id, title = "回归书 $id", format = BookFormat.TXT,
        filePath = "synthetic-$id.txt", isPinned = pinned, groupId = groupId
    )

    @Test fun deletingTheSelectedLastGroupReturnsTheBooksToAnAccessibleFilter() = runBlocking {
        var books = listOf(book(1, groupId = 7), book(2, groupId = 7))
        val deleted = mutableListOf<Long>()
        val selected = ShelfGroupActions.commitDeleteGroup(7, { 7L }) { id ->
            deleted += id
            books = books.map { if (it.groupId == id) it.copy(groupId = null) else it }
        }
        assertEquals(listOf(7L), deleted)
        assertNull("删除当前分组必须离开旧筛选", selected)
        assertEquals(2, ShelfQuery.apply(books.map { ShelfBook(it, null, 0f, 0) },
            ShelfSort.RECENT, selected).size)
        assertEquals(listOf(1L, 2L), books.map { it.id })
    }

    @Test fun deletingAnotherGroupPreservesTheCurrentFilterAndUngroupedSentinel() = runBlocking {
        for (selected in listOf(null, 42L, ShelfQuery.GROUP_NONE)) {
            assertEquals(selected, ShelfGroupActions.commitDeleteGroup(7, { selected }) { })
        }
    }

    @Test fun aFailedGroupDeletionDoesNotReportAChangedFilter() = runBlocking {
        var selected: Long? = 7L
        try {
            selected = ShelfGroupActions.commitDeleteGroup(7, { selected }) { error("受控删除失败") }
            fail("删除失败必须返回错误")
        } catch (expected: IllegalStateException) {
            assertEquals("受控删除失败", expected.message)
        }
        assertEquals(7L, selected)
    }

    @Test fun selectionChangedWhileDeletingIsNotOverwrittenByTheOldFilter() = runBlocking {
        var selected: Long? = 7L
        val deletionStarted = CompletableDeferred<Unit>()
        val allowDeletion = CompletableDeferred<Unit>()
        val deletion = async {
            selected = ShelfGroupActions.commitDeleteGroup(7, { selected }) {
                deletionStarted.complete(Unit)
                allowDeletion.await()
            }
        }
        deletionStarted.await()
        selected = 9L
        allowDeletion.complete(Unit)
        deletion.await()
        assertEquals("删除完成后应保留挂起期间的新筛选", 9L, selected)
    }

    @Test fun aConcurrentDeletionCannotRestoreAnAlreadyClearedFilter() = runBlocking {
        var selected: Long? = 9L
        val deletionStarted = CompletableDeferred<Unit>()
        val allowDeletion = CompletableDeferred<Unit>()
        val deletion = async {
            selected = ShelfGroupActions.commitDeleteGroup(7, { selected }) {
                deletionStarted.complete(Unit)
                allowDeletion.await()
            }
        }
        deletionStarted.await()
        selected = null
        allowDeletion.complete(Unit)
        deletion.await()
        assertNull("另一路已经清掉的筛选不能被迟到删除恢复", selected)
    }

    @Test fun singleBookDeleteCancellationLeavesNoAccidentalMultiSelection() {
        assertTrue(ShelfGroupActions.dismissDeleteSelection(setOf(1L), isSingleBookAction = true).isEmpty())
    }

    @Test fun cancellingABatchDeleteKeepsTheExistingSelection() {
        assertEquals(setOf(1L, 2L), ShelfGroupActions.dismissDeleteSelection(setOf(1L, 2L), isSingleBookAction = false))
    }

    @Test fun theRealBookMenuContainsAMultiSelectActionForThePressedBook() {
        val pressed = book(42)
        var selection = emptySet<Long>()
        val action = actions(pressed, onMultiSelect = { selection = setOf(pressed.id) }).singleOrNull { it.label == "多选" }
        assertNotNull("正常单书菜单必须提供多选入口", action)
        action!!.onClick()
        assertEquals(setOf(42L), selection)
    }

    @Test fun theRealBookMenuKeepsSingleBookPinAndFormatActions() {
        assertTrue(actions(book(1)).any { it.label == "置顶" })
        assertTrue(actions(book(1, pinned = true)).any { it.label == "取消置顶" })
        assertTrue(actions(book(1)).any { it.label == "重新识别章节" })
        assertFalse(actions(book(1).copy(format = BookFormat.EPUB)).any { it.label == "重新识别章节" })
    }

    @Test fun bulkPinningAMixedSelectionDoesNotUnpinAnAlreadyPinnedBook() = runBlocking {
        val result = mutableMapOf(1L to true, 2L to false)
        ShelfGroupActions.commitPinSelected(listOf(book(1, pinned = true), book(2))) { id, pinned -> result[id] = pinned }
        assertEquals(mapOf(1L to true, 2L to true), result)
    }

    @Test fun bulkPinningAnAlreadyPinnedSelectionIsIdempotent() = runBlocking {
        val result = mutableMapOf(1L to true, 2L to true)
        repeat(2) {
            ShelfGroupActions.commitPinSelected(result.map { (id, pinned) -> book(id, pinned) }) { id, pinned -> result[id] = pinned }
            assertTrue("重复置顶不能取消已有置顶", result.values.all { it })
        }
    }

    @Test fun bulkPinningAnUnpinnedSelectionPinsEveryBook() = runBlocking {
        val writes = mutableListOf<Pair<Long, Boolean>>()
        ShelfGroupActions.commitPinSelected(listOf(book(1), book(2))) { id, pinned -> writes += id to pinned }
        assertEquals(listOf(1L to true, 2L to true), writes)
    }

    private fun actions(book: BookEntity, onMultiSelect: () -> Unit = {}) = ShelfGroupActions.bookActionItems(
        book = book, onOpen = {}, onUnhide = {}, onEdit = {}, onTogglePinned = {},
        onMoveToGroup = {}, onHide = {}, onChapters = {}, onEncoding = {}, onDelete = {}, onMultiSelect = onMultiSelect
    )
}
