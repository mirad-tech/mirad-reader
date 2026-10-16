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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
        subtitle = "共 ${chapters.size} 章 · 当前第 ${currentIndex + 1} 章",
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
                Slider(
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
                        context.contentResolver.openInputStream(uri)?.use { input ->
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

    // 只占屏幕下半部分：调字号/行距时上半屏还能看到正文，改了立刻能看出效果。
    SheetScaffold(
        title = "阅读设置",
        subtitle = "只对当前这本书生效",
        onDismiss = onDismiss,
        fillHeight = true,
        maxHeightFraction = 0.62f
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.xl)
        ) {
            FontSection(
                config = config,
                customFonts = fonts,
                onChange = onChange,
                onImport = { fontPicker.launch(arrayOf("*/*")) },
                onDelete = { font ->
                    scope.launch {
                        withContext(Dispatchers.IO) { container.fonts.delete(font) }
                        fonts = withContext(Dispatchers.IO) { container.fonts.available() }
                    }
                }
            )

            SettingCard(title = "排版") {
                LabeledSlider(
                    label = "字号",
                    value = config.fontSizeSp,
                    valueText = "${config.fontSizeSp.toInt()} px",
                    range = 12f..34f,
                    steps = 21,
                    onChange = { value -> onChange { it.copy(fontSizeSp = value) } }
                )
                LabeledSlider(
                    label = "行距",
                    value = config.lineHeightMultiplier,
                    valueText = String.format("%.2f 倍", config.lineHeightMultiplier),
                    range = 1.1f..2.8f,
                    steps = 16,
                    onChange = { value -> onChange { it.copy(lineHeightMultiplier = value) } }
                )
                LabeledSlider(
                    label = "段间距",
                    value = config.paragraphSpacingEm,
                    valueText = String.format("%.2f em", config.paragraphSpacingEm),
                    range = 0f..1.6f,
                    steps = 15,
                    onChange = { value -> onChange { it.copy(paragraphSpacingEm = value) } }
                )
                LabeledSlider(
                    label = "首行缩进",
                    value = config.firstLineIndentEm,
                    valueText = String.format("%.1f 字", config.firstLineIndentEm),
                    range = 0f..3f,
                    steps = 5,
                    onChange = { value -> onChange { it.copy(firstLineIndentEm = value) } }
                )
                LabeledSlider(
                    label = "页边距",
                    value = config.horizontalPaddingDp.toFloat(),
                    valueText = "${config.horizontalPaddingDp} dp",
                    range = 6f..40f,
                    steps = 16,
                    onChange = { value -> onChange { it.copy(horizontalPaddingDp = value.toInt()) } }
                )
                CardDivider()
                ChoiceRow(
                    label = "对齐",
                    options = listOf(
                        TextAlignKey.JUSTIFY to "两端对齐",
                        TextAlignKey.LEFT to "左对齐",
                        TextAlignKey.CENTER to "居中"
                    ),
                    selected = config.textAlign,
                    onSelect = { key -> onChange { it.copy(textAlign = key) } },
                    columns = 3
                )
                SettingSwitchRow(
                    label = "加粗正文",
                    checked = config.boldText,
                    onCheckedChange = { value -> onChange { it.copy(boldText = value) } }
                )
            }

            // 亮度与阅读方式放在排版之后：先看得见字，再管看的方式
            SettingCard(title = "亮度") {
                SettingSwitchRow(
                    label = "跟随系统亮度",
                    subtitle = "关闭后可单独调节阅读时的屏幕亮度",
                    checked = config.brightness < 0f,
                    onCheckedChange = { follow ->
                        onChange { it.copy(brightness = if (follow) -1f else 0.65f) }
                    }
                )
                if (config.brightness >= 0f) {
                    LabeledSlider(
                        label = "屏幕亮度",
                        value = config.brightness,
                        valueText = "${(config.brightness * 100).toInt()}%",
                        range = 0.05f..1f,
                        onChange = { value -> onChange { it.copy(brightness = value) } }
                    )
                }
            }

            SettingCard(title = "阅读方式") {
                SegmentedRow(
                    options = listOf(PageMode.PAGED to "左右翻页", PageMode.SCROLL to "上下滚动"),
                    selected = config.pageMode,
                    onSelect = { mode -> onChange { it.copy(pageMode = mode) } }
                )
                ChoiceRow(
                    label = "翻页动画",
                    options = listOf(
                        PageAnim.NONE to "无动画",
                        PageAnim.SLIDE to "滑动",
                        PageAnim.COVER to "覆盖"
                    ),
                    selected = config.pageAnim,
                    onSelect = { anim -> onChange { it.copy(pageAnim = anim) } },
                    columns = 3
                )
            }

            BackgroundSection(
                config = config,
                onChange = onChange,
                onPickImage = { imagePicker.launch(arrayOf("image/*")) }
            )

            SettingCard(title = "屏幕与交互") {
                SettingSwitchRow(
                    label = "屏幕常亮",
                    checked = config.keepScreenOn,
                    onCheckedChange = { value -> onChange { it.copy(keepScreenOn = value) } }
                )
                SettingSwitchRow(
                    label = "全屏沉浸",
                    subtitle = "隐藏状态栏与导航栏",
                    checked = config.fullScreen,
                    onCheckedChange = { value -> onChange { it.copy(fullScreen = value) } }
                )
                CardDivider()
                ChoiceRow(
                    label = "屏幕方向",
                    options = listOf(
                        OrientationKey.AUTO to "自动",
                        OrientationKey.PORTRAIT to "竖屏",
                        OrientationKey.LANDSCAPE to "横屏"
                    ),
                    selected = config.orientation,
                    onSelect = { key -> onChange { it.copy(orientation = key) } },
                    columns = 3
                )
                ChoiceRow(
                    label = "点击区域",
                    options = listOf(
                        TapZoneKey.THREE to "左中右三分",
                        TapZoneKey.TWO to "左右两分",
                        TapZoneKey.NONE to "仅呼出菜单"
                    ),
                    selected = config.tapZone,
                    onSelect = { key -> onChange { it.copy(tapZone = key) } },
                    columns = 3
                )
            }

            SettingCard(title = "页眉页脚") {
                SettingSwitchRow(
                    label = "显示页眉",
                    checked = config.showHeader,
                    onCheckedChange = { value -> onChange { it.copy(showHeader = value) } }
                )
                SettingSwitchRow(
                    label = "页眉显示章节名",
                    checked = config.showChapterTitleInHeader,
                    onCheckedChange = { value -> onChange { it.copy(showChapterTitleInHeader = value) } }
                )
                SettingSwitchRow(
                    label = "显示页脚",
                    checked = config.showFooter,
                    onCheckedChange = { value -> onChange { it.copy(showFooter = value) } }
                )
                SettingSwitchRow(
                    label = "显示页码",
                    checked = config.showPageNumber,
                    onCheckedChange = { value -> onChange { it.copy(showPageNumber = value) } }
                )
                SettingSwitchRow(
                    label = "显示时间",
                    checked = config.showClock,
                    onCheckedChange = { value -> onChange { it.copy(showClock = value) } }
                )
                SettingSwitchRow(
                    label = "显示电量",
                    checked = config.showBattery,
                    onCheckedChange = { value -> onChange { it.copy(showBattery = value) } }
                )
            }

            SettingCard(title = "EPUB 排版") {
                SegmentedRow(
                    options = listOf(EpubLayoutMode.UNIFIED to "统一排版", EpubLayoutMode.ORIGINAL to "保留原书"),
                    selected = config.epubLayoutMode,
                    onSelect = { key -> onChange { it.copy(epubLayoutMode = key) } }
                )
                Text(
                    text = "统一排版会强制字号行距，但保留插图、加粗与层级；保留原书则尽量还原出版社版式。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SettingSwitchRow(
                    label = "显示插图",
                    checked = config.allowImages,
                    onCheckedChange = { value -> onChange { it.copy(allowImages = value) } }
                )
            }

            SettingCard(title = "朗读") {
                LabeledSlider(
                    label = "语速",
                    value = config.ttsSpeechRate,
                    valueText = String.format("%.2f", config.ttsSpeechRate),
                    range = 0.5f..2f,
                    onChange = { value -> onChange { it.copy(ttsSpeechRate = value) } }
                )
                LabeledSlider(
                    label = "音调",
                    value = config.ttsPitch,
                    valueText = String.format("%.2f", config.ttsPitch),
                    range = 0.5f..2f,
                    onChange = { value -> onChange { it.copy(ttsPitch = value) } }
                )
                SettingSwitchRow(
                    label = "读完自动下一章",
                    checked = config.ttsAutoNextChapter,
                    onCheckedChange = { value -> onChange { it.copy(ttsAutoNextChapter = value) } }
                )
            }

            OutlinedButton(onClick = onResetGlobal, modifier = Modifier.fillMaxWidth()) {
                Text("恢复全局默认设置")
            }
            Spacer(modifier = Modifier.height(Spacing.xxxl))
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

    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri ->
        val content = pendingExport
        pendingExport = null
        if (uri != null && content != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
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
                        ListRow(
                            title = highlight.text,
                            subtitle = highlight.note,
                            meta = "第 ${highlight.chapterIndex + 1} 章",
                            trailing = {
                                SheetActionButton("删除", onClick = { scope.launch { session.deleteHighlight(highlight) } })
                            },
                            onClick = { onJump(highlight.chapterIndex, highlight.startOffset) }
                        )
                    }
                }
            }
            else -> if (bookmarks.isEmpty()) {
                SheetEmpty("还没有书签。\n点顶栏的星标即可把当前位置加入书签。")
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    items(bookmarks, key = { it.id }) { bookmark ->
                        ListRow(
                            title = bookmark.chapterTitle,
                            subtitle = bookmark.snippet,
                            trailing = {
                                SheetActionButton(
                                    "删除",
                                    onClick = { scope.launch { container.notes.deleteBookmark(bookmark) } }
                                )
                            },
                            onClick = { onJump(bookmark.chapterIndex, bookmark.charOffset) }
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
            LinearProgressIndicator(
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

@Composable
fun TtsSheet(
    tts: TtsController,
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
                LinearProgressIndicator(
                    progress = { if (state.total <= 0) 0f else (state.index + 1).toFloat() / state.total },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Text(
                text = "朗读会在后台继续，通知栏可以暂停或切句；语速与音调在阅读设置里调整。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Normal
            )
            Spacer(modifier = Modifier.height(Spacing.sm))
        }
    }
}
