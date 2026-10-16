package com.mirad.reader.ui.notes

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.mirad.reader.AppContainer
import com.mirad.reader.core.model.NoteExporter
import com.mirad.reader.data.db.entity.BookmarkEntity
import com.mirad.reader.data.db.entity.HighlightEntity
import com.mirad.reader.ui.common.EmptyState
import com.mirad.reader.ui.common.ListRow
import com.mirad.reader.ui.common.RowDivider
import com.mirad.reader.ui.common.ScreenHeader
import com.mirad.reader.ui.common.SegmentedRow
import com.mirad.reader.ui.design.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 全部书籍的划线与书签（跨书浏览 + 一次性导出）。
 */
@Composable
fun NotesScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenBook: (Long) -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var highlights by remember { mutableStateOf<List<HighlightEntity>>(emptyList()) }
    var bookmarks by remember { mutableStateOf<List<BookmarkEntity>>(emptyList()) }
    var titles by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    var chapterTotals by remember { mutableStateOf<Map<Long, Int>>(emptyMap()) }
    var chapterLengths by remember { mutableStateOf<Map<Long, List<Int>>>(emptyMap()) }
    var pendingExport by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    suspend fun reload() = withContext(Dispatchers.IO) {
        highlights = container.notes.allHighlights().sortedByDescending { it.createdAt }
        bookmarks = container.notes.allBookmarks().sortedByDescending { it.createdAt }
        titles = container.books.observeShelf().first().associate { it.book.id to it.book.title }
        chapterTotals = container.books.observeShelf().first()
            .associate { it.book.id to it.book.totalChapters }
        // 每章字符数：用于识别"章号仍在范围内、但正文重排后偏移已漂移"的旧锚点
        chapterLengths = container.books.observeShelf().first().associate { shelfBook ->
            shelfBook.book.id to container.books.getChapters(shelfBook.book.id).map { it.charCount.toInt() }
        }
    }

    /**
     * 旧锚点判定，两层：
     * ① 章号越界——这本书重新识别编码/重新分章后，旧章号已不存在；
     * ② 偏移超出该章当前长度——章号还在但正文重排过，锚点已漂移。
     * 判定逻辑复用 NoteAnchor.isStale（阅读器内面板用的是同一套，文案与口径一致）。
     */
    fun anchorStale(bookId: Long, chapterIndex: Int, charOffset: Int): Boolean {
        val total = chapterTotals[bookId] ?: return false
        if (chapterIndex < 0 || chapterIndex >= total) return true
        val lengths = chapterLengths[bookId] ?: return false
        return com.mirad.reader.core.model.NoteAnchor.isStale(chapterIndex, charOffset, lengths)
    }

    LaunchedEffect(Unit) { reload() }

    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri ->
        val content = pendingExport
        pendingExport = null
        if (uri != null && content != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        // 旧写法在 openOutputStream 返回 null 时也返回 true → 提示"已导出"但什么都没写
                        val out = context.contentResolver.openOutputStream(uri) ?: return@runCatching false
                        out.use { it.write(content.toByteArray()) }
                        true
                    }.getOrDefault(false)
                }
                message = if (ok) "已导出" else "导出失败"
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ScreenHeader(
                title = "笔记与书签",
                subtitle = message ?: "划线 ${highlights.size} · 书签 ${bookmarks.size}",
                onBack = onBack,
                actions = {
                    TextButton(
                        onClick = {
                            scope.launch {
                                if (highlights.isEmpty() && bookmarks.isEmpty()) {
                                    message = "还没有可导出的内容"
                                    return@launch
                                }
                                val full = withContext(Dispatchers.IO) {
                                    buildFullExport(highlights, bookmarks, titles)
                                }
                                pendingExport = full
                                exporter.launch("墨读-全部读书笔记.md")
                            }
                        },
                        enabled = highlights.isNotEmpty() || bookmarks.isNotEmpty()
                    ) { Text("全部导出") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            SegmentedRow(
                options = listOf(0 to "划线 ${highlights.size}", 1 to "书签 ${bookmarks.size}"),
                selected = tab,
                onSelect = { tab = it },
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)
            )
            val list = if (tab == 0) highlights else bookmarks
            if (list.isEmpty()) {
                EmptyState(
                    title = if (tab == 0) "还没有划线或笔记" else "还没有书签",
                    badge = if (tab == 0) "记" else "签",
                    description = if (tab == 0) "阅读时长按选中文字，用工具条上的「划线」或「笔记」"
                    else "阅读时点顶栏的星标即可添加书签",
                    modifier = Modifier.fillMaxSize()
                )
            } else if (tab == 0) {
                LazyColumn(contentPadding = PaddingValues(vertical = Spacing.sm)) {
                    items(highlights, key = { it.id }) { item ->
                        ListRow(
                            title = item.text,
                            subtitle = item.note,
                            meta = "${titles[item.bookId] ?: "未知书籍"} · 第 ${item.chapterIndex + 1} 章" +
                                if (anchorStale(item.bookId, item.chapterIndex, item.startOffset)) {
                                    " · ${com.mirad.reader.core.model.NoteAnchor.STALE_HINT}"
                                } else "",
                            trailing = {
                                TextButton(onClick = {
                                    scope.launch {
                                        withContext(Dispatchers.IO) { container.notes.deleteHighlight(item) }
                                        reload()
                                    }
                                }) { Text("删除") }
                            },
                            onClick = { onOpenBook(item.bookId) }
                        )
                        RowDivider()
                    }
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(vertical = Spacing.sm)) {
                    items(bookmarks, key = { it.id }) { item ->
                        ListRow(
                            title = item.chapterTitle,
                            subtitle = item.snippet,
                            meta = titles[item.bookId] ?: "未知书籍",
                            trailing = {
                                TextButton(onClick = {
                                    scope.launch {
                                        withContext(Dispatchers.IO) { container.notes.deleteBookmark(item) }
                                        reload()
                                    }
                                }) { Text("删除") }
                            },
                            onClick = { onOpenBook(item.bookId) }
                        )
                        RowDivider()
                    }
                }
            }
        }
    }
}

/** 把全部书籍的笔记导出成一份 Markdown（按书分节）。 */
private fun buildFullExport(
    highlights: List<HighlightEntity>,
    bookmarks: List<BookmarkEntity>,
    titles: Map<Long, String>
): String {
    val builder = StringBuilder()
    builder.append("# 墨读 · 全部读书笔记\n\n")
    builder.append("- 划线 ${highlights.size} 条，书签 ${bookmarks.size} 条\n")
    val bookIds = (highlights.map { it.bookId } + bookmarks.map { it.bookId }).distinct().sorted()
    for (bookId in bookIds) {
        val bookHighlights = highlights.filter { it.bookId == bookId }
        val bookBookmarks = bookmarks.filter { it.bookId == bookId }
        builder.append('\n').append("## ").append(titles[bookId] ?: "未知书籍").append('\n')
        bookHighlights.sortedBy { it.chapterIndex }.forEach { item ->
            builder.append("\n> ").append(item.text.replace('\n', ' ')).append('\n')
            if (!item.note.isNullOrBlank()) builder.append("\n笔记：").append(item.note.replace('\n', ' ')).append('\n')
        }
        if (bookBookmarks.isNotEmpty()) {
            builder.append("\n书签：\n")
            bookBookmarks.sortedBy { it.chapterIndex }.forEach {
                builder.append("- ").append(it.chapterTitle).append("：").append(it.snippet.replace('\n', ' ')).append('\n')
            }
        }
    }
    return builder.toString()
}
