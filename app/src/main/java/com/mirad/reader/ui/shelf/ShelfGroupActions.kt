package com.mirad.reader.ui.shelf

/**
 * 书架分组操作与提交协调（生产单元，供 ShelfScreen 与 ShelfEntryTest 共享）。
 */
object ShelfGroupActions {

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
