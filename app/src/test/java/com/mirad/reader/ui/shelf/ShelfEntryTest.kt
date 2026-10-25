package com.mirad.reader.ui.shelf

import com.mirad.reader.data.db.entity.BookEntity
import com.mirad.reader.data.db.entity.BookFormat
import com.mirad.reader.data.repo.ShelfBook
import kotlinx.coroutines.async
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShelfEntryTest {

    private fun createBook(id: Long, title: String, isHidden: Boolean = false): BookEntity {
        return BookEntity(
            id = id,
            title = title,
            author = "作者 $id",
            format = BookFormat.TXT,
            filePath = "/tmp/$id.txt",
            fileSize = 1000L,
            isHidden = isHidden,
            addedAt = 100L
        )
    }

    private fun createShelfItem(book: BookEntity, lastReadAt: Long): ShelfBook {
        return ShelfBook(
            book = book,
            progress = null,
            percent = 0.5f,
            lastReadAt = lastReadAt
        )
    }

    @Test
    fun testF09ContinueReadingExcludesHiddenBooksAndFallbacks() {
        val book1 = createBook(1L, "书本 1", isHidden = false)
        val book2 = createBook(2L, "书本 2", isHidden = false)
        val book3 = createBook(3L, "书本 3", isHidden = false)

        // 初始状态：book3 最近阅读 (3000), book2 次之 (2000), book1 最早 (1000)
        val shelf1 = listOf(
            createShelfItem(book1, 1000L),
            createShelfItem(book2, 2000L),
            createShelfItem(book3, 3000L)
        )
        val res1 = resolveContinueReading(shelf1, showHidden = false)
        assertNotNull(res1)
        assertEquals(3L, res1?.book?.id)
        assertEquals("书本 3", res1?.book?.title)

        // 隐藏最近阅读书 (book3.isHidden = true)：普通书架续读卡应回退到 book2
        val shelf2 = listOf(
            createShelfItem(book1, 1000L),
            createShelfItem(book2, 2000L),
            createShelfItem(book3.copy(isHidden = true), 3000L)
        )
        val res2 = resolveContinueReading(shelf2, showHidden = false)
        assertNotNull(res2)
        assertEquals(2L, res2?.book?.id)
        assertEquals("书本 2", res2?.book?.title)

        // 再隐藏 book2：回退到 book1
        val shelf3 = listOf(
            createShelfItem(book1, 1000L),
            createShelfItem(book2.copy(isHidden = true), 2000L),
            createShelfItem(book3.copy(isHidden = true), 3000L)
        )
        val res3 = resolveContinueReading(shelf3, showHidden = false)
        assertNotNull(res3)
        assertEquals(1L, res3?.book?.id)
        assertEquals("书本 1", res3?.book?.title)

        // 全隐藏：返回 null（不展示续读卡）
        val shelf4 = listOf(
            createShelfItem(book1.copy(isHidden = true), 1000L),
            createShelfItem(book2.copy(isHidden = true), 2000L),
            createShelfItem(book3.copy(isHidden = true), 3000L)
        )
        val res4 = resolveContinueReading(shelf4, showHidden = false)
        assertNull("所有已读书均隐藏时，续读卡应为 null", res4)

        // 在已隐藏列表页面（showHidden = true），续读卡应始终为 null
        val resHiddenPage = resolveContinueReading(shelf1, showHidden = true)
        assertNull("已隐藏管理页面不应展示续读卡", resHiddenPage)

        // 取消 book3 隐藏：恢复展示 book3
        val shelf5 = listOf(
            createShelfItem(book1.copy(isHidden = true), 1000L),
            createShelfItem(book2.copy(isHidden = true), 2000L),
            createShelfItem(book3.copy(isHidden = false), 3000L)
        )
        val res5 = resolveContinueReading(shelf5, showHidden = false)
        assertNotNull(res5)
        assertEquals(3L, res5?.book?.id)
    }

    @Test
    fun testF08ShelfGroupActionsDismissSelectionAndRaceCondition() = kotlinx.coroutines.runBlocking {
        // 1. 单本书点击进入分组，取消后 selection 必须清空
        val singleDismissed = ShelfGroupActions.computeDismissSelection(
            currentSelection = setOf(42L),
            isSingleBookAction = true
        )
        assertTrue("单本书长按进入分组后取消，selection 必须清空避免残留多选", singleDismissed.isEmpty())

        // 2. 多选工具栏点击进入分组，取消后 selection 必须保留
        val multiDismissed = ShelfGroupActions.computeDismissSelection(
            currentSelection = setOf(10L, 20L),
            isSingleBookAction = false
        )
        assertEquals("多选工具栏进入分组弹窗取消后，原多选选择集合应保留", setOf(10L, 20L), multiDismissed)

        // 3. 提交期间发生并发取消/清空 selection：因为提交前已捕获 targetBookIds 快照，
        // 挂起完成后仍能准确将快照中的书移入新分组，并返回准确提示，绝不出现虚假移入或丢失
        var currentSelection = setOf(101L, 102L)
        val targetSnapshot = currentSelection.toSet()

        val movedBooks = mutableListOf<Pair<Long, Long>>()
        val createStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val allowCreateFinish = kotlinx.coroutines.CompletableDeferred<Long>()

        val job = async {
            ShelfGroupActions.commitNewGroupAndMove(
                name = "科幻小说",
                targetBookIds = targetSnapshot,
                createGroup = {
                    createStarted.complete(Unit)
                    allowCreateFinish.await()
                },
                moveBookToGroup = { bId, gId ->
                    movedBooks.add(bId to gId)
                }
            )
        }

        // 等待创建分组开始执行
        createStarted.await()

        // 此时用户在 UI 上点击了取消或清空选择
        currentSelection = emptySet()

        // 释放分组创建完成，生成 groupId = 999L
        allowCreateFinish.complete(999L)
        val toastMsg = job.await()

        assertEquals("已创建分组「科幻小说」并移入", toastMsg)
        assertEquals(2, movedBooks.size)
        assertEquals(listOf(101L to 999L, 102L to 999L), movedBooks)

        // 4. 空选择集合时创建分组：仅提示已创建，不提示并移入
        val emptyToast = ShelfGroupActions.commitNewGroupAndMove(
            name = "空分组",
            targetBookIds = emptySet(),
            createGroup = { 888L },
            moveBookToGroup = { _, _ -> }
        )
        assertEquals("已创建分组「空分组」", emptyToast)
    }
}
