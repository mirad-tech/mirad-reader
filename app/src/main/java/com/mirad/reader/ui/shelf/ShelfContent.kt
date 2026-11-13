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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import com.mirad.reader.ui.common.GlassDropdownMenu as DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import com.mirad.reader.ui.common.MiradLinearProgress
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import com.mirad.reader.ui.common.MiradTextButton
import androidx.compose.material3.OutlinedTextField
import com.mirad.reader.ui.common.MiradScaffold as Scaffold
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
import com.mirad.reader.R
import com.mirad.reader.data.db.entity.BookEntity
import com.mirad.reader.data.imports.ImportItem
import com.mirad.reader.data.imports.ImportStage
import com.mirad.reader.data.repo.ShelfBook
import com.mirad.reader.ui.MiradIcons
import com.mirad.reader.ui.common.ActionSheet
import com.mirad.reader.ui.common.EmptyState
import com.mirad.reader.ui.common.ScreenHeader
import com.mirad.reader.ui.design.Spacing
import kotlinx.coroutines.launch

// ---------------------------------------------------------------- 顶栏

@Composable
private fun ShelfHeader(
    selectionCount: Int,
    searchVisible: Boolean,
    groupFiltered: Boolean,
    onToggleSearch: () -> Unit,
    onStats: () -> Unit,
    onSettings: () -> Unit,
    onNotes: () -> Unit,
    onManageGroups: () -> Unit,
    onClearGroupFilter: () -> Unit,
    onExitSelection: () -> Unit,
    onSelectAll: () -> Unit,
    onPinSelected: () -> Unit,
    onGroupSelected: () -> Unit,
    onDeleteSelected: () -> Unit,
    importAction: @Composable () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    if (selectionCount > 0) {
        ScreenHeader(
            title = "已选 $selectionCount 本",
            onBack = onExitSelection,
            root = false, floatingGlass = true,
            actions = {
                IconButton(onClick = onSelectAll) {
                    Icon(Icons.Default.Check, contentDescription = "全选")
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(MiradIcons.More, "批量操作") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("置顶") }, onClick = { menu = false; onPinSelected() })
                        DropdownMenuItem(text = { Text("移入分组") }, onClick = { menu = false; onGroupSelected() })
                        DropdownMenuItem(text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                            onClick = { menu = false; onDeleteSelected() })
                    }
                }
            }
        )
    } else {
        ScreenHeader(
            title = "书架",
            actions = {
                importAction()
                IconButton(onClick = onToggleSearch) {
                    Icon(
                        MiradIcons.Search,
                        contentDescription = "搜索",
                        tint = if (searchVisible) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(
                            MiradIcons.More,
                            contentDescription = "更多",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("分组管理") },
                            onClick = { menu = false; onManageGroups() }
                        )
                        if (groupFiltered) {
                            DropdownMenuItem(
                                text = { Text("清除分组筛选") },
                                onClick = { menu = false; onClearGroupFilter() }
                            )
                        }
                    }
                }
            }
        )
    }
}

@Composable
private fun EmptyShelf(
    filtered: Boolean,
    onImport: () -> Unit,
    onSearch: (() -> Unit)?,
    onClearFilter: () -> Unit
) {
    if (filtered) {
        EmptyState(
            title = "没有匹配的书籍",
            actionText = "清除筛选",
            onAction = onClearFilter,
            modifier = Modifier.fillMaxSize()
        )
    } else {
        EmptyState(
            title = "书架为空",
            description = "支持 TXT、EPUB 文件",
            actionText = "导入书籍",
            onAction = onImport,
            modifier = Modifier.fillMaxSize(),
            illustrationRes = R.drawable.ic_empty_bookshelf,
            secondaryAction = {
                if (onSearch != null) MiradTextButton(onClick = onSearch) { Text("去搜书") }
            }
        )
    }
}

// 书籍与分组的操作面板统一用 ui/common/ActionSheet，不再各自实现 ModalBottomSheet。

// ---------------------------------------------------------------- 工具

private fun toggle(selection: Set<Long>, id: Long): Set<Long> =
    if (id in selection) selection - id else selection + id


/** Read-only input for the shelf's UI; repositories stay in ShelfScreen. */
internal data class ShelfContentState(
    val shelf: List<ShelfBook> = emptyList(),
    val visible: List<ShelfBook>,
    val sort: ShelfSort = ShelfSort.RECENT,
    val layout: ShelfLayout = ShelfLayout.GRID,
    val selectedGroup: Long? = null,
    val selectedGroupName: String? = null,
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
    val onToggleSearch: () -> Unit = {},
    val onQueryChange: (String) -> Unit = {},
    val onSortChange: (ShelfSort) -> Unit = {},
    val onLayoutChange: (ShelfLayout) -> Unit = {},
    val onClearGroupFilter: () -> Unit = {},
    val onSelectionChange: (Set<Long>) -> Unit = {},
    val onBookAction: (BookEntity) -> Unit = {},
    val onManageGroups: () -> Unit = {},
    val onSelectAll: () -> Unit = {},
    val onPinSelected: () -> Unit = {},
    val onGroupSelected: () -> Unit = {},
    val onDeleteSelected: () -> Unit = {},
    val onImportFile: () -> Unit = {},
    val onImportFolder: () -> Unit = {},
    val onImportUrl: () -> Unit = {},
    val onOnlineSearch: (() -> Unit)? = null,
    val onSourceManager: () -> Unit = {}
)

