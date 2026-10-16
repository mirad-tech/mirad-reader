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
import com.mirad.reader.ui.common.ChoiceChip
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
    onOpenSettings: () -> Unit
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

    val visible = remember(shelf, sort, selectedGroup, query) {
        shelf
            .filter { !it.book.isHidden || selection.isNotEmpty() }
            .filter {
                when (selectedGroup) {
                    null -> true
                    -1L -> it.book.groupId == null
                    else -> it.book.groupId == selectedGroup
                }
            }
            .filter {
                query.isBlank() ||
                    it.book.title.contains(query, true) ||
                    (it.book.author?.contains(query, true) == true)
            }
            .sortedWith(
                when (sort) {
                    ShelfSort.RECENT -> compareByDescending<ShelfBook> { it.book.isPinned }.thenByDescending { it.lastReadAt }
                    ShelfSort.ADDED -> compareByDescending<ShelfBook> { it.book.isPinned }.thenByDescending { it.book.addedAt }
                    ShelfSort.TITLE -> compareByDescending<ShelfBook> { it.book.isPinned }.thenBy { it.book.title }
                    ShelfSort.AUTHOR -> compareByDescending<ShelfBook> { it.book.isPinned }.thenBy { it.book.author.orEmpty() }
                    ShelfSort.SIZE -> compareByDescending<ShelfBook> { it.book.isPinned }.thenByDescending { it.book.fileSize }
                }
            )
    }

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

            if (groups.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        ChoiceChip("全部", selectedGroup == null) { selectedGroup = null }
                    }
                    item {
                        ChoiceChip("未分组", selectedGroup == -1L) { selectedGroup = -1L }
                    }
                    items(groups, key = { it.id }) { group ->
                        ChoiceChip(group.name, selectedGroup == group.id) { selectedGroup = group.id }
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
        BookActionSheet(
            book = book,
            canGroup = groups.isNotEmpty(),
            onDismiss = { actionBook = null },
            onOpen = { actionBook = null; onOpenBook(book.id) },
            onEdit = {
                actionBook = null
                editing = book
                editTitle = book.title
                editAuthor = book.author.orEmpty()
            },
            onTogglePin = {
                actionBook = null
                scope.launch { container.books.setPinned(book.id, !book.isPinned) }
            },
            onHide = {
                actionBook = null
                scope.launch { container.books.setHidden(book.id, true); toast = "已隐藏，可在设置里恢复" }
            },
            onGroup = { actionBook = null; selection = setOf(book.id); groupDialog = true },
            onDelete = { actionBook = null; selection = setOf(book.id); confirmDelete = true }
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
    onExitSelection: () -> Unit,
    onPinSelected: () -> Unit,
    onGroupSelected: () -> Unit,
    onDeleteSelected: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxWidth()
    ) {
        if (selectionCount > 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onExitSelection) {
                    Icon(Icons.Default.Close, contentDescription = "退出多选")
                }
                Text(
                    text = "已选 $selectionCount 本",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
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
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "墨读",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = if (total == 0) "本地离线阅读" else "书架共 $total 本",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onToggleSearch) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = "搜索",
                        tint = if (searchVisible) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onStats) {
                    Icon(Icons.Default.Star, contentDescription = "阅读统计", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "设置", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
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
                            else Color.Black.copy(alpha = 0.35f)
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
            .background(Color.Black.copy(alpha = 0.22f))
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
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(24.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (searching) "空" else "书",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = if (searching) "没有匹配的书籍" else "书架还是空的",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (searching) "换个关键词或分组试试" else "导入本机的 txt / epub 就能开始阅读",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            if (!searching) {
                Spacer(modifier = Modifier.height(18.dp))
                OutlinedButton(onClick = onImport) { Text("导入 txt / epub") }
            }
        }
    }
}

/** 长按书籍后的操作面板。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookActionSheet(
    book: BookEntity,
    canGroup: Boolean,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onTogglePin: () -> Unit,
    onHide: () -> Unit,
    onGroup: () -> Unit,
    onDelete: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BookCover(
                    book = book,
                    modifier = Modifier
                        .width(44.dp)
                        .height(60.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(text = book.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "${book.author ?: "未知作者"} · ${book.totalChapters} 章 · ${book.format}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            SheetAction("继续阅读", onOpen)
            SheetAction("编辑书名 / 作者", onEdit)
            SheetAction(if (book.isPinned) "取消置顶" else "置顶", onTogglePin)
            if (canGroup) SheetAction("移入分组", onGroup)
            SheetAction("在书架隐藏", onHide)
            SheetAction("删除这本书", onDelete, danger = true)
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SheetAction(text: String, onClick: () -> Unit, danger: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp)
    )
}

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
