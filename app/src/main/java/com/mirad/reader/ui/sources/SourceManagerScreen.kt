package com.mirad.reader.ui.sources

import com.mirad.reader.ui.common.MiradScaffold as Scaffold
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import com.mirad.reader.core.sources.BookSource
import com.mirad.reader.core.sources.SourceImportReport
import com.mirad.reader.core.sources.SourceRepository
import com.mirad.reader.ui.common.*
import com.mirad.reader.ui.design.Spacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private enum class SourceFilter(val label: String) {
    ALL("全部"), ENABLED("已启用"), DISABLED("已停用"), UNSUPPORTED("不支持")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SourceManagerScreen(repository: SourceRepository, onBack: () -> Unit, onSearch: () -> Unit) {
    val sources by repository.sources.collectAsState()
    val subscriptions by repository.subscriptions.collectAsState()
    val loadError by repository.loadError.collectAsState()
    val scope = rememberCoroutineScope()
    val feedback = rememberFeedbackState("sources")
    var showSubscriptions by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(SourceFilter.ALL) }
    var importDialog by rememberSaveable { mutableStateOf(false) }
    var busyIds by remember { mutableStateOf(emptySet<String>()) }
    var operationError by remember { mutableStateOf<String?>(null) }
    var deleteSource by remember { mutableStateOf<BookSource?>(null) }
    var removeLink by remember { mutableStateOf<String?>(null) }
    var refreshReport by remember { mutableStateOf<SourceImportReport?>(null) }
    val sourceListState = rememberLazyListState()
    val subscriptionListState = rememberLazyListState()

    fun act(id: String, action: String, work: suspend () -> Unit) {
        if (id in busyIds) return
        busyIds = busyIds + id
        operationError = null
        scope.launch {
            try {
                work()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                operationError = sourceActionError(action, error)
            } finally {
                busyIds = busyIds - id
            }
        }
    }

    val visible = remember(sources, query, filter) {
        sources.filter { source ->
            (source.name.contains(query, ignoreCase = true) || source.url.contains(query, ignoreCase = true)) &&
                when (filter) {
                    SourceFilter.ALL -> true
                    SourceFilter.ENABLED -> source.enabled && source.unsupportedReason == null
                    SourceFilter.DISABLED -> !source.enabled && source.unsupportedReason == null
                    SourceFilter.UNSUPPORTED -> source.unsupportedReason != null
                }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { FeedbackHost(feedback) },
        topBar = {
            ScreenHeader("书源管理", onBack = onBack, actions = {
                IconButton(onClick = onSearch) { Icon(Icons.Default.Search, "在线搜书") }
                IconButton(onClick = { importDialog = true }) { Icon(Icons.Default.Add, "导入书源") }
            })
        }
    ) { padding ->
        BouncingLazyColumn(
            modifier = Modifier.fillMaxSize().glassBackdropSource(),
            state = if (showSubscriptions) subscriptionListState else sourceListState,
            contentPadding = sourceListPadding(padding),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    ChoiceChip("书源 ${sources.size}", !showSubscriptions, { showSubscriptions = false })
                    ChoiceChip("导入链接 ${subscriptions.size}", showSubscriptions, { showSubscriptions = true })
                }
            }
            loadError?.let { item { ErrorDetails(it) } }
            operationError?.let { item { ErrorDetails(it) } }
            refreshReport?.let { report ->
                item { SourceImportResult(report) }
            }
            if (showSubscriptions) {
                if (subscriptions.isEmpty()) item { SourceEmpty("暂无导入链接") }
                items(subscriptions, key = { it.url }) { subscription ->
                    val busy = subscription.url in busyIds
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = Spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
                        Text(subscription.url, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "更新于 ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(subscription.fetchedAt))}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            MiradTextButton(onClick = {
                                act(subscription.url, "刷新失败") {
                                    refreshReport = null
                                    refreshReport = repository.importUrl(subscription.url)
                                }
                            }, enabled = !busy, loading = busy) { Text("刷新") }
                            MiradTextButton(onClick = { removeLink = subscription.url }, enabled = !busy, danger = true,
                                modifier = Modifier.semantics { contentDescription = "移除导入链接 ${subscription.url}" }) {
                                Text("移除")
                            }
                        }
                    }
                    HorizontalDivider()
                }
            } else {
                item {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it },
                        label = { Text("筛选书源名称或站点") },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        items(SourceFilter.entries) { choice ->
                            ChoiceChip(choice.label, filter == choice, { filter = choice })
                        }
                    }
                }
                if (visible.isEmpty()) item {
                    SourceEmpty(if (sources.isEmpty()) "暂无书源" else "没有符合筛选的书源")
                    if (sources.isEmpty()) MiradButton(onClick = { importDialog = true }) { Text("导入书源") }
                }
                items(visible, key = { it.id }) { source ->
                    val busy = source.id in busyIds
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = Spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(source.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            Switch(
                                checked = source.enabled && source.unsupportedReason == null,
                                onCheckedChange = { enabled -> act(source.id, "更新失败") { repository.setEnabled(source.id, enabled) } },
                                enabled = !busy && source.unsupportedReason == null,
                                modifier = Modifier.semantics { contentDescription = "启用书源 ${source.name}" }
                            )
                        }
                        Text(source.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            if (source.unsupportedReason != null) "不支持" else if (source.enabled) "已启用" else "已停用",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (source.unsupportedReason != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        source.unsupportedReason?.let { ErrorDetails(it) }
                        MiradTextButton(onClick = { deleteSource = source }, enabled = !busy, danger = true,
                            modifier = Modifier.semantics { contentDescription = "删除书源 ${source.name}" }) { Text("删除") }
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    if (importDialog) SourceImportDialog(repository, onDismiss = { importDialog = false })
    deleteSource?.let { source ->
        MiradDialog(
            onDismissRequest = { deleteSource = null },
            title = { Text("删除书源") },
            text = { Text(source.name) },
            confirmButton = {
                MiradTextButton(danger = true, onClick = {
                    deleteSource = null
                    act(source.id, "删除失败") { repository.remove(source.id); feedback.show("已删除书源") }
                }) { Text("删除") }
            },
            dismissButton = { MiradTextButton(onClick = { deleteSource = null }) { Text("取消") } }
        )
    }
    removeLink?.let { url ->
        MiradDialog(
            onDismissRequest = { removeLink = null },
            title = { Text("移除导入链接") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) { Text(url); Text("已导入的书源会保留。") } },
            confirmButton = {
                MiradTextButton(danger = true, onClick = {
                    removeLink = null
                    act(url, "移除失败") { repository.removeSubscription(url); feedback.show("已移除导入链接") }
                }) { Text("移除") }
            },
            dismissButton = { MiradTextButton(onClick = { removeLink = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun SourceImportDialog(repository: SourceRepository, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var url by rememberSaveable { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var report by remember { mutableStateOf<SourceImportReport?>(null) }
    MiradDialog(
        onDismissRequest = { if (!importing) onDismiss() },
        title = { Text("导入书源") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; error = null; report = null },
                    label = { Text("书源链接") },
                    modifier = Modifier.fillMaxWidth(), maxLines = 4, enabled = !importing,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
                error?.let { ErrorDetails(it) }
                report?.let { SourceImportResult(it) }
            }
        },
        confirmButton = {
            MiradButton(enabled = url.isNotBlank() && !importing, loading = importing, onClick = {
                importing = true
                error = null
                report = null
                scope.launch {
                    try {
                        report = repository.importUrl(url.trim())
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (failure: Exception) {
                        error = sourceActionError("导入失败", failure)
                    } finally {
                        importing = false
                    }
                }
            }) { Text(if (importing) "导入中" else "导入") }
        },
        dismissButton = { MiradTextButton(enabled = !importing, onClick = onDismiss) { Text(if (report == null) "取消" else "完成") } }
    )
}

@Composable
private fun SourceImportResult(report: SourceImportReport) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text("新增 ${report.added} · 更新 ${report.updated} · 无效 ${report.invalid} · 不支持 ${report.unsupported}",
            style = MaterialTheme.typography.bodyMedium)
        report.messages.forEach { message ->
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
