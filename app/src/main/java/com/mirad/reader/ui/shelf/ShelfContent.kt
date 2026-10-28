package com.mirad.reader.ui.shelf

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import com.mirad.reader.ui.common.BouncingLazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.heightIn
import com.mirad.reader.ui.common.glassBackdropSource
import androidx.compose.foundation.lazy.grid.GridCells
import com.mirad.reader.ui.common.BouncingLazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import com.mirad.reader.ui.common.GlassDropdownMenu as DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import com.mirad.reader.ui.common.MiradLinearProgress
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import com.mirad.reader.ui.common.MiradTextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mirad.reader.BuildConfig
import com.mirad.reader.data.db.entity.BookEntity
import com.mirad.reader.data.imports.ImportItem
import com.mirad.reader.data.imports.ImportStage
import com.mirad.reader.data.repo.ShelfBook
import com.mirad.reader.ui.common.ActionSheet
import com.mirad.reader.ui.common.ChoiceChip
import com.mirad.reader.ui.common.EmptyState
import com.mirad.reader.ui.common.ScreenHeader

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
    onManageGroups: () -> Unit,
    onExitSelection: () -> Unit,
    onSelectAll: () -> Unit,
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
                IconButton(onClick = onSelectAll) {
                    Icon(Icons.Default.Check, contentDescription = "全选")
                }
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
            subtitle = if (total == 0) "导入本地书开始阅读" else "书架共 $total 本",
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
                        DropdownMenuItem(
                            text = { Text("分组管理") },
                            onClick = { menu = false; onManageGroups() }
                        )
                    }
                }
            }
        )
    }
}

