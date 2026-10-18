package com.mirad.reader.ui.reader

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.mirad.reader.ui.common.MiradLinearProgress
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import com.mirad.reader.ui.common.MiradSlider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.mirad.reader.AppContainer
import com.mirad.reader.core.model.EpubLayoutMode
import com.mirad.reader.core.model.NoteAnchor
import com.mirad.reader.core.model.NoteExporter
import com.mirad.reader.core.model.OrientationKey
import com.mirad.reader.core.model.PageAnim
import com.mirad.reader.core.model.PageMode
import com.mirad.reader.core.model.ReaderConfig
import com.mirad.reader.core.model.TapZoneKey
import com.mirad.reader.core.model.TextAlignKey
import com.mirad.reader.data.db.entity.BookEntity
import com.mirad.reader.data.db.entity.ChapterEntity
import com.mirad.reader.data.repo.SearchHit
import com.mirad.reader.data.repo.SearchOptions
import com.mirad.reader.data.repo.SearchProgress
import com.mirad.reader.reader.ReaderSession
import com.mirad.reader.tts.TtsBus
import com.mirad.reader.tts.TtsController
import com.mirad.reader.ui.common.*
import com.mirad.reader.ui.common.BackgroundSection
import com.mirad.reader.ui.common.CardDivider
import com.mirad.reader.ui.common.ChoiceRow
import com.mirad.reader.ui.common.FontSection
import com.mirad.reader.ui.common.LabeledSlider
import com.mirad.reader.ui.common.ListRow
import com.mirad.reader.ui.common.SegmentedRow
import com.mirad.reader.ui.common.SettingCard
import com.mirad.reader.ui.common.SettingSwitchRow
import com.mirad.reader.ui.common.SheetActionButton
import com.mirad.reader.ui.common.SheetEmpty
import com.mirad.reader.ui.common.SheetScaffold
import com.mirad.reader.ui.design.LocalMiradColors
import com.mirad.reader.ui.design.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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
            SheetEmpty("没有匹配的章节")
        } else {
            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f)) {
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
                    OutlinedButton(onClick = { draft = 0f; onJump(0f) }, modifier = Modifier.weight(1f)) {
                        Text("开头")
                    }
                    OutlinedButton(onClick = { draft = 0.5f; onJump(0.5f) }, modifier = Modifier.weight(1f)) {
                        Text("中间")
                    }
                    OutlinedButton(onClick = { draft = 1f; onJump(1f) }, modifier = Modifier.weight(1f)) {
                        Text("结尾")
                    }
                }
                CardDivider()
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    OutlinedButton(
                        onClick = { onJumpChapter(chapterIndex - 1) },
                        enabled = chapterIndex > 0,
                        modifier = Modifier.weight(1f)
                    ) { Text("上一章") }
                    OutlinedButton(
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

// ---------------------------------------------------------------- 阅读设置

@Composable
fun ReaderSettingsSheet(
    container: AppContainer,
    bookId: Long,
    isEpub: Boolean = false,
    config: ReaderConfig,
    onChange: ((ReaderConfig) -> ReaderConfig) -> Unit,
    onResetGlobal: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var fonts by remember { mutableStateOf(container.fonts.available()) }

    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val imported = withContext(Dispatchers.IO) { container.fonts.import(uri) }
                if (imported != null) {
                    fonts = withContext(Dispatchers.IO) { container.fonts.available() }
                    onChange {
                        it.copy(
                            fontFamily = com.mirad.reader.core.model.FontFamilyKey.CUSTOM,
                            customFontAsset = null,
                            customFontPath = imported.filePath
                        )
                    }
                }
            }
        }
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val target = File(container.paths.backgroundsDir, "bg_$bookId.jpg")
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        (context.contentResolver.openInputStream(uri) ?: return@runCatching false).use { input ->
                            target.outputStream().use { output -> input.copyTo(output) }
                        }
                        true
                    }.getOrDefault(false)
                }
                if (ok) {
                    onChange {
                        it.copy(
                            backgroundType = com.mirad.reader.core.model.BackgroundType.IMAGE,
                            backgroundImageUri = target.absolutePath
                        )
                    }
                }
            }
        }
    }

    var category by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<SettingsCategory?>(null) }
    val scrollStates = SettingsCategory.entries.associateWith { rememberScrollState() }
    val homeScroll = rememberScrollState()
    androidx.activity.compose.BackHandler(category != null) { category = null }
    DisposableEffect(bookId) {
        onDispose { container.settings.flushBookConfig(bookId, container.appScope) }
    }
    CompositionLocalProvider(LocalSettingsCommit provides { container.settings.flushBookConfig(bookId, container.appScope) }) {
    SheetScaffold(
        title = category?.title ?: "阅读设置",
        subtitle = "当前书籍",
        onDismiss = onDismiss,
        fillHeight = true,
        maxHeightFraction = 0.62f,
        actions = { if (category != null) SheetActionButton("返回", { category = null }) }
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(category?.let { scrollStates.getValue(it) } ?: homeScroll).padding(horizontal = Spacing.xl)) {
            val selected = category
            if (selected == null) {
                SettingCard { FontSizeControl(config, onChange) }
                BrightnessSettings(config, onChange)
                SettingCard {
                    listOf(SettingsCategory.TYPOGRAPHY, SettingsCategory.THEME, SettingsCategory.INTERACTION, SettingsCategory.SCREEN)
                        .plus(if (isEpub) listOf(SettingsCategory.EPUB) else emptyList())
                        .forEach { item -> SettingNavRow(item.title, onClick = { category = item }) }
                }
                OutlinedButton(onClick = onResetGlobal, modifier = Modifier.fillMaxWidth()) { Text("恢复全局默认设置") }
            } else {
                ReaderSettingsContent(selected, config, onChange, fonts,
                    onImportFont = { fontPicker.launch(arrayOf("*/*")) },
                    onDeleteFont = { font -> scope.launch {
                        withContext(Dispatchers.IO) { container.fonts.delete(font) }
                        fonts = withContext(Dispatchers.IO) { container.fonts.available() }
                    } },
                    onPickImage = { imagePicker.launch(arrayOf("image/*")) })
            }
            Spacer(Modifier.height(Spacing.lg))
        }
    }
    }
}

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
    var pendingExport by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    // 删除划线/书签是破坏性操作，先确认再执行（保留动作供确认后调用）
    var pendingDelete by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    // 章节长度：用于判断划线/书签是否已失效（重新识别编码或重新分章后章节会变）
    val chapterCharCounts = remember(session.chapters) {
        session.chapters.map { it.charCount.toInt() }
    }

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
                message = if (ok) "已导出到所选文件" else "导出失败"
            }
        }
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
                pendingExport = markdown
                exporter.launch("${book.title}-读书笔记.md")
            }
        }
    }

    pendingDelete?.let { pending ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除${pending.first}") },
            text = { Text("删除后无法恢复，确定要删除吗？") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    val action = pending.second
                    pendingDelete = null
                    action()
                }) { Text("删除") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pendingDelete = null }) { Text("取消") }
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
                SheetEmpty("还没有划线或笔记。\n长按选中文字后点工具条上的「划线」或「笔记」。")
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
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
                SheetEmpty("还没有书签。\n点顶栏的星标即可把当前位置加入书签。")
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
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

    fun runSearch() {
        val keyword = query.trim()
        if (keyword.isEmpty()) return
        results = emptyList()
        scanning = true
        searched = true
        scannedChapters = 0
        scope.launch {
            container.search.search(book, session.chapters, keyword, SearchOptions())
                .collect { progress ->
                    when (progress) {
                        is SearchProgress.Scanning -> scannedChapters = progress.chapterIndex + 1
                        is SearchProgress.Done -> {
                            results = progress.results
                            scanning = false
                        }
                    }
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
            !searched -> SheetEmpty("输入关键词后点右上角「搜索」，会在全书逐章查找。")
            scanning && results.isEmpty() -> SheetEmpty("正在查找…")
            results.isEmpty() -> SheetEmpty("没有找到「$query」")
            else -> LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
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

// ---------------------------------------------------------------- 朗读

/** 跳到系统 TTS 设置；老机型没有该页面时退回应用详情页。 */
fun openTtsSettings(context: android.content.Context) {
    val candidates = listOf(
        android.content.Intent("com.android.settings.TTS_SETTINGS"),
        android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS),
        android.content.Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.fromParts("package", context.packageName, null)
        )
    )
    for (intent in candidates) {
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(intent) }.isSuccess) return
    }
}

