package com.mirad.reader.ui.shelf

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import com.mirad.reader.ui.common.MiradDialog as AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import com.mirad.reader.ui.common.MiradTextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.mirad.reader.AppContainer
import com.mirad.reader.data.db.entity.BookEntity
import com.mirad.reader.data.imports.ImportSource
import com.mirad.reader.data.imports.ImportSources
import com.mirad.reader.ui.common.ActionItem
import com.mirad.reader.ui.common.ActionSheet
import com.mirad.reader.ui.design.Sizes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class ShelfSort(val label: String) {
    RECENT("最近阅读"),
    ADDED("添加时间"),
    TITLE("按书名"),
    AUTHOR("按作者"),
    SIZE("按大小")
}

internal enum class ShelfLayout { GRID, LIST }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ShelfScreen(
    container: AppContainer,
    onOpenBook: (Long) -> Unit,
    onOpenStats: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenNotes: () -> Unit = {},
    onOpenHidden: () -> Unit = {},
    onOnlineSearch: () -> Unit = {},
    onSourceManager: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val shelf by container.books.observeShelf().collectAsState(initial = emptyList())
    val groups by container.groups.observeGroups().collectAsState(initial = emptyList())
    val importState by container.importer.state.collectAsState()
    val summary by container.importer.lastSummary.collectAsState()

    var sort by remember { mutableStateOf(ShelfSort.RECENT) }
    // 网格/列表选择持久化：离开书架再回来保持用户上次的选择（此前每次都重置为网格）
    val shelfLayoutGrid by container.settings.shelfLayoutGrid.collectAsState(initial = true)
    val layout = if (shelfLayoutGrid) ShelfLayout.GRID else ShelfLayout.LIST
    var selectedGroup by remember { mutableStateOf<Long?>(null) }
    var showHidden by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var groupDialog by remember { mutableStateOf(false) }
    var isSingleBookGroupAction by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<BookEntity?>(null) }
    var editTitle by remember { mutableStateOf("") }
    var editAuthor by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    var actionBook by remember { mutableStateOf<BookEntity?>(null) }
    var encodingBook by remember { mutableStateOf<BookEntity?>(null) }
    var chapterBook by remember { mutableStateOf<BookEntity?>(null) }
    var showGroupManager by remember { mutableStateOf(false) }
    var groupAction by remember { mutableStateOf<com.mirad.reader.data.db.entity.GroupEntity?>(null) }
    var groupRenameTarget by remember { mutableStateOf<com.mirad.reader.data.db.entity.GroupEntity?>(null) }
    var groupRenameDraft by remember { mutableStateOf("") }
    var groupDeleteTarget by remember { mutableStateOf<com.mirad.reader.data.db.entity.GroupEntity?>(null) }
    val feedback = com.mirad.reader.ui.common.rememberFeedbackState("shelf")
    var toast by feedback
    var showUrlImportDialog by remember { mutableStateOf(false) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            container.importer.import(ImportSources.fromUris(context, uris))
        }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            scope.launch {
                val sources = withContext(Dispatchers.IO) {
                    runCatching {
                        DocumentFile.fromTreeUri(context, uri)?.listFiles()
                            ?.filter { it.isFile && it.name != null && isSupported(it.name!!) }
                            ?.map { ImportSource.FromUri(it.uri, it.name!!) }
                            ?: emptyList()
                    }.getOrDefault(emptyList())
                }
                if (sources.isEmpty()) toast = "所选文件夹里没有 txt / epub" else container.importer.import(sources)
            }
        }
    }

    val visible = remember(shelf, sort, selectedGroup, query, showHidden) {
        ShelfQuery.apply(
            all = shelf,
            sort = sort,
            groupId = selectedGroup,
            query = query,
            includeHidden = false,
            onlyHidden = showHidden
        )
    }

    ShelfContent(
        state = ShelfContentState(
            shelf = shelf, visible = visible, groups = groups, sort = sort, layout = layout,
            selectedGroup = selectedGroup, showHidden = showHidden, query = query,
            showSearch = showSearch, selection = selection, importItems = importState.items,
            importing = importState.running
        ),
        actions = ShelfContentActions(
            onOpenBook = onOpenBook, onStats = onOpenStats, onSettings = onOpenSettings,
            onNotes = onOpenNotes, onHidden = onOpenHidden,
            onOnlineSearch = onOnlineSearch, onSourceManager = onSourceManager,
            onToggleSearch = { showSearch = !showSearch; if (!showSearch) query = "" },
            onQueryChange = { query = it }, onSortChange = { sort = it },
            onLayoutChange = { container.settings.queueShelfLayout(it == ShelfLayout.GRID, container.appScope) },
            onSelectGroup = { id, hidden -> selectedGroup = id; showHidden = hidden },
            onSelectionChange = { selection = it }, onBookAction = { actionBook = it },
            onGroupAction = { groupAction = it }, onManageGroups = { showGroupManager = true },
            onSelectAll = { selection = visible.map { it.book.id }.toSet() },
            onPinSelected = { scope.launch {
                selection.forEach { id ->
                    val pinned = shelf.firstOrNull { it.book.id == id }?.book?.isPinned == true
                    container.books.setPinned(id, !pinned)
                }
                selection = emptySet()
            } },
            onGroupSelected = { isSingleBookGroupAction = false; groupDialog = true }, onDeleteSelected = { confirmDelete = true },
            onImportFile = { filePicker.launch(arrayOf("*/*")) },
            onImportFolder = { folderPicker.launch(null) },
            onImportUrl = { showUrlImportDialog = true },
            onImportSamples = { scope.launch {
                val sources = withContext(Dispatchers.IO) { scanSamples(container) }
                if (sources.isNotEmpty()) container.importer.import(sources)
                else toast = "未找到调试样书"
            } }
        ),
        snackbarHost = { com.mirad.reader.ui.common.FeedbackHost(feedback, Modifier.imePadding()) }
    )

    // ---------------- 弹层 ----------------

    actionBook?.let { book ->
        val isHidden = book.isHidden
        ActionSheet(
            title = book.title,
            subtitle = "${book.author ?: "未知作者"} · ${book.totalChapters} 章 · ${book.format}",
            onDismiss = { actionBook = null },
            header = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BookCover(
                        book = book,
                        modifier = Modifier.size(Sizes.coverSmallWidth, Sizes.coverSmallHeight)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "阅读进度 ${(shelf.firstOrNull { it.book.id == book.id }?.percent ?: 0f).let { (it * 100).toInt() }}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = if (isHidden) "已在书架隐藏" else "上次阅读 ${formatRelative(shelf.firstOrNull { it.book.id == book.id }?.lastReadAt ?: 0L)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            items = buildList {
                add(ActionItem("继续阅读") { onOpenBook(book.id) })
                if (isHidden) {
                    add(ActionItem("取消隐藏") {
                        scope.launch { container.books.setHidden(book.id, false); toast = "已恢复《${book.title}》到书架" }
                    })
                }
                add(ActionItem("编辑书名 / 作者") {
                    editing = book
                    editTitle = book.title
                    editAuthor = book.author.orEmpty()
                })
                add(ActionItem(if (book.isPinned) "取消置顶" else "置顶") {
                    scope.launch { container.books.setPinned(book.id, !book.isPinned) }
                })
                add(ActionItem("移入分组") {
                    selection = setOf(book.id)
                    isSingleBookGroupAction = true
                    groupDialog = true
                })
                if (!isHidden) {
                    add(ActionItem("在书架隐藏", hint = "可在分组栏「已隐藏」或设置中找回") {
                        scope.launch { container.books.setHidden(book.id, true); toast = "已隐藏《${book.title}》，可在「已隐藏」中找回" }
                    })
                }
                if (book.format == com.mirad.reader.data.db.entity.BookFormat.TXT) {
                    add(ActionItem("重新识别章节") { chapterBook = book })
                    add(ActionItem("文字乱码？重新识别编码…", hint = "当前识别为 ${book.encoding ?: "未知"}") {
                        encodingBook = book
                    })
                }
                add(ActionItem("删除这本书", danger = true) { selection = setOf(book.id); confirmDelete = true })
            }
        )
    }

    if (showGroupManager) {
        GroupManagerSheet(container = container, onDismiss = { showGroupManager = false })
    }

    chapterBook?.let { book ->
        ChapterPreviewSheet(
            container = container,
            book = book,
            onDismiss = { chapterBook = null },
            onApplied = { count -> chapterBook = null; toast = "已更新目录，共 $count 节" }
        )
    }

    encodingBook?.let { book ->
        EncodingDialog(
            container = container,
            book = book,
            onDismiss = { encodingBook = null },
            onApplied = { message ->
                encodingBook = null
                toast = message
            }
        )
    }

    groupAction?.let { group ->
        ActionSheet(
            title = group.name,
            subtitle = "分组 · ${shelf.count { it.book.groupId == group.id }} 本",
            onDismiss = { groupAction = null },
            items = listOf(
                ActionItem("只看这个分组") { selectedGroup = group.id; showHidden = false },
                ActionItem("重命名", hint = "当前名称：${group.name}") {
                    groupRenameTarget = group
                    groupRenameDraft = group.name
                },
                ActionItem("删除分组", hint = "组内书籍会回到「未分组」", danger = true) {
                    groupAction = null
                    groupDeleteTarget = group
                }
            )
        )
    }



    if (showUrlImportDialog) {
        UrlImportDialog(
            container = container,
            onDismiss = { showUrlImportDialog = false },
            onSuccess = { bookTitle ->
                toast = "正在将《$bookTitle》加入书架"
            }
        )
    }

    if (summary != null && !showUrlImportDialog && !groupDialog && editing == null &&
        encodingBook == null && chapterBook == null && !confirmDelete && actionBook == null &&
        !showGroupManager && groupRenameTarget == null && groupAction == null && groupDeleteTarget == null) {
        val result = summary!!
        ImportResultDialog(result) { container.importer.clearSummary(result.batchId) }
    }

    if (groupDialog) {
        var nameDraft by remember { mutableStateOf("") }
        val dismissGroupDialog = {
            groupDialog = false
            selection = ShelfGroupActions.computeDismissSelection(selection, isSingleBookGroupAction)
            isSingleBookGroupAction = false
        }
        AlertDialog(
            onDismissRequest = dismissGroupDialog,
            title = { Text("移入分组") },
            text = {
                Column {
                    MiradTextButton(onClick = {
                        val targetBookIds = selection.toSet()
                        selection = emptySet()
                        isSingleBookGroupAction = false
                        groupDialog = false
                        scope.launch {
                            val msg = ShelfGroupActions.commitMoveToGroup(
                                targetBookIds = targetBookIds,
                                targetGroupId = null,
                                targetGroupName = null,
                                moveBookToGroup = { bId, gId -> container.books.setGroup(bId, gId) }
                            )
                            if (msg.isNotEmpty()) toast = msg
                        }
                    }) { Text("移出分组") }
                    groups.forEach { group ->
                        MiradTextButton(onClick = {
                            val targetBookIds = selection.toSet()
                            selection = emptySet()
                            isSingleBookGroupAction = false
                            groupDialog = false
                            scope.launch {
                                val msg = ShelfGroupActions.commitMoveToGroup(
                                    targetBookIds = targetBookIds,
                                    targetGroupId = group.id,
                                    targetGroupName = group.name,
                                    moveBookToGroup = { bId, gId -> container.books.setGroup(bId, gId) }
                                )
                                if (msg.isNotEmpty()) toast = msg
                            }
                        }) { Text(group.name) }
                    }
                    OutlinedTextField(
                        value = nameDraft,
                        onValueChange = { nameDraft = it },
                        label = { Text("新建分组") },
                        singleLine = true,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            },
            confirmButton = {
                MiradTextButton(enabled = nameDraft.isNotBlank(), onClick = {
                    val name = nameDraft.trim()
                    if (name.isEmpty()) return@MiradTextButton
                    val targetBookIds = selection.toSet()
                    selection = emptySet()
                    isSingleBookGroupAction = false
                    groupDialog = false
                    scope.launch {
                        val msg = ShelfGroupActions.commitNewGroupAndMove(
                            name = name,
                            targetBookIds = targetBookIds,
                            createGroup = { container.groups.create(it) },
                            moveBookToGroup = { bId, gId -> container.books.setGroup(bId, gId) }
                        )
                        if (msg.isNotEmpty()) toast = msg
                    }
                }) { Text("新建并移入") }
            },
            dismissButton = { MiradTextButton(onClick = dismissGroupDialog) { Text("取消") } }
        )
    }

    groupDeleteTarget?.let { target ->
        val count = shelf.count { it.book.groupId == target.id }
        AlertDialog(
            onDismissRequest = { groupDeleteTarget = null },
            title = { Text("删除分组「${target.name}」") },
            text = {
                Text(
                    if (count > 0) "组内 $count 本书会回到「未分组」，书籍本身不会被删除。"
                    else "这个分组是空的。"
                )
            },
            confirmButton = {
                MiradTextButton(danger = true, onClick = {
                    scope.launch {
                        container.groups.delete(target)
                        if (selectedGroup == target.id) selectedGroup = null
                        groupDeleteTarget = null
                        toast = "已删除分组「${target.name}」"
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { MiradTextButton(onClick = { groupDeleteTarget = null }) { Text("取消") } }
        )
    }

    if (groupRenameTarget != null) {
        val target = groupRenameTarget!!
        AlertDialog(
            onDismissRequest = { groupRenameTarget = null },
            title = { Text("重命名分组「${target.name}」") },
            text = {
                OutlinedTextField(
                    value = groupRenameDraft,
                    onValueChange = { groupRenameDraft = it },
                    label = { Text("分组名称") },
                    singleLine = true
                )
            },
            confirmButton = {
                MiradTextButton(enabled = groupRenameDraft.isNotBlank(), onClick = {
                    val name = groupRenameDraft.trim()
                    groupRenameTarget = null
                    if (name.isNotEmpty() && name != target.name) {
                        scope.launch {
                            container.groups.rename(target, name)
                            toast = "已重命名为「$name」"
                        }
                    }
                }) { Text("保存") }
            },
            dismissButton = { MiradTextButton(onClick = { groupRenameTarget = null }) { Text("取消") } }
        )
    }

    if (confirmDelete) {
        val targetBook = if (selection.size == 1) shelf.firstOrNull { it.book.id in selection }?.book else null
        val targetTitle = targetBook?.let { "《${it.title}》" }
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(if (targetTitle != null) "删除$targetTitle" else "删除 ${selection.size} 本书籍") },
            text = { Text("将删除${if (targetTitle != null) targetTitle else "所选 ${selection.size} 本书"}及其阅读记录、书签与笔记，无法恢复。") },
            confirmButton = {
                MiradTextButton(danger = true, onClick = {
                    scope.launch {
                        val count = selection.size
                        val name = targetBook?.title
                        selection.forEach { id -> container.books.getBook(id)?.let { container.books.deleteBook(it) } }
                        selection = emptySet(); confirmDelete = false
                        toast = if (name != null) "已删除《$name》" else "已删除 $count 本书籍"
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { MiradTextButton(onClick = { confirmDelete = false }) { Text("取消") } }
        )
    }

    editing?.let { book ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("编辑《${book.title}》") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = editTitle,
                        onValueChange = { editTitle = it },
                        label = { Text("书名") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = editAuthor,
                        onValueChange = { editAuthor = it },
                        label = { Text("作者") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                MiradTextButton(onClick = {
                    scope.launch {
                        container.books.updateMeta(
                            book.id,
                            editTitle.trim().ifBlank { book.title },
                            editAuthor.trim().ifBlank { null }
                        )
                        editing = null
                        toast = "已保存修改"
                    }
                }) { Text("保存") }
            },
            dismissButton = { MiradTextButton(onClick = { editing = null }) { Text("取消") } }
        )
    }
}

private fun isSupported(name: String): Boolean {
    val lower = name.lowercase()
    return lower.endsWith(".txt") || lower.endsWith(".epub")
}

private fun scanSamples(container: AppContainer): List<ImportSource> {
    val candidates = listOf(container.paths.samplesDir, File(container.paths.root, "samples"))
    for (dir in candidates) {
        val files = dir.listFiles()?.filter { it.isFile && isSupported(it.name) }.orEmpty()
        if (files.isNotEmpty()) {
            return files.map { ImportSource.FromFile(it) }
        }
    }
    return emptyList()
}