@Composable
private fun EmptyShelf(searching: Boolean, onImport: () -> Unit, hiddenOnly: Boolean = false) {
    when {
        hiddenOnly -> EmptyState(
            // 只看隐藏书籍的筛选为空时，书架本身往往还有书，不能引导"导入"。
            title = "没有隐藏的书籍",
            badge = "空",
            description = "长按书籍后选择「隐藏」，书籍会出现在这里",
            modifier = Modifier.fillMaxSize()
        )
        searching -> EmptyState(
            title = "没有匹配的书籍",
            badge = "空",
            description = "换个关键词，或切回「全部」分组",
            modifier = Modifier.fillMaxSize()
        )
        else -> EmptyState(
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
                MiradLinearProgress(
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

private fun toggle(selection: Set<Long>, id: Long): Set<Long> =
    if (id in selection) selection - id else selection + id


/** Read-only input for the shelf's UI; repositories stay in ShelfScreen. */
internal data class ShelfContentState(
    val shelf: List<ShelfBook> = emptyList(),
    val visible: List<ShelfBook> = shelf,
    val groups: List<com.mirad.reader.data.db.entity.GroupEntity> = emptyList(),
    val sort: ShelfSort = ShelfSort.RECENT,
    val layout: ShelfLayout = ShelfLayout.GRID,
    val selectedGroup: Long? = null,
    val showHidden: Boolean = false,
    val query: String = "",
    val showSearch: Boolean = false,
    val selection: Set<Long> = emptySet(),
    val importItems: List<ImportItem> = emptyList(),
    val importing: Boolean = false
)

internal data class ShelfContentActions(
    val onOpenBook: (Long) -> Unit = {},
    val onStats: () -> Unit = {},
    val onSettings: () -> Unit = {},
    val onNotes: () -> Unit = {},
    val onHidden: () -> Unit = {},
    val onToggleSearch: () -> Unit = {},
    val onQueryChange: (String) -> Unit = {},
    val onSortChange: (ShelfSort) -> Unit = {},
    val onLayoutChange: (ShelfLayout) -> Unit = {},
    val onSelectGroup: (Long?, Boolean) -> Unit = { _, _ -> },
    val onSelectionChange: (Set<Long>) -> Unit = {},
    val onBookAction: (BookEntity) -> Unit = {},
    val onGroupAction: (com.mirad.reader.data.db.entity.GroupEntity) -> Unit = {},
    val onManageGroups: () -> Unit = {},
    val onSelectAll: () -> Unit = {},
    val onPinSelected: () -> Unit = {},
    val onGroupSelected: () -> Unit = {},
    val onDeleteSelected: () -> Unit = {},
    val onImportFile: () -> Unit = {},
    val onImportFolder: () -> Unit = {},
    val onImportUrl: () -> Unit = {},
    val onOnlineSearch: () -> Unit = {},
    val onSourceManager: () -> Unit = {},
    val onImportSamples: () -> Unit = {}
)

@Composable
internal fun ShelfContent(
    state: ShelfContentState,
    actions: ShelfContentActions = ShelfContentActions(),
    snackbarHost: @Composable () -> Unit = {},
    initialScrollIndex: Int = 0,
    initialScrollOffset: Int = 0
) {
    val shelf = state.shelf
    val visible = state.visible
    val groups = state.groups
    val sort = state.sort
    val layout = state.layout
    val selectedGroup = state.selectedGroup
    val showHidden = state.showHidden
    val query = state.query
    val showSearch = state.showSearch
    val selection = state.selection
    val hiddenCount = remember(shelf) { shelf.count { it.book.isHidden } }
    val onOpenBook = actions.onOpenBook
    var sortMenu by remember { mutableStateOf(false) }
    var importMenu by remember { mutableStateOf(false) }
    com.mirad.reader.ui.common.ResetEdgeBounceOnOverlay(importMenu || sortMenu)
    val gridState = rememberLazyGridState(initialScrollIndex, initialScrollOffset)
    val listState = rememberLazyListState(initialScrollIndex, initialScrollOffset)
    // Search moved into the scroll content; opening it must still expose the field.
    androidx.compose.runtime.LaunchedEffect(showSearch, layout) {
        if (showSearch) {
            if (layout == ShelfLayout.GRID) gridState.animateScrollToItem(0)
            else listState.animateScrollToItem(0)
        }
    }
    val controls: @Composable () -> Unit = {
        Column {
            if (showSearch) {
                OutlinedTextField(
                    value = query,
                    onValueChange = actions.onQueryChange,
                    placeholder = { Text("搜索书名或作者") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                )
            }

            // 继续阅读：省得每次都要在书架里翻找上次那本
            val continueReading = remember(shelf, showHidden) {
                resolveContinueReading(shelf, showHidden)
            }
            continueReading?.let { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .clickable { onOpenBook(item.book.id) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "继续阅读 · 已读 ${(item.percent * 100).toInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = item.book.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            maxLines = 1
                        )
                    }
                    Text(
                        text = formatRelative(item.lastReadAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            if (groups.isNotEmpty() || hiddenCount > 0) {
                LazyRow(
                    contentPadding = PaddingValues(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        ChoiceChip(
                            text = "全部",
                            selected = selectedGroup == null && !showHidden,
                            onClick = { actions.onSelectGroup(null, false) }
                        )
                    }
                    item {
                        ChoiceChip(
                            text = "未分组",
                            selected = selectedGroup == ShelfQuery.GROUP_NONE && !showHidden,
                            onClick = { actions.onSelectGroup(ShelfQuery.GROUP_NONE, false) }
                        )
                    }
                    items(groups, key = { it.id }) { group ->
                        ChoiceChip(
                            text = group.name,
                            selected = selectedGroup == group.id && !showHidden,
                            onClick = { actions.onSelectGroup(group.id, false) },
                            onLongClick = { actions.onGroupAction(group) }
                        )
                    }
                    if (hiddenCount > 0) {
                        item {
                            ChoiceChip(
                                text = "已隐藏 $hiddenCount",
                                selected = showHidden,
                                onClick = { actions.onSelectGroup(null, true) }
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box {
                    MiradTextButton(onClick = { sortMenu = true }) {
                        Text("${sort.label}  ▾")
                    }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        ShelfSort.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                onClick = { actions.onSortChange(option); sortMenu = false },
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
                MiradTextButton(onClick = {
                    actions.onLayoutChange(if (layout == ShelfLayout.GRID) ShelfLayout.LIST else ShelfLayout.GRID)
                }) {
                    Text(
                        text = if (layout == ShelfLayout.GRID) "网格" else "列表"
                    )
                }
            }

        }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = snackbarHost,
        topBar = {
            ShelfHeader(
                total = shelf.count { !it.book.isHidden }, selectionCount = selection.size,
                searchVisible = showSearch, onToggleSearch = actions.onToggleSearch,
                onStats = actions.onStats, onSettings = actions.onSettings,
                onNotes = actions.onNotes, onHidden = actions.onHidden,
                onManageGroups = actions.onManageGroups,
                onExitSelection = { actions.onSelectionChange(emptySet()) },
                onSelectAll = actions.onSelectAll, onPinSelected = actions.onPinSelected,
                onGroupSelected = actions.onGroupSelected, onDeleteSelected = actions.onDeleteSelected
            )
        },
        floatingActionButton = {
            if (selection.isEmpty()) {
                Box {
                    if (com.mirad.reader.ui.common.LocalGlassLegacy.current) {
                        ExtendedFloatingActionButton(
                            onClick = { importMenu = true },
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            shape = MaterialTheme.shapes.medium,
                            elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 2.dp, pressedElevation = 4.dp),
                            icon = { Icon(Icons.Default.Add, contentDescription = null) },
                            text = { Text("导入") }
                        )
                    } else {
                        com.mirad.reader.ui.common.GlassSurface(shape = MaterialTheme.shapes.large) {
                            Row(
                                modifier = Modifier
                                    .heightIn(min = 56.dp)
                                    .clickable(role = androidx.compose.ui.semantics.Role.Button) { importMenu = true }
                                    .padding(horizontal = 20.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Text("导入", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                    DropdownMenu(expanded = importMenu, onDismissRequest = { importMenu = false }) {
                        DropdownMenuItem(text = { Text("导入文件（txt / epub）") },
                            onClick = { importMenu = false; actions.onImportFile() })
                        DropdownMenuItem(text = { Text("导入文件夹") },
                            onClick = { importMenu = false; actions.onImportFolder() })
                        DropdownMenuItem(text = { Text("网址导入") },
                            onClick = { importMenu = false; actions.onImportUrl() })
                        DropdownMenuItem(text = { Text("在线搜书") },
                            onClick = { importMenu = false; actions.onOnlineSearch() })
                        DropdownMenuItem(text = { Text("书源管理") },
                            onClick = { importMenu = false; actions.onSourceManager() })
                        if (BuildConfig.DEBUG) {
                            DropdownMenuItem(text = { Text("导入调试样书（samples 目录）") },
                                onClick = { importMenu = false; actions.onImportSamples() })
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().glassBackdropSource()) {
            val layoutDirection = LocalLayoutDirection.current
            val contentPadding = PaddingValues(
                start = padding.calculateStartPadding(layoutDirection) + 16.dp,
                end = padding.calculateEndPadding(layoutDirection) + 16.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 96.dp
            )
            if (layout == ShelfLayout.GRID) {
                BouncingLazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 112.dp),
                    state = gridState, contentPadding = contentPadding,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    modifier = Modifier.fillMaxWidth().weight(1f)
                ) {
                    item(key = "shelf-controls", span = { GridItemSpan(maxLineSpan) }) { controls() }
                    if (visible.isEmpty()) {
                        item(key = "shelf-empty", span = { GridItemSpan(maxLineSpan) }) {
                            Box(Modifier.fillMaxWidth().heightIn(min = 320.dp)) {
                                EmptyShelf(
                                    query.isNotBlank() || selectedGroup != null,
                                    actions.onImportFile,
                                    hiddenOnly = showHidden && query.isBlank() && selectedGroup == null
                                )
                            }
                        }
                    }
                    items(visible, key = { it.book.id }) { item ->
                        BookGridCard(
                            item = item, selected = item.book.id in selection,
                            selectionMode = selection.isNotEmpty(),
                            onClick = {
                                if (selection.isEmpty()) onOpenBook(item.book.id)
                                else actions.onSelectionChange(toggle(selection, item.book.id))
                            },
                            onLongClick = {
                                if (selection.isEmpty()) actions.onBookAction(item.book)
                                else actions.onSelectionChange(toggle(selection, item.book.id))
                            }
                        )
                    }
                }
            } else {
                BouncingLazyColumn(
                    state = listState, contentPadding = contentPadding,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth().weight(1f)
                ) {
                    item(key = "shelf-controls") { controls() }
                    if (visible.isEmpty()) {
                        item(key = "shelf-empty") {
                            Box(Modifier.fillMaxWidth().heightIn(min = 320.dp)) {
                                EmptyShelf(
                                    query.isNotBlank() || selectedGroup != null,
                                    actions.onImportFile,
                                    hiddenOnly = showHidden && query.isBlank() && selectedGroup == null
                                )
                            }
                        }
                    }
                    items(visible, key = { it.book.id }) { item ->
                        BookListRow(
                            item = item, selected = item.book.id in selection,
                            onClick = {
                                if (selection.isEmpty()) onOpenBook(item.book.id)
                                else actions.onSelectionChange(toggle(selection, item.book.id))
                            },
                            onLongClick = {
                                if (selection.isEmpty()) actions.onBookAction(item.book)
                                else actions.onSelectionChange(toggle(selection, item.book.id))
                            }
                        )
                    }
                }
            }
            ImportBar(state.importItems, state.importing)
        }
    }
}

internal fun resolveContinueReading(shelf: List<ShelfBook>, showHidden: Boolean = false): ShelfBook? {
    if (showHidden) return null
    return shelf.filter { !it.book.isHidden && it.lastReadAt > 0L }.maxByOrNull { it.lastReadAt }
}