@Composable
fun TtsSheet(
    tts: TtsController,
    config: ReaderConfig,
    onChange: ((ReaderConfig) -> ReaderConfig) -> Unit,
    onStart: (resume: Boolean) -> Unit,
    onClose: () -> Unit
) {
    val state by TtsBus.state.collectAsState()
    SheetScaffold(
        title = "朗读",
        subtitle = when {
            state.error != null -> state.error
            state.active -> "第 ${state.index + 1}/${state.total} 句"
            else -> "使用系统语音朗读当前章节"
        },
        onDismiss = onClose
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedButton(onClick = { tts.previous() }, modifier = Modifier.weight(1f)) { Text("上一句") }
                OutlinedButton(
                    onClick = { if (state.active && state.playing) tts.pause() else onStart(state.active) },
                    modifier = Modifier.weight(1f)
                ) { Text(if (state.playing) "暂停" else "播放") }
                OutlinedButton(onClick = { tts.next() }, modifier = Modifier.weight(1f)) { Text("下一句") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedButton(onClick = { onStart(false) }, modifier = Modifier.weight(1f)) { Text("从本章开头") }
                OutlinedButton(
                    onClick = { tts.stop(); onClose() },
                    modifier = Modifier.weight(1f)
                ) { Text("停止朗读") }
            }
            if (state.active) {
                MiradLinearProgress(
                    progress = { if (state.total <= 0) 0f else (state.index + 1).toFloat() / state.total },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            ReaderSettingsContent(SettingsCategory.TTS, config, onChange)
            Spacer(modifier = Modifier.height(Spacing.sm))
        }
    }
}
