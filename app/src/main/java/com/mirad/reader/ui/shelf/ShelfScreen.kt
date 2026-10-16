package com.mirad.reader.ui.shelf

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.mirad.reader.AppContainer
import com.mirad.reader.BuildConfig
import com.mirad.reader.data.db.entity.BookEntity
import com.mirad.reader.data.imports.ImportItem
import com.mirad.reader.data.imports.ImportSource
import com.mirad.reader.data.imports.ImportSources
import com.mirad.reader.data.imports.ImportStage
import com.mirad.reader.data.repo.ShelfBook
import com.mirad.reader.ui.common.ActionItem
import com.mirad.reader.ui.common.ActionSheet
import com.mirad.reader.ui.common.ChoiceChip
import com.mirad.reader.ui.common.EmptyState
import com.mirad.reader.ui.common.ScreenHeader
import com.mirad.reader.ui.design.LocalMiradColors
import com.mirad.reader.ui.design.Sizes
import com.mirad.reader.ui.design.Spacing
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

private enum class ShelfLayout { GRID, LIST }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ShelfScreen(
    container: AppContainer,
    onOpenBook: (Long) -> Unit,
    onOpenStats: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenNotes: () -> Unit = {},
    onOpenHidden: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val shelf by container.books.observeShelf().collectAsState(initial = emptyList())
    val groups by container.groups.observeGroups().collectAsState(initial = emptyList())
    val importState by container.importer.state.collectAsState()
    val summary by container.importer.lastSummary.collectAsState()

    var sort by remember { mutableStateOf(ShelfSort.RECENT) }
    var layout by remember { mutableStateOf(ShelfLayout.GRID) }
    var selectedGroup by remember { mutableStateOf<Long?>(null) }
    var showHidden by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var sortMenu by remember { mutableStateOf(false) }
    var importMenu by remember { mutableStateOf(false) }
    var groupDialog by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<BookEntity?>(null) }
    var editTitle by remember { mutableStateOf("") }
    var editAuthor by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    var actionBook by remember { mutableStateOf<BookEntity?>(null) }
    var groupAction by remember { mutableStateOf<com.mirad.reader.data.db.entity.GroupEntity?>(null) }
    var groupRenameTarget by remember { mutableStateOf<com.mirad.reader.data.db.entity.GroupEntity?>(null) }
    var groupRenameDraft by remember { mutableStateOf("") }
    var toast by remember { mutableStateOf<String?>(null) }

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
            includeHidden = showHidden
        )
    }
    val hiddenCount = remember(shelf) { shelf.count { it.book.isHidden } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ShelfHeader(
                total = shelf.count { !it.book.isHidden },
                selectionCount = selection.size,
                searchVisible = showSearch,
                onToggleSearch = { showSearch = !showSearch; if (!showSearch) query = "" },
                onStats = onOpenStats,
                onSettings = onOpenSettings,
                onNotes = onOpenNotes,
                onHidden = onOpenHidden,
                onExitSelection = { selection = emptySet() },
                onPinSelected = {
                    scope.launch {
                        selection.forEach { id ->
                            val pinned = shelf.firstOrNull { it.book.id == id }?.book?.isPinned == true
                            container.books.setPinned(id, !pinned)
                        }
                        selection = emptySet()
                    }
                },
                onGroupSelected = { groupDialog = true },
                onDeleteSelected = { confirmDelete = true }
            )
        },
        floatingActionButton = {
            if (selection.isEmpty()) {
                Box {
                    ExtendedFloatingActionButton(
                        onClick = { importMenu = true },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        shape = RoundedCornerShape(18.dp),
                        elevation = FloatingActionButtonDefaults.elevation(
                            defaultElevation = 2.dp,
                            pressedElevation = 4.dp
                        ),
                        icon = { Icon(Icons.Default.Add, contentDescription = null) },
                        text = { Text("导入") }
                    )
                    DropdownMenu(expanded = importMenu, onDismissRequest = { importMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("导入文件（txt / epub）") },
                            onClick = { importMenu = false; filePicker.launch(arrayOf("*/*")) }
                        )
                        DropdownMenuItem(
                            text = { Text("导入文件夹") },
                            onClick = { importMenu = false; folderPicker.launch(null) }
                        )
                        if (BuildConfig.DEBUG) {
                            DropdownMenuItem(
                                text = { Text("导入调试样书（samples 目录）") },
                                onClick = {
                                    importMenu = false
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) { scanSamples(container) }
                                        if (result.sources.isNotEmpty()) container.importer.import(result.sources)
                                        else toast = "未找到样书：${result.directory}"
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (showSearch) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("搜索书名或作者") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            if (groups.isNotEmpty() || hiddenCount > 0) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        ChoiceChip(
                            text = "全部",
                            selected = selectedGroup == null && !showHidden,
                            onClick = { selectedGroup = null; showHidden = false }
                        )
                    }
                    item {
                        ChoiceChip(
                            text = "未分组",
                            selected = selectedGroup == ShelfQuery.GROUP_NONE && !showHidden,
                            onClick = { selectedGroup = ShelfQuery.GROUP_NONE; showHidden = false }
                        )
                    }
                    items(groups, key = { it.id }) { group ->
                        ChoiceChip(
                            text = group.name,
                            selected = selectedGroup == group.id && !showHidden,
                            onClick = { selectedGroup = group.id; showHidden = false },
                            onLongClick = { groupAction = group }
                        )
                    }
                    if (hiddenCount > 0) {
                        item {
                            ChoiceChip(
                                text = "已隐藏 $hiddenCount",
                                selected = showHidden,
                                onClick = { selectedGroup = null; showHidden = true }
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box {
                    TextButton(onClick = { sortMenu = true }) {
                        Text("${sort.label}  ▾", style = MaterialTheme.typography.labelLarge)
                    }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        ShelfSort.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                onClick = { sort = option; sortMenu = false },
                                trailingIcon = {
                                    if (option == sort) {
                                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                    }
                                }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    layout = if (layout == ShelfLayout.GRID) ShelfLayout.LIST else ShelfLayout.GRID
                }) {
                    Text(
                        text = if (layout == ShelfLayout.GRID) "网格" else "列表",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }

            when {
                visible.isEmpty() -> EmptyShelf(
                    searching = query.isNotBlank() || selectedGroup != null,
                    onImport = { filePicker.launch(arrayOf("*/*")) }
                )
                layout == ShelfLayout.GRID -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 112.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(visible, key = { it.book.id }) { item ->
                        BookGridCard(
                            item = item,
                            selected = item.book.id in selection,
                            selectionMode = selection.isNotEmpty(),
                            onClick = {
                                if (selection.isEmpty()) onOpenBook(item.book.id)
                                else selection = toggle(selection, item.book.id)
                            },
                            onLongClick = {
                                if (selection.isEmpty()) actionBook = item.book
                                else selection = toggle(selection, item.book.id)
                            }
                        )
                    }
                }
                else -> LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(visible, key = { it.book.id }) { item ->
                        BookListRow(
                            item = item,
                            selected = item.book.id in selection,
                            onClick = {
                                if (selection.isEmpty()) onOpenBook(item.book.id)
                                else selection = toggle(selection, item.book.id)
                            },
                            onLongClick = {
                                if (selection.isEmpty()) actionBook = item.book
                                else selection = toggle(selection, item.book.id)
                            }
                        )
                    }
                }
            }

            ImportBar(importState.items, importState.running)
        }
    }

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
                        scope.launch { container.books.setHidden(book.id, false); toast = "已恢复到书架" }
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
                if (groups.isNotEmpty()) {
                    add(ActionItem("移入分组") { selection = setOf(book.id); groupDialog = true })
                }
                if (!isHidden) {
                    add(ActionItem("在书架隐藏", hint = "隐藏后可从分组行的「已隐藏」里找回") {
                        scope.launch { container.books.setHidden(book.id, true); toast = "已隐藏，可从分组行的「已隐藏」找回" }
                    })
                }
                add(ActionItem("删除这本书", danger = true) { selection = setOf(book.id); confirmDelete = true })
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
                    scope.launch {
                        container.groups.delete(group)
                        if (selectedGroup == group.id) selectedGroup = null
                        toast = "已删除分组「${group.name}」"
                    }
                }
            )
        )
    }

    toast?.let { message ->
        ShelfToast(message = message, onDismiss = { toast = null })
    }

    if (summary != null) {
        val result = summary!!
        AlertDialog(
            onDismissRequest = { container.importer.clearSummary() },
            title = { Text("导入完成") },
            text = {
                Column {
                    Text(
                        text = "成功 ${result.success} 本 · 跳过 ${result.skipped} 本 · 失败 ${result.failed} 本",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    result.messages.take(6).forEach { message ->
                        Text(
                            text = "· $message",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { container.importer.clearSummary() }) { Text("好") }
            }
        )
    }

    if (groupDialog) {
        var nameDraft by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { groupDialog = false },
            title = { Text("移入分组") },
            text = {
                Column {
                    TextButton(onClick = {
                        scope.launch {
                            selection.forEach { container.books.setGroup(it, null) }
                            selection = emptySet(); groupDialog = false
                        }
                    }) { Text("移出分组") }
                    groups.forEach { group ->
                        TextButton(onClick = {
                            scope.launch {
                                selection.forEach { container.books.setGroup(it, group.id) }
                                selection = emptySet(); groupDialog = false
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
                TextButton(onClick = {
                    val name = nameDraft.trim()
                    if (name.isEmpty()) { groupDialog = false; return@TextButton }
                    scope.launch {
                        val id = container.groups.create(name)
                        selection.forEach { container.books.setGroup(it, id) }
                        selection = emptySet(); groupDialog = false
                    }
                }) { Text("新建并移入") }
            },
            dismissButton = { TextButton(onClick = { groupDialog = false }) { Text("取消") } }
        )
    }

    if (groupRenameTarget != null) {
        val target = groupRenameTarget!!
        AlertDialog(
            onDismissRequest = { groupRenameTarget = null },
            title = { Text("重命名分组") },
            text = {
                OutlinedTextField(
                    value = groupRenameDraft,
                    onValueChange = { groupRenameDraft = it },
                    label = { Text("分组名称") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = groupRenameDraft.trim()
                    groupRenameTarget = null
                    if (name.isNotEmpty() && name != target.name) {
                        scope.launch { container.groups.rename(target, name) }
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { groupRenameTarget = null }) { Text("取消") } }
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除书籍") },
            text = { Text("将删除所选 ${selection.size} 本书及其阅读记录、书签与笔记，无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        selection.forEach { id -> container.books.getBook(id)?.let { container.books.deleteBook(it) } }
                        selection = emptySet(); confirmDelete = false
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } }
        )
    }

    editing?.let { book ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("编辑书籍信息") },
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
                TextButton(onClick = {
                    scope.launch {
                        container.books.updateMeta(
                            book.id,
                            editTitle.trim().ifBlank { book.title },
                            editAuthor.trim().ifBlank { null }
                        )
                        editing = null
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } }
        )
    }
}

// ---------------------------------------------------------------- 顶栏

@Composable
private fun ShelfHeader(
    total: Int,
    selectionCount: Int,
    searchVisible: Boolean,
    onToggleSearch: () -> Unit,
    onStats: () -> Unit,
    onSettings: () -> Unit,
    onNotes: () -> Unit,
    onHidden: () -> Unit,
    onExitSelection: () -> Unit,
    onPinSelected: () -> Unit,
    onGroupSelected: () -> Unit,
    onDeleteSelected: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    if (selectionCount > 0) {
        ScreenHeader(
            title = "已选 $selectionCount 本",
            onBack = onExitSelection,
            actions = {
                IconButton(onClick = onPinSelected) {
                    Icon(Icons.Default.Star, contentDescription = "置顶")
                }
                IconButton(onClick = onGroupSelected) {
                    Icon(Icons.Default.Add, contentDescription = "移入分组")
                }
                IconButton(onClick = onDeleteSelected) {
                    Icon(Icons.Default.Delete, contentDescription = "删除")
                }
            }
        )
    } else {
        ScreenHeader(
            title = "墨读",
            subtitle = if (total == 0) "本地离线阅读" else "书架共 $total 本",
            large = true,
            divider = false,
            actions = {
                IconButton(onClick = onToggleSearch) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = "搜索",
                        tint = if (searchVisible) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onStats) {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = "阅读统计",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onSettings) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = "设置",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "更多",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("笔记与书签") },
                            onClick = { menu = false; onNotes() }
                        )
                        DropdownMenuItem(
                            text = { Text("隐藏的书籍") },
                            onClick = { menu = false; onHidden() }
                        )
                    }
                }
            }
        )
    }
}

// ---------------------------------------------------------------- 书籍卡片

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookGridCard(
    item: ShelfBook,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(4.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
                .shadow(2.dp, MaterialTheme.shapes.small)
                .clip(MaterialTheme.shapes.small)
        ) {
            BookCover(book = item.book, modifier = Modifier.fillMaxSize())
            CoverProgress(item.percent)
            if (item.book.isPinned) {
                Badge(text = "置顶", modifier = Modifier.align(Alignment.TopEnd).padding(5.dp))
            }
            if (selectionMode) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(5.dp)
                        .size(22.dp)
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary
                            else LocalMiradColors.current.coverScrim
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (selected) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = item.book.title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = item.book.format,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (item.percent > 0f) {
                Text(
                    text = "${(item.percent * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookListRow(
    item: ShelfBook,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BookCover(
                book = item.book,
                modifier = Modifier
                    .width(48.dp)
                    .height(66.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.book.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${item.book.author ?: "未知作者"} · ${item.book.totalChapters} 章",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(6.dp))
                InlineProgress(item.percent)
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "${(item.percent * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/** 网格卡片：进度贴在封面底部。 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.CoverProgress(percent: Float) {
    if (percent <= 0f) return
    Box(
        modifier = Modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth()
            .height(4.dp)
            .background(LocalMiradColors.current.coverScrim)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(percent.coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.primary)
        )
    }
}

/** 列表模式：独立细进度条。 */
@Composable
private fun InlineProgress(percent: Float) {
    LinearProgressIndicator(
        progress = { percent.coerceIn(0f, 1f) },
        modifier = Modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp)),
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.surfaceVariant
    )
}

@Composable
private fun Badge(text: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        shape = RoundedCornerShape(6.dp),
        modifier = modifier
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
        )
    }
}

@Composable
private fun EmptyShelf(searching: Boolean, onImport: () -> Unit) {
    if (searching) {
        EmptyState(
            title = "没有匹配的书籍",
            badge = "空",
            description = "换个关键词，或切回「全部」分组",
            modifier = Modifier.fillMaxSize()
        )
    } else {
        EmptyState(
            title = "书架还是空的",
            badge = "书",
            description = "导入本机的 txt / epub 就能开始阅读",
            actionText = "导入 txt / epub",
            onAction = onImport,
            modifier = Modifier.fillMaxSize()
        )
    }
}

// 书籍与分组的操作面板统一用 ui/common/ActionSheet，不再各自实现 ModalBottomSheet。

@Composable
private fun ShelfToast(message: String, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.inverseSurface,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 84.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onDismiss) { Text("知道了") }
        }
    }
}

