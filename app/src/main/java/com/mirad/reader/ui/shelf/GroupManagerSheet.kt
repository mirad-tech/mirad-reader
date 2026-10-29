package com.mirad.reader.ui.shelf

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import com.mirad.reader.ui.common.BouncingLazyColumn
import androidx.compose.foundation.lazy.items
import com.mirad.reader.ui.common.MiradDialog as AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import com.mirad.reader.ui.common.MiradTextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.mirad.reader.AppContainer
import com.mirad.reader.data.db.entity.GroupEntity
import com.mirad.reader.ui.common.EmptyState
import com.mirad.reader.ui.common.ListRow
import com.mirad.reader.ui.common.RowDivider
import com.mirad.reader.ui.common.SheetScaffold
import com.mirad.reader.ui.design.Spacing
import kotlinx.coroutines.launch

/**
 * 分组管理：集中重命名与删除分组。
 *
 * 之前只能靠"长按书架上的分组胶囊"，功能存在但没人猜得到；这里给出可见入口，
 * 从书架的「⋯ → 分组管理」进入。
 */
@Composable
fun GroupManagerSheet(
    container: AppContainer,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val groups by container.groups.observeGroups().collectAsState(initial = emptyList())
    val shelf by container.books.observeShelf().collectAsState(initial = emptyList())
    GroupManagerContent(
        groups = groups,
        bookCounts = groups.associate { group -> group.id to shelf.count { it.book.groupId == group.id } },
        onRename = { group, name -> scope.launch { container.groups.rename(group, name) } },
        onDelete = { group -> scope.launch { container.groups.delete(group) } },
        onDismiss = onDismiss
    )
}

/** 生产页与隔离预览共用展示、弹窗和滚动行为；数据写入仍由原入口持有。 */
@Composable
internal fun GroupManagerContent(
    groups: List<GroupEntity>,
    bookCounts: Map<Long, Int>,
    onRename: (GroupEntity, String) -> Unit,
    onDelete: (GroupEntity) -> Unit,
    onDismiss: () -> Unit
) {
    var renameTarget by remember { mutableStateOf<GroupEntity?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<GroupEntity?>(null) }

    SheetScaffold(
        title = "分组管理",
        subtitle = if (groups.isEmpty()) "还没有分组" else "共 ${groups.size} 个分组",
        onDismiss = onDismiss,
        fillHeight = true
    ) {
        BouncingLazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
        if (groups.isEmpty()) {
            item {
                EmptyState(
                title = "还没有分组",
                description = "在书架上长按选中的书，选「移入分组」即可新建分组",
                modifier = Modifier.fillParentMaxWidth()
            )
            }
        } else {
            item {
            Text(
                text = "重命名或删除分组。删除分组不会删书，组内书籍会回到「未分组」。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.xl)
            )
            }
                items(groups, key = { it.id }) { group ->
                    val count = bookCounts[group.id] ?: 0
                    ListRow(
                        title = group.name,
                        meta = "$count 本",
                        trailing = {
                            Column {
                                MiradTextButton(onClick = {
                                    renameTarget = group
                                    renameDraft = group.name
                                }) { Text("重命名") }
                                MiradTextButton(danger = true, onClick = { deleteTarget = group }) { Text("删除") }
                            }
                        }
                    )
                    RowDivider()
                }
        }
        }
    }

    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名分组") },
            text = {
                OutlinedTextField(
                    value = renameDraft,
                    onValueChange = { renameDraft = it },
                    label = { Text("分组名称") },
                    singleLine = true
                )
            },
            confirmButton = {
                MiradTextButton(enabled = renameDraft.isNotBlank(), onClick = {
                    val name = renameDraft.trim()
                    renameTarget = null
                    if (name.isNotEmpty() && name != target.name) {
                        onRename(target, name)
                    }
                }) { Text("保存") }
            },
            dismissButton = { MiradTextButton(onClick = { renameTarget = null }) { Text("取消") } }
        )
    }

    deleteTarget?.let { target ->
        val count = bookCounts[target.id] ?: 0
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除分组「${target.name}」") },
            text = {
                Text(
                    if (count > 0) "组内 $count 本书会回到「未分组」，书本身不会被删除。"
                    else "这个分组是空的。"
                )
            },
            confirmButton = {
                MiradTextButton(danger = true, onClick = {
                    deleteTarget = null
                    onDelete(target)
                }) { Text("删除") }
            },
            dismissButton = { MiradTextButton(onClick = { deleteTarget = null }) { Text("取消") } }
        )
    }
}
