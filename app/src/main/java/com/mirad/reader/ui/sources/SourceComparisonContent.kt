package com.mirad.reader.ui.sources

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mirad.reader.core.sources.SourceCatalog
import com.mirad.reader.core.sources.SourceComparisonEntry
import com.mirad.reader.core.sources.SourceComparisonState
import com.mirad.reader.ui.common.BouncingLazyColumn
import com.mirad.reader.ui.common.ErrorDetails
import com.mirad.reader.ui.common.MiradButton
import com.mirad.reader.ui.common.MiradTextButton
import com.mirad.reader.ui.common.SheetScaffold
import com.mirad.reader.ui.design.Spacing

internal fun rankedCatalogs(entries: List<SourceComparisonEntry>): List<SourceComparisonEntry> =
    entries.filter { it.catalog != null && it.error == null }
        .sortedWith(compareByDescending<SourceComparisonEntry> { it.catalog!!.chapters.size }
            .thenBy { it.source.name })

internal fun comparisonKey(entry: SourceComparisonEntry): String = "${entry.source.id}|${entry.book.bookUrl}"

internal fun LazyListScope.sourceComparisonItems(
    state: SourceComparisonState,
    expanded: Boolean,
    submitting: String?,
    onExpand: () -> Unit,
    onCatalog: (SourceCatalog) -> Unit,
    onDownload: (SourceComparisonEntry) -> Unit
) {
    val match = state.match ?: return
    val ranked = rankedCatalogs(state.entries)
    val unverified = state.entries.filter { it.catalog == null || it.error != null }
    item {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(match.title, style = MaterialTheme.typography.headlineSmall)
            Text(match.author.ifBlank { "作者未提供" }, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            SourceProgress(if (state.running) "目录读取进度" else "已处理书源", state.completedSources, state.totalSources, state.running)
        }
    }
    if (ranked.isNotEmpty()) item {
        Text("已核实 ${ranked.size} 个书源 · 按完整目录章数排序", style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = Spacing.sm))
    }
    itemsIndexed(if (expanded) ranked else ranked.take(5), key = { _, entry -> "ranked:${comparisonKey(entry)}" }) { index, entry ->
        val catalog = requireNotNull(entry.catalog)
        val blockedChapters = catalog.chapters.count { it.restricted || it.isVolume }
        Column(
            Modifier.fillMaxWidth().padding(vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            Text("${index + 1}. ${entry.source.name}", style = MaterialTheme.typography.titleMedium)
            Text(catalog.book.title, style = MaterialTheme.typography.bodyMedium)
            Text("${catalog.book.author.ifBlank { "作者未提供" }} · ${catalog.chapters.size} 章",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            entry.book.latestChapter.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (blockedChapters > 0) Text("含 $blockedChapters 个不可下载章节",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            ComparisonActions(
                onCatalog = { onCatalog(catalog) },
                onDownload = { onDownload(entry) },
                submitting = submitting == comparisonKey(entry),
                enabled = submitting == null && catalog.chapters.isNotEmpty() && blockedChapters == 0
            )
        }
        HorizontalDivider()
    }
    if (ranked.size > 5) item {
        MiradTextButton(onClick = onExpand) { Text(if (expanded) "收起，仅看前 5 个" else "展开全部 ${ranked.size} 个书源") }
    }
    if (unverified.isNotEmpty() || state.failures.isNotEmpty()) item {
        Text("未核实目录", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = Spacing.sm))
    }
    items(unverified, key = { "unverified:${comparisonKey(it)}" }) { entry ->
        Column(Modifier.fillMaxWidth().padding(vertical = Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(entry.source.name, style = MaterialTheme.typography.titleMedium)
            val error = entry.error ?: state.failures.firstOrNull { it.sourceId == entry.source.id }?.message
            if (error != null) ErrorDetails(error)
            else Text(if (state.running) "正在读取目录" else "目录未核实",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider()
    }
    items(state.failures.filter { failure -> unverified.none { it.source.id == failure.sourceId } },
        key = { "failure:${it.sourceId}" }) { SourceFailureRow(it) }
    if (!state.running && ranked.isEmpty()) item { SourceEmpty("暂无可下载的完整目录") }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ComparisonActions(onCatalog: () -> Unit, onDownload: () -> Unit, submitting: Boolean, enabled: Boolean) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        MiradTextButton(onClick = onCatalog) { Text("查看目录") }
        MiradButton(onClick = onDownload, enabled = enabled, loading = submitting) { Text("下载整本") }
    }
}

@Composable
internal fun SourceCatalogSheet(catalog: SourceCatalog, onDismiss: () -> Unit) {
    SheetScaffold(
        title = catalog.book.title,
        subtitle = "${catalog.book.sourceName} · ${catalog.chapters.size} 章",
        onDismiss = onDismiss,
        fillHeight = true,
        actions = { MiradTextButton(onClick = onDismiss) { Text("关闭") } }
    ) {
        BouncingLazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(horizontal = Spacing.xl)) {
            itemsIndexed(catalog.chapters, key = { index, _ -> index }) { index, chapter ->
                Column(Modifier.fillMaxWidth().padding(vertical = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text("${index + 1}. ${chapter.title}", style = MaterialTheme.typography.bodyMedium)
                    if (chapter.restricted) Text("受限章节", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error)
                }
                HorizontalDivider()
            }
        }
    }
}