@Composable
internal fun ShelfContent(
    state: ShelfContentState,
    actions: ShelfContentActions = ShelfContentActions(),
    snackbarHost: @Composable () -> Unit = {},
    initialScrollIndex: Int = 0,
    initialScrollOffset: Int = 0,
    resumePrompt: ShelfResumePromptState = remember { ShelfResumePromptState() }
) {
    val shelf = state.shelf
    val visible = state.visible
    val sort = state.sort
    val layout = state.layout
    val selectedGroup = state.selectedGroup
    val query = state.query
    val showSearch = state.showSearch
    val selection = state.selection
    val onOpenBook: (Long) -> Unit = { bookId ->
        resumePrompt.dismiss()
        actions.onOpenBook(bookId)
    }
    val continueReading = remember(shelf) { resolveContinueReading(shelf) }
    var sortMenu by remember { mutableStateOf(false) }
    var importMenu by remember { mutableStateOf(false) }
    com.mirad.reader.ui.common.ResetEdgeBounceOnOverlay(importMenu || sortMenu)
    val gridState = rememberLazyGridState(initialScrollIndex, initialScrollOffset)
    val listState = rememberLazyListState(initialScrollIndex, initialScrollOffset)
    val scrollScope = androidx.compose.runtime.rememberCoroutineScope()
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

            if (visible.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs),
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
                    val groupSuffix = state.selectedGroupName?.let { " · $it" }.orEmpty()
                    Text(
                        text = "${visible.size} 本$groupSuffix",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val toList = layout == ShelfLayout.GRID
                    IconButton(onClick = {
                        actions.onLayoutChange(if (toList) ShelfLayout.LIST else ShelfLayout.GRID)
                    }) {
                        Icon(
                            imageVector = if (toList) MiradIcons.Contents else MiradIcons.Grid,
                            contentDescription = if (toList) "切换到列表" else "切换到网格",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
    val empty: @Composable () -> Unit = {
        Box(Modifier.fillMaxWidth().heightIn(min = 320.dp)) {
            EmptyShelf(
                filtered = query.isNotBlank() || selectedGroup != null,
                onImport = actions.onImportFile,
                onSearch = actions.onOnlineSearch,
                onClearFilter = {
                    actions.onQueryChange("")
                    actions.onClearGroupFilter()
                }
            )
        }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = snackbarHost,
        bottomAccessory = { available ->
            ShelfFloatingCard(
                available = available && selection.isEmpty(),
                resume = continueReading,
                prompt = resumePrompt,
                importing = state.importing,
                importItems = state.importItems,
                onOpenBook = onOpenBook
            )
        },
        topBar = {
            ShelfHeader(
                selectionCount = selection.size, groupFiltered = selectedGroup != null,
                searchVisible = showSearch, onToggleSearch = {
                    actions.onToggleSearch()
                    // 仅用户打开搜索时露出输入框，跨栏恢复可见搜索不触发回顶。
                    if (!showSearch) scrollScope.launch {
                        if (layout == ShelfLayout.GRID) gridState.animateScrollToItem(0)
                        else listState.animateScrollToItem(0)
                    }
                },
                onStats = actions.onStats, onSettings = actions.onSettings,
                onNotes = actions.onNotes,
                onManageGroups = actions.onManageGroups,
                onClearGroupFilter = actions.onClearGroupFilter,
                onExitSelection = { actions.onSelectionChange(emptySet()) },
                onSelectAll = actions.onSelectAll, onPinSelected = actions.onPinSelected,
                onGroupSelected = actions.onGroupSelected, onDeleteSelected = actions.onDeleteSelected,
                importAction = {
                    Box {
                        IconButton(onClick = { importMenu = true }) {
                            Icon(MiradIcons.Add, "导入", tint = MaterialTheme.colorScheme.onSurface)
                        }
                        DropdownMenu(expanded = importMenu, onDismissRequest = { importMenu = false }) {
                            DropdownMenuItem(text = { Text("导入文件（txt / epub）") },
                                onClick = { importMenu = false; actions.onImportFile() })
                            DropdownMenuItem(text = { Text("导入文件夹") },
                                onClick = { importMenu = false; actions.onImportFolder() })
                            DropdownMenuItem(text = { Text("网址导入") },
                                onClick = { importMenu = false; actions.onImportUrl() })
                        }
                    }
                }
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().glassBackdropSource()) {
            val layoutDirection = LocalLayoutDirection.current
            val contentPadding = PaddingValues(
                start = padding.calculateStartPadding(layoutDirection) + 16.dp,
                end = padding.calculateEndPadding(layoutDirection) + 16.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 16.dp
            )
            if (layout == ShelfLayout.GRID) {
                BouncingLazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 104.dp),
                    state = gridState, contentPadding = contentPadding,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                    modifier = Modifier.fillMaxWidth().weight(1f)
                ) {
                    item(key = "shelf-controls", span = { GridItemSpan(maxLineSpan) }) { controls() }
                    if (visible.isEmpty()) {
                        item(key = "shelf-empty", span = { GridItemSpan(maxLineSpan) }) {
                            empty()
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
                val percentColumnWidth = listProgressLabelWidth()
                BouncingLazyColumn(
                    state = listState, contentPadding = contentPadding,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth().weight(1f)
                ) {
                    item(key = "shelf-controls") { controls() }
                    if (visible.isEmpty()) {
                        item(key = "shelf-empty") { empty() }
                    }
                    items(visible, key = { it.book.id }) { item ->
                        BookListRow(
                            item = item, selected = item.book.id in selection,
                            percentColumnWidth = percentColumnWidth,
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
        }
    }
}

internal fun resolveContinueReading(shelf: List<ShelfBook>): ShelfBook? =
    shelf.filter { !it.book.isHidden && it.lastReadAt > 0L }.maxByOrNull { it.lastReadAt }
