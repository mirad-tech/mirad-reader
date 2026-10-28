package com.mirad.reader.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import com.mirad.reader.ui.common.BouncingLazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import com.mirad.reader.ui.common.MiradLinearProgress
import androidx.compose.material3.MaterialTheme
import com.mirad.reader.ui.common.MiradOutlinedButton
import androidx.compose.material3.OutlinedTextField
import com.mirad.reader.ui.common.MiradSlider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.mirad.reader.AppContainer
import com.mirad.reader.data.db.entity.BookEntity
import com.mirad.reader.data.db.entity.ChapterEntity
import com.mirad.reader.data.repo.SearchHit
import com.mirad.reader.data.repo.SearchOptions
import com.mirad.reader.data.repo.SearchProgress
import com.mirad.reader.reader.ReaderSession
import com.mirad.reader.ui.common.*
import com.mirad.reader.ui.common.CardDivider
import com.mirad.reader.ui.common.ListRow
import com.mirad.reader.ui.common.SettingCard
import com.mirad.reader.ui.common.SheetActionButton
import com.mirad.reader.ui.common.BouncingSheetEmpty
import com.mirad.reader.ui.common.SheetScaffold
import com.mirad.reader.ui.design.LocalMiradColors
import com.mirad.reader.ui.design.Spacing
import kotlinx.coroutines.launch

// ---------------------------------------------------------------- 目录

@Composable
fun TocSheet(
    chapters: List<ChapterEntity>,
    currentIndex: Int,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(chapters, query) {
        if (query.isBlank()) chapters
        else chapters.filter { it.title.contains(query, ignoreCase = true) }
    }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = filtered.indexOfFirst { it.index == currentIndex }.coerceAtLeast(0)
    )
    SheetScaffold(
        title = "目录",
        // 用章节标题而不是"第 N 章"序号：分卷/重新编号的书里，序号和标题里的章号会不一致，
        // 用户会以为"当前章节识别错了"。
        subtitle = "共 ${chapters.size} 章 · 当前：${chapters.getOrNull(currentIndex)?.title ?: "第 ${currentIndex + 1} 章"}",
        onDismiss = onDismiss,
        fillHeight = true
    ) {
        Column(modifier = Modifier.padding(horizontal = Spacing.lg)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("搜索章节名") },
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (filtered.isEmpty()) {
            BouncingSheetEmpty("没有匹配的章节")
        } else {
            BouncingLazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f)) {
                items(filtered, key = { it.index }) { chapter ->
                    ListRow(
                        title = chapter.title,
                        meta = if (chapter.isVolume) "卷" else null,
                        selected = chapter.index == currentIndex,
                        titleMaxLines = 1,
                        modifier = Modifier.padding(start = if (chapter.isVolume) Spacing.xs else Spacing.xl),
                        trailing = {
                            if (chapter.index == currentIndex) {
                                Text(
                                    text = "当前",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        },
                        onClick = { onSelect(chapter.index) }
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 进度

@Composable
fun ProgressSheet(
    percent: Float,
    chapterIndex: Int,
    chapterCount: Int,
    chapterTitle: String,
    page: Int,
    pages: Int,
    onJump: (Float) -> Unit,
    onJumpChapter: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var draft by remember(percent) { mutableStateOf(percent) }
    SheetScaffold(
        title = "阅读进度",
        subtitle = "全书 ${(percent * 100).toInt()}%",
        onDismiss = onDismiss
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            SettingCard {
                Text(text = chapterTitle, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "第 ${chapterIndex + 1}/$chapterCount 章 · 第 ${page + 1}/$pages 页",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                CardDivider()
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "${(draft * 100).toInt()}%",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "拖动跳到任意位置",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                MiradSlider(
                    value = draft,
                    onValueChange = { draft = it },
                    onValueChangeFinished = { onJump(draft) }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    MiradOutlinedButton(onClick = { draft = 0f; onJump(0f) }, modifier = Modifier.weight(1f)) {
                        Text("开头")
                    }
                    MiradOutlinedButton(onClick = { draft = 0.5f; onJump(0.5f) }, modifier = Modifier.weight(1f)) {
                        Text("中间")
                    }
                    MiradOutlinedButton(onClick = { draft = 1f; onJump(1f) }, modifier = Modifier.weight(1f)) {
                        Text("结尾")
                    }
                }
                CardDivider()
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    MiradOutlinedButton(
                        onClick = { onJumpChapter(chapterIndex - 1) },
                        enabled = chapterIndex > 0,
                        modifier = Modifier.weight(1f)
                    ) { Text("上一章") }
                    MiradOutlinedButton(
                        onClick = { onJumpChapter(chapterIndex + 1) },
                        enabled = chapterIndex < chapterCount - 1,
                        modifier = Modifier.weight(1f)
                    ) { Text("下一章") }
                }
            }
            Spacer(modifier = Modifier.height(Spacing.sm))
        }
    }
}

// ---------------------------------------------------------------- 全文搜索

@Composable
fun SearchSheet(
    container: AppContainer,
    session: ReaderSession,
    book: BookEntity,
    onDismiss: () -> Unit,
    onJump: (SearchHit) -> Unit
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var scannedChapters by remember { mutableStateOf(0) }
    var searched by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }

    fun runSearch() {
        val keyword = query.trim()
        if (keyword.isEmpty()) return
        results = emptyList()
        searchError = null
        scanning = true
        searched = true
        scannedChapters = 0
        scope.launch {
            try {
                container.search.search(book, session.chapters, keyword, SearchOptions())
                    .collect { progress ->
                        when (progress) {
                            is SearchProgress.Scanning -> scannedChapters = progress.chapterIndex + 1
                            is SearchProgress.Done -> {
                                results = progress.results
                                searchError = null
                                scanning = false
                            }
                            is SearchProgress.Error -> {
                                results = progress.partialResults
                                searchError = progress.message
                                scanning = false
                            }
                        }
                    }
            } catch (e: Exception) {
                searchError = e.message ?: "搜索出错"
                scanning = false
            } finally {
                scanning = false
            }
        }
    }

    val highlightColor = LocalMiradColors.current.searchHighlight

    SheetScaffold(
        title = "全书搜索",
        subtitle = when {
            scanning -> "正在扫描第 $scannedChapters/${session.chapters.size} 章…"
            searched -> "找到 ${results.size} 处"
            else -> "在《${book.title}》中查找"
        },
        onDismiss = onDismiss,
        fillHeight = true,
        actions = { SheetActionButton("搜索", onClick = { runSearch() }, enabled = query.isNotBlank() && !scanning) }
    ) {
        Column(modifier = Modifier.padding(horizontal = Spacing.xl)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("输入要查找的内容") },
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (scanning) {
            MiradLinearProgress(
                progress = {
                    if (session.chapters.isEmpty()) 0f else scannedChapters.toFloat() / session.chapters.size
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.xl)
            )
        }
        when {
            searchError != null -> BouncingSheetEmpty(searchError ?: "搜索失败")
            !searched -> BouncingSheetEmpty("输入关键词后点右上角「搜索」，会在全书逐章查找。")
            scanning && results.isEmpty() -> BouncingSheetEmpty("正在查找…")
            results.isEmpty() -> BouncingSheetEmpty("没有找到「$query」")
            else -> BouncingLazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                items(results) { hit ->
                    ListRow(
                        title = hit.snippet,
                        meta = hit.chapterTitle,
                        subtitleMaxLines = 2,
                        onClick = { onJump(hit) }
                    )
                }
            }
        }
    }
}
