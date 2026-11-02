package com.mirad.reader.ui.shelf

import com.mirad.reader.data.db.entity.BookEntity
import com.mirad.reader.data.db.entity.BookFormat
import com.mirad.reader.data.db.entity.GroupEntity
import com.mirad.reader.ui.common.ActionItem

/**
 * 书架操作与提交协调；生产页面和内存预览使用同一条动作链。
 */
object ShelfGroupActions {

    internal fun bookActionItems(
        book: BookEntity,
        onOpen: () -> Unit,
        onUnhide: () -> Unit,
        onEdit: () -> Unit,
        onTogglePinned: () -> Unit,
        onMoveToGroup: () -> Unit,
        onHide: () -> Unit,
        onChapters: () -> Unit,
        onEncoding: () -> Unit,
        onDelete: () -> Unit,
        onMultiSelect: () -> Unit
    ): List<ActionItem> = buildList {
        add(ActionItem("继续阅读", onClick = onOpen))
        add(ActionItem("多选", onClick = onMultiSelect))
        if (book.isHidden) add(ActionItem("取消隐藏", onClick = onUnhide))
        add(ActionItem("编辑书名 / 作者", onClick = onEdit))
        add(ActionItem(if (book.isPinned) "取消置顶" else "置顶", onClick = onTogglePinned))
        add(ActionItem("移入分组", onClick = onMoveToGroup))
        if (!book.isHidden) add(ActionItem("在书架隐藏", hint = "可在分组栏「已隐藏」或设置中找回", onClick = onHide))
        if (book.format == BookFormat.TXT) {
            add(ActionItem("重新识别章节", onClick = onChapters))
            add(ActionItem("文字乱码？重新识别编码…", hint = "当前识别为 ${book.encoding ?: "未知"}", onClick = onEncoding))
        }
        add(ActionItem("删除这本书", danger = true, onClick = onDelete))
    }

    internal suspend fun commitPinSelected(books: List<BookEntity>, setPinned: suspend (Long, Boolean) -> Unit) {
        books.forEach { book -> setPinned(book.id, true) }
    }

    internal fun dismissDeleteSelection(selection: Set<Long>, isSingleBookAction: Boolean): Set<Long> =
        computeDismissSelection(selection, isSingleBookAction)

    internal suspend fun commitDeleteGroup(
        groupId: Long,
        currentSelectedGroupId: () -> Long?,
        deleteGroup: suspend (Long) -> Unit
    ): Long? {
        deleteGroup(groupId)
        // 删除可能挂起，完成时再读取筛选，不能用提交前的快照覆盖用户的后续选择。
        return currentSelectedGroupId().takeUnless { it == groupId }
    }

    internal fun groupActionItems(
        group: GroupEntity,
        onSelect: () -> Unit,
        onRename: () -> Unit,
        onDelete: () -> Unit
    ): List<ActionItem> = listOf(
        ActionItem("只看这个分组", onClick = onSelect),
        ActionItem("重命名", hint = "当前名称：${group.name}", onClick = onRename),
        ActionItem("删除分组", hint = "组内书籍会回到「未分组」", danger = true, onClick = onDelete)
    )

    /**
     * 单本或多选分组弹窗关闭时的选择集收敛规则：
     * 若从单本书长按菜单进入，关闭时必须清空 selection，避免遗留意外多选态；
     * 若从多选工具栏进入，关闭时保留用户原有 selection。
     */
    fun computeDismissSelection(currentSelection: Set<Long>, isSingleBookAction: Boolean): Set<Long> {
        return if (isSingleBookAction) emptySet() else currentSelection
    }

    /**
     * 新建分组并移入书籍：
     * 1. 提交前必须固定目标书籍集合快照 [targetBookIds]；
     * 2. 调用 [createGroup] 创建新分组；
     * 3. 只有当确实存在目标书籍时才执行移组并反馈“已创建并移入”，否则仅反馈“已创建”。
     */
    suspend fun commitNewGroupAndMove(
        name: String,
        targetBookIds: Set<Long>,
        createGroup: suspend (String) -> Long,
        moveBookToGroup: suspend (bookId: Long, groupId: Long) -> Unit
    ): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return ""
        val newGroupId = createGroup(trimmed)
        return if (targetBookIds.isNotEmpty()) {
            for (bookId in targetBookIds) {
                moveBookToGroup(bookId, newGroupId)
            }
            "已创建分组「$trimmed」并移入"
        } else {
            "已创建分组「$trimmed」"
        }
    }

    /**
     * 移入已有分组或移出分组。
     */
    suspend fun commitMoveToGroup(
        targetBookIds: Set<Long>,
        targetGroupId: Long?,
        targetGroupName: String?,
        moveBookToGroup: suspend (bookId: Long, groupId: Long?) -> Unit
    ): String {
        if (targetBookIds.isEmpty()) return ""
        for (bookId in targetBookIds) {
            moveBookToGroup(bookId, targetGroupId)
        }
        return if (targetGroupId != null) {
            "已移入分组「$targetGroupName」"
        } else {
            "已移出分组"
        }
    }
}
