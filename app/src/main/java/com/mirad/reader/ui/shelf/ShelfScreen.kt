package com.mirad.reader.ui.shelf

import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.CircularProgressIndicator
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
import com.mirad.reader.AppContainer
import com.mirad.reader.data.db.entity.BookEntity
import com.mirad.reader.data.imports.ImportSource
import com.mirad.reader.data.imports.ImportSources
import com.mirad.reader.ui.common.ActionSheet
import com.mirad.reader.ui.design.Sizes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    onOnlineSearch: () -> Unit = {},
    onSourceManager: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val shelfFlow = remember(container) { container.books.observeShelf() }
    val loadedShelf by shelfFlow.collectAsState(initial = null)
    val shelf = loadedShelf
    // 等到真实数据首帧再挂载列表，避免空集合把恢复的滚动索引裁成0。
    if (shelf == null) {
        com.mirad.reader.ui.common.MiradScaffold(topBar = {
            com.mirad.reader.ui.common.ScreenHeader("书架")
        }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        return
    }
    val groups by container.groups.observeGroups().collectAsState(initial = emptyList())
    val importState by container.importer.state.collectAsState()
    val summary by container.importer.lastSummary.collectAsState()

    var sort by rememberSaveable { mutableStateOf(ShelfSort.RECENT) }
    // 网格/列表选择持久化：离开书架再回来保持用户上次的选择（此前每次都重置为网格）
    val shelfLayoutGrid by container.settings.shelfLayoutGrid.collectAsState(initial = true)
    val layout = if (shelfLayoutGrid) ShelfLayout.GRID else ShelfLayout.LIST
    var groupFilter by rememberSaveable { mutableStateOf<Long?>(null) }
    // 旧版「未分组」胶囊已移除，恢复时视为未筛选。
    val selectedGroup = groupFilter?.takeUnless { it == ShelfQuery.GROUP_NONE }
    var query by rememberSaveable { mutableStateOf("") }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var selection by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var groupDialog by remember { mutableStateOf(false) }
    var isSingleBookGroupAction by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<BookEntity?>(null) }
    var editTitle by remember { mutableStateOf("") }
    var editAuthor by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    var isSingleBookDeleteAction by remember { mutableStateOf(false) }
    var actionBook by remember { mutableStateOf<BookEntity?>(null) }
    var encodingBook by remember { mutableStateOf<BookEntity?>(null) }
    var chapterBook by remember { mutableStateOf<BookEntity?>(null) }
    var relinkTarget by remember { mutableStateOf<BookEntity?>(null) }
    var showGroupManager by remember { mutableStateOf(false) }
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
                    ImportSources.fromTreeUri(context, uri, ::isSupported)
                }
                if (sources.isEmpty()) toast = "所选文件夹里没有 txt / epub" else container.importer.import(sources)
            }
        }
    }
    val relinkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = relinkTarget
        relinkTarget = null
        if (uri != null && target != null) {
            scope.launch {
                try {
                    com.mirad.reader.data.io.BookContentAccess.tryTakePersistableReadPermission(context, uri)
                    val uriText = uri.toString()
                    val displayName = ImportSources.nameOf(context, uri)
                    val saved = container.maintenance.relinkSource(target.id, uriText, uriText, displayName)
                    toast = "已重新关联《${saved.title}》"
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    toast = e.message ?: "重新关联失败"
                }
            }
        }
    }

    val visible = remember(shelf, sort, selectedGroup, query) {
        ShelfQuery.apply(
            all = shelf,
            sort = sort,
            groupId = selectedGroup,
            query = query
        )
    }

    ShelfContent(
        resumePrompt = container.shelfResumePrompt,
        state = ShelfContentState(
            shelf = shelf, visible = visible, sort = sort, layout = layout,
            selectedGroup = selectedGroup, selectedGroupName = groups.firstOrNull { it.id == selectedGroup }?.name,
            query = query,
            showSearch = showSearch, selection = selection, importItems = importState.items,
            importing = importState.running
        ),
        actions = ShelfContentActions(
            onOpenBook = onOpenBook, onStats = onOpenStats, onSettings = onOpenSettings,
            onNotes = onOpenNotes,
            onOnlineSearch = onOnlineSearch, onSourceManager = onSourceManager,
            onToggleSearch = { showSearch = !showSearch; if (!showSearch) query = "" },
            onQueryChange = { query = it }, onSortChange = { sort = it },
            onLayoutChange = { container.settings.queueShelfLayout(it == ShelfLayout.GRID, container.appScope) },
            onClearGroupFilter = { groupFilter = null },
            onSelectionChange = { selection = it }, onBookAction = { actionBook = it },
            onManageGroups = { showGroupManager = true },
            onSelectAll = { selection = visible.map { it.book.id }.toSet() },
            onPinSelected = { scope.launch {
                ShelfGroupActions.commitPinSelected(selection.mapNotNull { id -> shelf.firstOrNull { it.book.id == id }?.book }) { id, pinned ->
                    container.books.setPinned(id, pinned)
                }
                selection = emptySet()
            } },
            onGroupSelected = { isSingleBookGroupAction = false; groupDialog = true },
            onDeleteSelected = { isSingleBookDeleteAction = false; confirmDelete = true },
            onImportFile = { filePicker.launch(arrayOf("*/*")) },
            onImportFolder = { folderPicker.launch(null) },
            onImportUrl = { showUrlImportDialog = true }
        ),
        snackbarHost = { com.mirad.reader.ui.common.FeedbackHost(feedback, Modifier.imePadding()) }
    )

    // ---------------- 弹层 ----------------

    actionBook?.let { selectedBook ->
        val entry = shelf.firstOrNull { it.book.id == selectedBook.id }
        val book = entry?.book ?: selectedBook
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
                            text = "阅读进度 ${((entry?.percent ?: 0f) * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "上次阅读 ${formatRelative(entry?.lastReadAt ?: 0L)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            items = ShelfGroupActions.bookActionItems(
                book = book,
                onOpen = { onOpenBook(book.id) },
                onEdit = {
                    editing = book
                    editTitle = book.title
                    editAuthor = book.author.orEmpty()
                },
                onTogglePinned = {
                    scope.launch { container.books.setPinned(book.id, !book.isPinned) }
                },
                onSetFinished = { finished -> container.queueBookFinished(book.id, finished) },
                onMoveToGroup = {
                    selection = setOf(book.id)
                    isSingleBookGroupAction = true
                    groupDialog = true
                },
                onHide = {
                    scope.launch { container.books.setHidden(book.id, true); toast = "已在书架隐藏" }
                },
                onChapters = { chapterBook = book },
                onEncoding = { encodingBook = book },
                onDelete = { selection = setOf(book.id); isSingleBookDeleteAction = true; confirmDelete = true },
                onMultiSelect = { selection = setOf(book.id) },
                onRelink = {
                    relinkTarget = book
                    val mime = if (book.format == com.mirad.reader.data.db.entity.BookFormat.EPUB) {
                        arrayOf("application/epub+zip", "application/octet-stream", "*/*")
                    } else {
                        arrayOf("text/plain", "*/*")
                    }
                    relinkPicker.launch(mime)
                }
            )
        )
    }

    if (showGroupManager) {
        GroupManagerSheet(container = container, onDismiss = { showGroupManager = false },
            currentSelectedGroupId = { selectedGroup },
            onSelectedGroupChange = { groupFilter = it },
            onOpenGroup = { groupFilter = it.id; showGroupManager = false })
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



    if (showUrlImportDialog) {
        val downloader = container.networkDownloader
        UrlImportDialog(
            progress = downloader.progress,
            downloadAndImport = downloader::downloadAndImport,
            resetDownload = downloader::resetState,
            onDismiss = { showUrlImportDialog = false },
            onSuccess = { bookTitle ->
                if (container.importer.lastSummary.value == null) {
                    toast = "已将《$bookTitle》加入书架"
                }
            }
        )
    }

    val pageActive = com.mirad.reader.ui.common.LocalPagerPageActive.current
    if (pageActive && summary != null && !showUrlImportDialog && !groupDialog && editing == null &&
        encodingBook == null && chapterBook == null && !confirmDelete && actionBook == null &&
        !showGroupManager) {
        val result = summary!!
        ImportResultDialog(
            result,
            onPreview = container.importer::previewUpdate,
            onUpdate = container.importer::confirmUpdate,
            onResolveWithoutUpdate = container.importer::resolveWithoutUpdate,
            onDismiss = { container.importer.clearSummary(result.batchId) }
        )
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

    if (confirmDelete) {
        val dismissDelete = {
            selection = ShelfGroupActions.dismissDeleteSelection(selection, isSingleBookDeleteAction)
            isSingleBookDeleteAction = false
            confirmDelete = false
        }
        val targetBook = if (selection.size == 1) shelf.firstOrNull { it.book.id in selection }?.book else null
        val targetTitle = targetBook?.let { "《${it.title}》" }
        AlertDialog(
            onDismissRequest = dismissDelete,
            title = { Text(if (targetTitle != null) "删除$targetTitle" else "删除 ${selection.size} 本书籍") },
            text = { Text("将删除${if (targetTitle != null) targetTitle else "所选 ${selection.size} 本书"}及其阅读记录、书签与笔记，无法恢复。") },
            confirmButton = {
                MiradTextButton(danger = true, onClick = {
                    scope.launch {
                        val count = selection.size
                        val name = targetBook?.title
                        selection.forEach { id -> container.books.getBook(id)?.let { container.books.deleteBook(it) } }
                        selection = emptySet(); confirmDelete = false; isSingleBookDeleteAction = false
                        toast = if (name != null) "已删除《$name》" else "已删除 $count 本书籍"
                    }
                }) { Text("删除") }
            },
            dismissButton = { MiradTextButton(onClick = dismissDelete) { Text("取消") } }
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
