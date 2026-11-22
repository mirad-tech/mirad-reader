package com.mirad.reader.ui.sources

import com.mirad.reader.ui.common.MiradScaffold as Scaffold
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mirad.reader.R
import com.mirad.reader.core.sources.*
import com.mirad.reader.ui.common.*
import com.mirad.reader.ui.design.Sizes
import com.mirad.reader.ui.design.Spacing
import com.mirad.reader.ui.MiradIcons
import com.mirad.reader.ui.shelf.CoverArtwork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun SourceSearchScreen(
    repository: SourceRepository,
    search: SourceSearch,
    downloads: BookDownloads,
    actionScope: CoroutineScope,
    onBack: () -> Unit,
    onSources: () -> Unit,
    onDownloads: () -> Unit
) {
    val state by search.state.collectAsStateWithLifecycle()
    val comparison by search.comparison.collectAsStateWithLifecycle()
    val sources by repository.sources.collectAsStateWithLifecycle()
    val loadError by repository.loadError.collectAsStateWithLifecycle()
    val availableSourceIds = remember(sources) {
        sources.filter { it.enabled && it.unsupportedReason == null }.mapTo(hashSetOf()) { it.id }
    }
    val feedback = rememberFeedbackState("source-search")
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf(state.query) }
    var menu by remember { mutableStateOf(false) }
    var expanded by remember(comparison.match?.key) { mutableStateOf(false) }
    var catalog by remember { mutableStateOf<SourceCatalog?>(null) }
    var submitting by remember { mutableStateOf<String?>(null) }
    var enqueueError by remember { mutableStateOf<String?>(null) }
    var permissionAsked by rememberSaveable { mutableStateOf(false) }
    var permissionDenied by rememberSaveable { mutableStateOf(false) }
    var pendingDownload by rememberSaveable { mutableStateOf<String?>(null) }
    val searchListState = rememberLazyListState()
    val comparisonListState = remember(comparison.match?.key) { LazyListState() }
    val retrying = state.running && state.retry != null
    LaunchedEffect(state.query) { searchListState.scrollToItem(0) }
    LaunchedEffect(retrying) { if (retrying) searchListState.scrollToItem(0) }

    fun enqueue(entry: SourceComparisonEntry) {
        val completeCatalog = entry.catalog ?: return
        if (submitting != null) return
        submitting = comparisonKey(entry)
        enqueueError = null
        actionScope.launch {
            try {
                downloads.enqueue(entry.source, completeCatalog)
                feedback.show("已加入下载列表", FeedbackKind.SUCCESS)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                enqueueError = sourceActionError("无法开始下载", error)
            } finally {
                submitting = null
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionDenied = !granted
        val pending = pendingDownload
        pendingDownload = null
        comparison.entries.firstOrNull { comparisonKey(it) == pending }?.let { enqueue(it) }
    }

    fun download(entry: SourceComparisonEntry) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !permissionAsked
        ) {
            permissionAsked = true
            pendingDownload = comparisonKey(entry)
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else enqueue(entry)
    }

    val comparing = comparison.match != null
    val back: () -> Unit = { if (comparing) search.clearComparison() else onBack() }
    BackHandler(enabled = comparing, onBack = back)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { FeedbackHost(feedback) },
        topBar = {
            ScreenHeader(if (comparing) "比较书源" else "搜书", onBack = back,
                root = !comparing && LocalAppRootPage.current, actions = {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "更多操作") }
                    GlassDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("书源管理") }, onClick = { menu = false; onSources() })
                        DropdownMenuItem(text = { Text("下载列表") }, onClick = { menu = false; onDownloads() })
                    }
                }
            })
        }
    ) { padding ->
        val keyboard = LocalSoftwareKeyboardController.current
        val focus = LocalFocusManager.current

        fun closeSearchInput() {
            focus.clearFocus()
            keyboard?.hide()
        }

        fun beginSearch() {
            if (query.isBlank()) return
            closeSearchInput()
            enqueueError = null
            search.search(query.trim())
        }

        BouncingLazyColumn(
            modifier = Modifier.fillMaxSize().imePadding().glassBackdropSource(),
            state = if (comparing) comparisonListState else searchListState,
            contentPadding = sourceListPadding(padding),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            loadError?.let { item { ErrorDetails(it) } }
            enqueueError?.let { item { ErrorDetails(it) } }
            if (permissionDenied) item {
                Text("通知未开启，可在下载列表查看进度。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (comparing) {
                sourceComparisonItems(comparison, expanded, submitting,
                    onExpand = { expanded = !expanded }, onCatalog = { catalog = it }, onDownload = { download(it) })
            } else {
                item {
                    val interaction = remember { MutableInteractionSource() }
                    val focused by interaction.collectIsFocusedAsState()
                    val colors = MaterialTheme.colorScheme
                    TextField(
                        value = query, onValueChange = { query = it },
                        placeholder = { Text("书名或作者") },
                        modifier = Modifier.fillMaxWidth()
                            .border(1.dp, if (focused) colors.primary else colors.outlineVariant, CircleShape)
                            .semantics { contentDescription = "书名或作者" },
                        singleLine = true, shape = CircleShape, interactionSource = interaction,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = colors.surface, unfocusedContainerColor = colors.surface,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
                        ),
                        leadingIcon = { Icon(MiradIcons.Search, contentDescription = null) },
                        trailingIcon = {
                            MiradTextButton(enabled = state.running || query.isNotBlank(),
                                onClick = { if (state.running) search.cancelSearch() else beginSearch() }) {
                                Text(when {
                                    retrying -> "取消重试"
                                    state.running -> "取消搜索"
                                    else -> "搜索"
                                })
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { beginSearch() })
                    )
                }
                if (state.totalSources > 0) item {
                    SearchStatus(state)
                }
                state.message?.let { item { Text(it, style = MaterialTheme.typography.bodyMedium) } }
                if (availableSourceIds.isEmpty()) item {
                    EmptyState("没有可用的已启用书源", actionText = "管理书源", onAction = onSources,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 280.dp),
                        illustrationRes = if (loadError == null && !state.running) R.drawable.ic_empty_search else null)
                } else if (state.query.isBlank() && !state.running) item {
                    EmptyState("输入书名或作者，搜索已启用的书源",
                        modifier = Modifier.fillMaxWidth().heightIn(min = 280.dp),
                        illustrationRes = R.drawable.ic_empty_search)
                } else if (state.query.isNotBlank() && !state.running && !state.cancelled &&
                    state.retry?.cancelled != true && state.totalSources > 0 &&
                    state.completedSources == state.totalSources && state.failures.isEmpty() && state.matches.isEmpty()
                ) item {
                    EmptyState("没有找到匹配的书籍",
                        modifier = Modifier.fillMaxWidth().heightIn(min = 280.dp),
                        illustrationRes = R.drawable.ic_empty_search)
                }
                items(state.matches, key = { it.key }) { match ->
                    SearchResultCard(match) {
                        closeSearchInput()
                        search.compare(match)
                    }
                }
                if (state.failures.isNotEmpty()) item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("搜索失败 ${state.failures.size} 个书源", Modifier.weight(1f),
                            style = MaterialTheme.typography.titleSmall)
                        if (!state.running && state.failures.any { it.sourceId in availableSourceIds }) {
                            MiradTextButton(onClick = {
                                closeSearchInput()
                                search.retryFailures()
                            }) { Text("重试") }
                        }
                    }
                }
                items(state.failures, key = { "failure:${it.sourceId}" }) { SourceFailureRow(it) }
            }
        }
    }
    catalog?.let { SourceCatalogSheet(it, onDismiss = { catalog = null }) }
}

