package com.mirad.reader.ui.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import com.mirad.reader.ui.common.BouncingLazyColumn
import androidx.compose.foundation.lazy.items
import com.mirad.reader.ui.common.MiradTextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.mirad.reader.AppContainer
import com.mirad.reader.core.model.NoteAnchor
import com.mirad.reader.core.model.NoteExporter
import com.mirad.reader.data.db.entity.BookEntity
import com.mirad.reader.reader.ReaderSession
import com.mirad.reader.ui.common.*
import com.mirad.reader.ui.common.ListRow
import com.mirad.reader.ui.common.SegmentedRow
import com.mirad.reader.ui.common.SheetActionButton
import com.mirad.reader.ui.common.BouncingSheetEmpty
import com.mirad.reader.ui.common.SheetScaffold
import com.mirad.reader.ui.design.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.mirad.reader.ui.common.rememberMarkdownExporter

// ---------------------------------------------------------------- 笔记与书签

@Composable
fun NotesSheet(
    container: AppContainer,
    session: ReaderSession,
    book: BookEntity,
    onDismiss: () -> Unit,
    onJump: (chapterIndex: Int, offset: Int) -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val highlights by container.notes.observeHighlights(book.id).collectAsState(initial = emptyList())
    val bookmarks by container.notes.observeBookmarks(book.id).collectAsState(initial = emptyList())
    var tab by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    // 删除划线/书签是破坏性操作，先确认再执行（保留动作供确认后调用）
    var pendingDelete by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    // 章节长度：用于判断划线/书签是否已失效（重新识别编码或重新分章后章节会变）
    val chapterCharCounts = remember(session.chapters) {
        session.chapters.map { it.charCount.toInt() }
    }

    val exporter = rememberMarkdownExporter { ok ->
        message = if (ok) "已导出到所选文件" else "导出失败"
    }

    fun startExport() {
        scope.launch {
            val markdown = withContext(Dispatchers.IO) {
                val chapters = session.chapters
                val titleOf = { index: Int -> chapters.firstOrNull { it.index == index }?.title.orEmpty() }
                NoteExporter.toMarkdown(
                    bookTitle = book.title,
                    author = book.author,
                    highlights = container.notes.allHighlights(book.id).map {
                        NoteExporter.ExportHighlight(it.chapterIndex, titleOf(it.chapterIndex), it.startOffset, it.text, it.note)
                    },
                    bookmarks = container.notes.allBookmarks().filter { it.bookId == book.id }.map {
                        NoteExporter.ExportBookmark(it.chapterIndex, titleOf(it.chapterIndex).ifBlank { it.chapterTitle }, it.charOffset, it.snippet)
                    }
                )
            }
            if (markdown.isEmpty()) {
                message = "还没有可导出的划线或书签"
            } else {
                exporter("${book.title}-读书笔记.md", markdown)
            }
        }
    }

    pendingDelete?.let { pending ->
        com.mirad.reader.ui.common.MiradDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除${pending.first}") },
            text = { Text("删除后无法恢复，确定要删除吗？") },
            confirmButton = {
                MiradTextButton(danger = true, onClick = {
                    val action = pending.second
                    pendingDelete = null
                    action()
                }) { Text("删除") }
            },
            dismissButton = {
                MiradTextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }

    SheetScaffold(
        title = "笔记与书签",
        subtitle = "划线 ${highlights.size} · 书签 ${bookmarks.size}" + (message?.let { " · $it" } ?: ""),
        onDismiss = onDismiss,
        fillHeight = true,
        actions = {
            SheetActionButton("导出", onClick = { startExport() }, enabled = highlights.isNotEmpty() || bookmarks.isNotEmpty())
        }
    ) {
        Column(modifier = Modifier.padding(horizontal = Spacing.xl)) {
            SegmentedRow(
                options = listOf(0 to "划线 ${highlights.size}", 1 to "书签 ${bookmarks.size}"),
                selected = tab,
                onSelect = { tab = it }
            )
        }
        when (tab) {
            0 -> if (highlights.isEmpty()) {
                BouncingSheetEmpty("还没有划线或笔记。\n长按选中文字后点工具条上的「划线」或「笔记」。")
            } else {
                BouncingLazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    items(highlights, key = { it.id }) { highlight ->
                        val stale = NoteAnchor.isStale(
                            highlight.chapterIndex,
                            highlight.startOffset,
                            chapterCharCounts
                        )
                        ListRow(
                            title = highlight.text,
                            subtitle = highlight.note,
                            meta = if (stale) {
                                "第 ${highlight.chapterIndex + 1} 章 · ${NoteAnchor.STALE_HINT}"
                            } else {
                                "第 ${highlight.chapterIndex + 1} 章"
                            },
                            trailing = {
                                SheetActionButton("删除", onClick = {
                                    pendingDelete = "划线" to { scope.launch { session.deleteHighlight(highlight) } }
                                })
                            },
                            onClick = if (stale) null else ({ onJump(highlight.chapterIndex, highlight.startOffset) })
                        )
                    }
                }
            }
            else -> if (bookmarks.isEmpty()) {
                BouncingSheetEmpty("还没有书签。\n点顶栏的星标即可把当前位置加入书签。")
            } else {
                BouncingLazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    items(bookmarks, key = { it.id }) { bookmark ->
                        val stale = NoteAnchor.isStale(
                            bookmark.chapterIndex,
                            bookmark.charOffset,
                            chapterCharCounts
                        )
                        ListRow(
                            title = bookmark.chapterTitle,
                            subtitle = bookmark.snippet,
                            meta = if (stale) NoteAnchor.STALE_HINT else null,
                            trailing = {
                                SheetActionButton(
                                    "删除",
                                    onClick = {
                                        pendingDelete = "书签" to {
                                            scope.launch { container.notes.deleteBookmark(bookmark) }
                                        }
                                    }
                                )
                            },
                            onClick = if (stale) null else ({ onJump(bookmark.chapterIndex, bookmark.charOffset) })
                        )
                    }
                }
            }
        }
    }
}
