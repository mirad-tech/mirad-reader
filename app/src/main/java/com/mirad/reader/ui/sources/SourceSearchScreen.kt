package com.mirad.reader.ui.sources

import com.mirad.reader.ui.common.MiradScaffold as Scaffold
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import com.mirad.reader.core.sources.*
import com.mirad.reader.ui.common.*
import com.mirad.reader.ui.design.Spacing
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
    val state by search.state.collectAsState()
    val comparison by search.comparison.collectAsState()
    val sources by repository.sources.collectAsState()
    val loadError by repository.loadError.collectAsState()
    val feedback = rememberFeedbackState("source-search")
    val keyboard = LocalSoftwareKeyboardController.current
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
    LaunchedEffect(state.query) { searchListState.scrollToItem(0) }

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

    fun beginSearch() {
        if (query.isBlank()) return
        keyboard?.hide()
        enqueueError = null
        search.search(query.trim())
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
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        OutlinedTextField(
                            value = query, onValueChange = { query = it }, label = { Text("书名或作者") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { beginSearch() })
                        )
                        MiradButton(enabled = state.running || query.isNotBlank(),
                            onClick = { if (state.running) search.cancelSearch() else beginSearch() }) {
                            Text(if (state.running) "取消搜索" else "搜索")
                        }
                    }
                }
                if (state.totalSources > 0) item {
                    SourceProgress(if (state.running) "正在搜索" else "已搜索", state.completedSources, state.totalSources, state.running)
                }
                state.message?.let { item { Text(it, style = MaterialTheme.typography.bodyMedium) } }
                if (sources.none { it.enabled && it.unsupportedReason == null }) item {
                    SourceEmpty("没有可用的已启用书源")
                    MiradButton(onClick = onSources) { Text("管理书源") }
                } else if (state.query.isNotBlank() && !state.running && state.matches.isEmpty()) item {
                    SourceEmpty("没有找到匹配的书籍")
                }
                items(state.matches, key = { it.key }) { match ->
                    Column(
                        Modifier.fillMaxWidth()
                            .clickable(role = Role.Button, onClickLabel = "比较《${match.title}》的书源") {
                                keyboard?.hide()
                                search.compare(match)
                            }.padding(vertical = Spacing.md),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
                        Text(match.title, style = MaterialTheme.typography.titleMedium)
                        Text(match.author.ifBlank { "作者未提供" }, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(if (match.author.isBlank()) match.books.map { it.sourceName }.distinct().joinToString("、")
                            else "${match.books.size} 个书源",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider()
                }
                if (state.failures.isNotEmpty()) item {
                    Text("搜索失败 ${state.failures.size} 个书源", style = MaterialTheme.typography.titleSmall)
                }
                items(state.failures, key = { "failure:${it.sourceId}" }) { SourceFailureRow(it) }
            }
        }
    }
    catalog?.let { SourceCatalogSheet(it, onDismiss = { catalog = null }) }
}