@Composable
private fun SearchStatus(state: SourceSearchState) {
    val retry = state.retry
    val completed = retry?.completedSources ?: state.completedSources
    val total = retry?.totalSources ?: state.totalSources
    val processed = "$completed / $total"
    val label = if (retry == null) when {
        state.running -> "正在搜索 $processed"
        state.cancelled -> "已取消 · 已搜索 $processed"
        else -> "已搜索 $processed · 找到 ${state.matches.size} 本"
    } else when {
        state.running -> "正在重试 $processed"
        retry.cancelled -> "已取消重试 · 已处理 $processed"
        else -> "已重试 $processed · 找到 ${state.matches.size} 本"
    }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        MiradLinearProgress(
            progress = { completed.toFloat() / total },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun SearchResultCard(match: BookMatch, onClick: () -> Unit) {
    val shape = MaterialTheme.shapes.medium
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth().clip(shape)
            .clickable(role = Role.Button, onClickLabel = "比较《${match.title}》的书源", onClick = onClick),
        shape = shape, color = colors.surface,
        border = BorderStroke(1.dp, colors.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CoverArtwork(
                title = match.title, colorSeed = match.key.hashCode().toLong(),
                modifier = Modifier.size(Sizes.coverSmallWidth, Sizes.coverSmallHeight).clearAndSetSemantics {}
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(match.title, style = MaterialTheme.typography.titleMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(match.author.ifBlank { "作者未提供" }, style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Surface(shape = CircleShape, color = colors.surfaceVariant, contentColor = colors.onSurfaceVariant) {
                    Text("${match.books.distinctBy { it.sourceId }.size} 个书源",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs))
                }
            }
            Icon(MiradIcons.ChevronRight, contentDescription = null, tint = colors.onSurfaceVariant,
                modifier = Modifier.size(18.dp))
        }
    }
}
