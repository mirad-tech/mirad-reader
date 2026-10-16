package com.mirad.reader.ui.shelf

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import com.mirad.reader.AppContainer
import com.mirad.reader.BuildConfig
import com.mirad.reader.data.db.entity.BookEntity
import com.mirad.reader.data.db.entity.BookFormat
import com.mirad.reader.data.imports.ImportSource
import com.mirad.reader.data.imports.ImportSources
import com.mirad.reader.data.imports.ImportStage
import com.mirad.reader.data.repo.ShelfBook
import com.mirad.reader.ui.common.ChoiceRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class ShelfSort(val label: String) {
    RECENT("最近阅读"),
    ADDED("添加时间"),
    TITLE("书名"),
    AUTHOR("作者"),
    SIZE("文件大小")
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
    var newGroupName by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<BookEntity?>(null) }
    var editTitle by remember { mutableStateOf("") }
    var editAuthor by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }

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
                if (sources.isEmpty()) {
                    container.importer.import(emptyList())
                } else {
                    container.importer.import(sources)
                }
            }
        }
    }

    val visible = remember(shelf, sort, selectedGroup, query) {
        shelf
            .filter { !it.book.isHidden }
            .filter { selectedGroup == null || it.book.groupId == selectedGroup }
            .filter { query.isBlank() || it.book.title.contains(query, true) || (it.book.author?.contains(query, true) == true) }
            .sortedWith(
                when (sort) {
                    ShelfSort.RECENT -> compareByDescending<ShelfBook> { it.book.isPinned }
                        .thenByDescending { it.lastReadAt }
                    ShelfSort.ADDED -> compareByDescending<ShelfBook> { it.book.isPinned }
                        .thenByDescending { it.book.addedAt }
                    ShelfSort.TITLE -> compareByDescending<ShelfBook> { it.book.isPinned }
                        .thenBy { it.book.title }
                    ShelfSort.AUTHOR -> compareByDescending<ShelfBook> { it.book.isPinned }
                        .thenBy { it.book.author.orEmpty() }
                    ShelfSort.SIZE -> compareByDescending<ShelfBook> { it.book.isPinned }
                        .thenByDescending { it.book.fileSize }
                }
            )
    }

    Scaffold(
        topBar = {
            if (selection.isEmpty()) {
                TopAppBar(
                    title = { Text(if (query.isBlank()) "墨读" else "搜索：$query") },
                    actions = {
                        IconButton(onClick = { showSearch = !showSearch }) {
                            Icon(Icons.Default.Search, contentDescription = "搜索书架")
                        }
                        IconButton(onClick = onOpenStats) {
                            Icon(Icons.Default.Star, contentDescription = "阅读统计")
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "设置")
                        }
                        IconButton(onClick = { sortMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "排序")
                        }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            ShelfSort.values().forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.label + if (option == sort) "  ✓" else "") },
                                    onClick = {
                                        sort = option
                                        sortMenu = false
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(if (layout == ShelfLayout.GRID) "切换为列表" else "切换为网格") },
                                onClick = {
                                    layout = if (layout == ShelfLayout.GRID) ShelfLayout.LIST else ShelfLayout.GRID
                                    sortMenu = false
                                }
                            )
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = { Text("已选择 ${selection.size} 本") },
                    navigationIcon = {
                        IconButton(onClick = { selection = emptySet() }) {
                            Icon(Icons.Default.Close, contentDescription = "退出多选")
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            scope.launch {
                                selection.forEach { id ->
                                    val pinned = shelf.firstOrNull { it.book.id == id }?.book?.isPinned == true
                                    container.books.setPinned(id, !pinned)
                                }
                                selection = emptySet()
                            }
                        }) { Icon(Icons.Default.Star, contentDescription = "置顶") }
                        IconButton(onClick = { groupDialog = true }) {
                            Icon(Icons.Default.Add, contentDescription = "移入分组")
                        }
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "删除")
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            if (selection.isEmpty()) {
                Box {
                    ExtendedFloatingActionButton(
                        onClick = { importMenu = true },
                        icon = { Icon(Icons.Default.Add, contentDescription = null) },
                        text = { Text("导入") }
                    )
                    DropdownMenu(expanded = importMenu, onDismissRequest = { importMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("导入文件（txt / epub）") },
                            onClick = {
                                importMenu = false
                                filePicker.launch(arrayOf("*/*"))
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("导入文件夹") },
                            onClick = {
                                importMenu = false
                                folderPicker.launch(null)
                            }
                        )
                        if (BuildConfig.DEBUG) {
                            DropdownMenuItem(
                                text = { Text("导入调试样书（samples 目录）") },
                                onClick = {
                                    importMenu = false
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) { scanSamples(container) }
                                        if (result.sources.isNotEmpty()) {
                                            container.importer.import(result.sources)
                                        } else {
                                            android.widget.Toast.makeText(
                                                context,
                                                "未找到样书：${result.directory}\n存在=${result.exists} 文件数=${result.count}",
                                                android.widget.Toast.LENGTH_LONG
                                            ).show()
                                        }
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
                    label = { Text("书架搜索（书名 / 作者）") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                )
            }

            if (groups.isNotEmpty() || selectedGroup != null) {
                ChoiceRow(
                    label = "",
                    options = buildList {
                        add(null as Long? to "全部")
                        add(-1L to "未分组")
                        groups.forEach { add(it.id to it.name) }
                    },
                    selected = selectedGroup,
                    onSelect = { selectedGroup = if (it == -1L) -1L else it },
                    modifier = Modifier.padding(horizontal = 12.dp),
                    columns = 4
                )
            }

            if (visible.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "书架还是空的\n点右下角「导入」添加 txt / epub",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else if (layout == ShelfLayout.GRID) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 108.dp),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
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
                                selection = if (selection.isEmpty()) setOf(item.book.id) else toggle(selection, item.book.id)
                            },
                            onEdit = {
                                editing = item.book
                                editTitle = item.book.title
                                editAuthor = item.book.author.orEmpty()
                            },
                            onDelete = { selection = setOf(item.book.id); confirmDelete = true }
                        )
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
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
                                selection = if (selection.isEmpty()) setOf(item.book.id) else toggle(selection, item.book.id)
                            }
                        )
                    }
                }
            }

            ImportBar(importState.items, importState.running)
        }
    }

    // ---------------- 对话框 ----------------

    if (summary != null) {
        val s = summary!!
        AlertDialog(
            onDismissRequest = { container.importer.clearSummary() },
            title = { Text("导入完成") },
            text = {
                Column {
                    Text("成功 ${s.success} 本，跳过 ${s.skipped} 本，失败 ${s.failed} 本")
                    s.messages.take(6).forEach { message ->
                        Text(
                            text = "· $message",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    if (s.skipped > 0) {
                        Text(
                            text = "重复书籍已跳过；如需再次导入，可删除原书后重新导入。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
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
                    groups.forEach { group ->
                        TextButton(onClick = {
                            scope.launch {
                                selection.forEach { container.books.setGroup(it, group.id) }
                                selection = emptySet()
                                groupDialog = false
                            }
                        }) { Text(group.name) }
                    }
                    TextButton(onClick = {
                        scope.launch {
                            selection.forEach { container.books.setGroup(it, null) }
                            selection = emptySet()
                            groupDialog = false
                        }
                    }) { Text("移出分组") }
                    OutlinedTextField(
                        value = nameDraft,
                        onValueChange = { nameDraft = it },
                        label = { Text("新建分组") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = nameDraft.trim()
                        if (name.isNotEmpty()) {
                            scope.launch {
                                val id = container.groups.create(name)
                                selection.forEach { container.books.setGroup(it, id) }
                                selection = emptySet()
                                groupDialog = false
                            }
                        } else {
                            groupDialog = false
                        }
                    }
                ) { Text("新建并移入") }
            },
            dismissButton = {
                TextButton(onClick = { groupDialog = false }) { Text("取消") }
            }
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除书籍") },
            text = { Text("将删除所选 ${selection.size} 本书及其阅读记录、书签与笔记，且不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        selection.forEach { id ->
                            container.books.getBook(id)?.let { container.books.deleteBook(it) }
                        }
                        selection = emptySet()
                        confirmDelete = false
                    }
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            }
        )
    }

    editing?.let { book ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("编辑书籍信息") },
            text = {
                Column {
                    OutlinedTextField(
                        value = editTitle,
                        onValueChange = { editTitle = it },
                        label = { Text("书名") },
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))
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
            dismissButton = {
                TextButton(onClick = { editing = null }) { Text("取消") }
            }
        )
    }
}

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

/** 调试用：扫描 samples 目录（外部私有目录优先，其次内部私有目录）。 */
private fun scanSamples(container: AppContainer): SampleScan {
    val candidates = listOf(
        container.paths.samplesDir,
        File(container.paths.root, "samples")
    )
    for (dir in candidates) {
        val files = dir.listFiles()?.filter { it.isFile && isSupported(it.name) }.orEmpty()
        android.util.Log.i(
            "MiradDebug",
            "scanSamples dir=${dir.absolutePath} exists=${dir.exists()} supported=${files.size}"
        )
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookGridCard(
    item: ShelfBook,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(6.dp)
    ) {
        Box {
            BookCover(
                book = item.book,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.72f)
            )
            if (item.book.isPinned) {
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                ) {
                    Text(
                        text = "置顶",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                }
            }
            if (selected) {
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = item.book.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = buildString {
                append(item.book.author ?: "未知作者")
                append(" · ")
                append(formatSize(item.book.fileSize))
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { item.percent.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = "已读 ${(item.percent * 100).toInt()}% · ${item.book.format}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (!selectionMode) {
            Box {
                TextButton(onClick = { menu = true }) { Text("更多") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("编辑信息") }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text("删除") }, onClick = { menu = false; onDelete() })
                }
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BookCover(
            book = item.book,
            modifier = Modifier
                .width(52.dp)
                .height(70.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.book.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${item.book.author ?: "未知作者"} · ${item.book.totalChapters} 章 · ${item.book.format}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { item.percent.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "${(item.percent * 100).toInt()}%",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ImportBar(items: List<com.mirad.reader.data.imports.ImportItem>, running: Boolean) {
    if (items.isEmpty()) return
    val active = items.lastOrNull {
        it.stage == ImportStage.COPYING || it.stage == ImportStage.PARSING || it.stage == ImportStage.INDEXING
    }
    if (active == null && !running) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "正在导入：${active?.displayName ?: "处理中"}",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { active?.progress ?: 0f },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
        )
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> "未知大小"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
}