@Composable
private fun ImportBar(items: List<ImportItem>, running: Boolean) {
    if (items.isEmpty()) return
    val active = items.lastOrNull {
        it.stage == ImportStage.COPYING || it.stage == ImportStage.PARSING || it.stage == ImportStage.INDEXING
    }
    if (active == null && !running) return
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "正在导入 ${active?.displayName ?: ""}",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { active?.progress ?: 0f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 工具

private fun isSupported(name: String): Boolean {
    val lower = name.lowercase()
    return lower.endsWith(".txt") || lower.endsWith(".epub")
}

/** 「3 小时前 / 昨天 / 3 天前」这类相对时间，书架副标题用。 */
private fun formatRelative(timeMillis: Long): String {
    if (timeMillis <= 0L) return "还没读过"
    val diff = System.currentTimeMillis() - timeMillis
    val minutes = diff / 60_000
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "$minutes 分钟前"
        minutes < 60 * 24 -> "${minutes / 60} 小时前"
        minutes < 60 * 24 * 2 -> "昨天"
        minutes < 60 * 24 * 30 -> "${minutes / (60 * 24)} 天前"
        else -> {
            val days = minutes / (60 * 24)
            "${days / 30} 个月前"
        }
    }
}

private data class SampleScan(
    val sources: List<ImportSource>,
    val directory: String,
    val exists: Boolean,
    val count: Int
)

private fun scanSamples(container: AppContainer): SampleScan {
    val candidates = listOf(container.paths.samplesDir, File(container.paths.root, "samples"))
    for (dir in candidates) {
        val files = dir.listFiles()?.filter { it.isFile && isSupported(it.name) }.orEmpty()
        if (files.isNotEmpty()) {
            return SampleScan(files.map { ImportSource.FromFile(it) }, dir.absolutePath, true, files.size)
        }
    }
    val first = candidates.first()
    val all = first.listFiles()?.size ?: 0
    return SampleScan(emptyList(), first.absolutePath, first.exists(), all)
}

private fun toggle(selection: Set<Long>, id: Long): Set<Long> =
    if (id in selection) selection - id else selection + id
