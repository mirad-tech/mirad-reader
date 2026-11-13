package com.mirad.reader.ui.notes

import android.util.Log
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.runtime.key
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import com.mirad.reader.ui.common.BouncingLazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.mirad.reader.ui.common.MiradScaffold as Scaffold
import androidx.compose.material3.Text
import com.mirad.reader.ui.common.MiradTextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mirad.reader.AppContainer
import com.mirad.reader.R
import com.mirad.reader.data.db.entity.BookmarkEntity
import com.mirad.reader.data.db.entity.HighlightEntity
import com.mirad.reader.core.model.NoteAnchor
import com.mirad.reader.ui.common.glassBackdropSource
import com.mirad.reader.ui.common.EmptyState
import com.mirad.reader.ui.common.FeedbackAction
import com.mirad.reader.ui.common.FeedbackHost
import com.mirad.reader.ui.common.FeedbackKind
import com.mirad.reader.ui.common.LocalPagerPageActive
import com.mirad.reader.ui.common.rememberFeedbackState
import com.mirad.reader.ui.common.ScreenHeader
import com.mirad.reader.ui.common.SegmentedRow
import com.mirad.reader.ui.design.LocalMiradColors
import com.mirad.reader.ui.design.Spacing
import com.mirad.reader.ui.theme.MiradSerif
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.mirad.reader.ui.common.rememberMarkdownExporter

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
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    var loaded by remember { mutableStateOf(false) }
    var highlights by remember { mutableStateOf<List<HighlightEntity>>(emptyList()) }
    var bookmarks by remember { mutableStateOf<List<BookmarkEntity>>(emptyList()) }
    var titles by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    var chapterTotals by remember { mutableStateOf<Map<Long, Int>>(emptyMap()) }
    var chapterLengths by remember { mutableStateOf<Map<Long, List<Int>>>(emptyMap()) }
    var changingNote by remember { mutableStateOf(false) }
    val feedback = rememberFeedbackState("notes")
    val pageActive = LocalPagerPageActive.current
    val stillOnNotes by rememberUpdatedState(pageActive)
    var pageGeneration by remember { mutableIntStateOf(0) }
    LaunchedEffect(pageActive) {
        if (!pageActive) {
            pageGeneration++
            feedback.dismiss()
        }
    }

    suspend fun reloadNotes() {
        val (newHighlights, newBookmarks) = withContext(Dispatchers.IO) {
            container.notes.allHighlights().sortedByDescending { it.createdAt } to
                container.notes.allBookmarks().sortedByDescending { it.createdAt }
        }
        highlights = newHighlights
        bookmarks = newBookmarks
    }

    fun <T> changeNotes(failureMessage: String, change: suspend () -> T, onSuccess: (T, Boolean) -> Unit) {
        if (changingNote || !stillOnNotes) return
        changingNote = true
        val generation = pageGeneration
        // 写入随应用完成，反馈只发送给发起操作时的笔记页。
        val operation = container.appScope.async {
            try {
                Result.success(change())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w("NotesScreen", failureMessage, error)
                Result.failure(error)
            }
        }
        scope.launch {
            try {
                val result = operation.await()
                val refreshed = try {
                    reloadNotes()
                    true
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.w("NotesScreen", "刷新笔记列表失败", error)
                    false
                }
                if (stillOnNotes && generation == pageGeneration) {
                    result.fold(
                        onSuccess = { onSuccess(it, refreshed) },
                        onFailure = {
                            val message = if (refreshed) failureMessage else "$failureMessage，列表刷新失败"
                            feedback.show(message, FeedbackKind.ERROR)
                        }
                    )
                }
            } finally {
                changingNote = false
            }
        }
    }

    fun <T : Any> deleteNote(
        label: String,
        remove: suspend () -> T?,
        restore: suspend (T) -> Long
    ) {
        changeNotes("删除${label}失败", remove) { removed, refreshed ->
            if (removed != null) {
                feedback.show(
                    text = if (refreshed) "已删除$label" else "已删除$label，列表刷新失败",
                    kind = FeedbackKind.SUCCESS,
                    timeoutMillis = 5000L,
                    action = FeedbackAction("撤销") {
                        changeNotes("撤销失败", { restore(removed) }) { _, reloaded ->
                            feedback.show(
                                if (reloaded) "已撤销" else "已撤销，列表刷新失败",
                                if (reloaded) FeedbackKind.SUCCESS else FeedbackKind.ERROR
                            )
                        }
                    }
                )
            } else if (!refreshed) {
                feedback.show("列表刷新失败", FeedbackKind.ERROR)
            }
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
        return NoteAnchor.isStale(chapterIndex, charOffset, lengths)
    }

    LaunchedEffect(Unit) {
        val (shelf, lengths) = withContext(Dispatchers.IO) {
            val entries = container.books.observeShelf().first()
            entries to entries.associate { entry ->
                entry.book.id to container.books.getChapters(entry.book.id).map { it.charCount.toInt() }
            }
        }
        titles = shelf.associate { it.book.id to it.book.title }
        chapterTotals = shelf.associate { it.book.id to it.book.totalChapters }
        chapterLengths = lengths
        reloadNotes()
        loaded = true
    }

    val exporter = rememberMarkdownExporter { ok ->
        feedback.show(if (ok) "已导出" else "导出失败", if (ok) FeedbackKind.SUCCESS else FeedbackKind.ERROR)
    }

    if (!loaded) {
        Scaffold(topBar = { ScreenHeader("笔记", onBack = onBack) }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                androidx.compose.material3.CircularProgressIndicator()
            }
        }
        return
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { FeedbackHost(feedback, actionEnabled = !changingNote) },
        topBar = {
            ScreenHeader(
                title = "笔记",
                onBack = onBack,
                actions = {
                    MiradTextButton(
                        onClick = {
                            scope.launch {
                                if (highlights.isEmpty() && bookmarks.isEmpty()) {
                                    feedback.show("还没有可导出的内容")
                                    return@launch
                                }
                                val full = withContext(Dispatchers.IO) {
                                    buildFullExport(highlights, bookmarks, titles)
                                }
                                exporter("墨读-全部读书笔记.md", full)
                            }
                        },
                        enabled = !changingNote && (highlights.isNotEmpty() || bookmarks.isNotEmpty())
                    ) { Text("全部导出") }
                }
            )
        }
    ) { padding ->
        key(tab) {
            val direction = LocalLayoutDirection.current
            val contentPadding = PaddingValues(
                start = padding.calculateStartPadding(direction) + Spacing.lg,
                end = padding.calculateEndPadding(direction) + Spacing.lg,
                top = padding.calculateTopPadding() + Spacing.sm,
                bottom = padding.calculateBottomPadding() + Spacing.sm
            )
            BouncingLazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().glassBackdropSource(),
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                item(key = "notes-tabs") {
                    SegmentedRow(
                        options = listOf(0 to "划线 ${highlights.size}", 1 to "书签 ${bookmarks.size}"),
                        selected = tab,
                        onSelect = { tab = it },
                        modifier = Modifier.padding(vertical = Spacing.sm)
                    )
                }
                val list = if (tab == 0) highlights else bookmarks
                if (list.isEmpty()) {
                    item(key = "notes-empty") {
                        NotesEmptyState(bookmarks = tab == 1, contentPadding = contentPadding)
                    }
                } else if (tab == 0) {
                    items(highlights, key = { it.id }) { item ->
                        NoteCard(
                            quote = item.text,
                            note = item.note,
                            location = "${titles[item.bookId] ?: "未知书籍"} · 第 ${item.chapterIndex + 1} 章",
                            stale = anchorStale(item.bookId, item.chapterIndex, item.startOffset),
                            accent = LocalMiradColors.current.noteHighlight.copy(alpha = 1f),
                            deleteEnabled = pageActive && !changingNote,
                            onOpen = { onOpenBook(item.bookId) },
                            onDelete = {
                                deleteNote("划线", { container.notes.deleteHighlightForUndo(item.id) },
                                    container.notes::addHighlight)
                            }
                        )
                    }
                } else {
                    items(bookmarks, key = { it.id }) { item ->
                        NoteCard(
                            heading = item.chapterTitle,
                            quote = item.snippet,
                            note = item.note,
                            location = "${titles[item.bookId] ?: "未知书籍"} · 第 ${item.chapterIndex + 1} 章",
                            stale = anchorStale(item.bookId, item.chapterIndex, item.charOffset),
                            accent = MaterialTheme.colorScheme.primary,
                            deleteEnabled = pageActive && !changingNote,
                            onOpen = { onOpenBook(item.bookId) },
                            onDelete = {
                                deleteNote("书签", { container.notes.deleteBookmarkForUndo(item.id) },
                                    container.notes::addBookmark)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NoteCard(
    quote: String,
    note: String?,
    location: String,
    stale: Boolean,
    accent: Color,
    deleteEnabled: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    heading: String? = null
) {
    val shape = MaterialTheme.shapes.medium
    val colors = MaterialTheme.colorScheme
    var offsetPx by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var dismissed by remember { mutableStateOf(false) }
    val currentDelete by rememberUpdatedState(onDelete)
    val canDelete by rememberUpdatedState(deleteEnabled)
    val translation by animateFloatAsState(
        offsetPx, if (dragging) snap() else tween(160), label = "noteSwipe"
    )
    LaunchedEffect(deleteEnabled, dismissed) {
        // 删除失败后原卡片仍在列表里，回到可以再次操作的位置。
        if (deleteEnabled && dismissed) {
            dismissed = false
            offsetPx = 0f
        }
    }
    Box(
        modifier = Modifier.fillMaxWidth().clip(shape).pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragStart = { dragging = canDelete && !dismissed },
                onHorizontalDrag = { _, amount ->
                    if (dragging) offsetPx = (offsetPx + amount).coerceIn(-size.width.toFloat(), 0f)
                },
                onDragCancel = { dragging = false; offsetPx = 0f },
                onDragEnd = {
                    val shouldDelete = dragging && canDelete && -offsetPx > size.width / 3f
                    dragging = false
                    if (shouldDelete) {
                        offsetPx = -size.width.toFloat()
                        dismissed = true
                        currentDelete()
                    } else {
                        offsetPx = 0f
                    }
                }
            )
        }
    ) {
        if (translation < 0f) {
            Box(
                Modifier.matchParentSize().background(colors.errorContainer).clearAndSetSemantics {}
                    .padding(horizontal = Spacing.lg),
                contentAlignment = Alignment.CenterEnd
            ) {
                Text("删除", color = colors.onErrorContainer, style = MaterialTheme.typography.labelLarge)
            }
        }
        Surface(
            onClick = onOpen,
            modifier = Modifier.fillMaxWidth().graphicsLayer { translationX = translation }
                .semantics {
                    if (deleteEnabled && !dismissed) {
                        customActions = listOf(CustomAccessibilityAction("删除") {
                            currentDelete()
                            true
                        })
                    }
                },
            shape = shape, color = colors.surface, border = BorderStroke(1.dp, colors.outlineVariant)
        ) {
            Row(
                modifier = Modifier.padding(Spacing.md).height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(accent, CircleShape))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    if (!heading.isNullOrBlank()) {
                        Text(heading, style = MaterialTheme.typography.titleSmall,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (quote.isNotBlank()) {
                        Text(quote, style = MaterialTheme.typography.bodyLarge.copy(fontFamily = MiradSerif),
                            maxLines = 4, overflow = TextOverflow.Ellipsis)
                    }
                    if (!note.isNullOrBlank()) {
                        Text(note, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                            maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    Text(location, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    if (stale) {
                        Text(NoteAnchor.STALE_HINT, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
internal fun LazyItemScope.NotesEmptyState(bookmarks: Boolean, contentPadding: PaddingValues) {
    val windowHeight = LocalWindowInfo.current.containerSize.height
    val listHeight = with(LocalDensity.current) { windowHeight.toDp() } -
        contentPadding.calculateTopPadding() - contentPadding.calculateBottomPadding()
    // 分段栏、插画、两行提示和留白需要约 320 dp，短窗口优先显示文字。
    val showIllustration = listHeight >= 320.dp
    Box(contentAlignment = Alignment.Center) {
        if (showIllustration) Spacer(Modifier.fillParentMaxHeight(0.75f))
        EmptyState(
            title = if (bookmarks) "还没有书签" else "还没有划线或笔记",
            description = if (bookmarks) "阅读时点顶栏的「书签」即可添加"
                else "阅读时长按选中文字，用工具条上的「划线」或「笔记」",
            modifier = Modifier.fillMaxWidth(),
            illustrationRes = if (showIllustration) R.drawable.ic_empty_notes else null,
            verticalPadding = if (showIllustration) Spacing.xxxl else Spacing.sm
        )
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
