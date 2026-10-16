package com.mirad.reader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.mirad.reader.AppContainer
import com.mirad.reader.core.model.BackgroundType
import com.mirad.reader.core.model.EpubLayoutMode
import com.mirad.reader.core.model.FontFamilyKey
import com.mirad.reader.core.model.OrientationKey
import com.mirad.reader.core.model.PageAnim
import com.mirad.reader.core.model.PageMode
import com.mirad.reader.core.model.ReaderConfig
import com.mirad.reader.core.model.TapZoneKey
import com.mirad.reader.core.model.TextAlignKey
import com.mirad.reader.core.model.ThemeKey
import com.mirad.reader.data.db.entity.BookEntity
import com.mirad.reader.data.db.entity.BookmarkEntity
import com.mirad.reader.data.db.entity.ChapterEntity
import com.mirad.reader.data.db.entity.HighlightEntity
import com.mirad.reader.data.repo.SearchHit
import com.mirad.reader.data.repo.SearchOptions
import com.mirad.reader.data.repo.SearchProgress
import com.mirad.reader.reader.ReaderSession
import com.mirad.reader.tts.TtsBus
import com.mirad.reader.tts.TtsController
import com.mirad.reader.ui.common.ChoiceRow
import com.mirad.reader.ui.common.ColorDotRow
import com.mirad.reader.ui.common.LabeledSlider
import com.mirad.reader.ui.common.SectionTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TocSheet(
    chapters: List<ChapterEntity>,
    currentIndex: Int,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit
) {
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = currentIndex.coerceAtLeast(0)
    )
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text("目录（共 ${chapters.size} 章）", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
        ) {
            items(chapters, key = { it.index }) { chapter ->
                val isCurrent = chapter.index == currentIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (isCurrent) MaterialTheme.colorScheme.primaryContainer
                            else Color.Transparent
                        )
                        .clickable { onSelect(chapter.index) }
                        .padding(
                            start = 16.dp + if (chapter.isVolume) 0.dp else 16.dp,
                            end = 16.dp,
                            top = 12.dp,
                            bottom = 12.dp
                        )
                ) {
                    Text(
                        text = chapter.title,
                        style = if (chapter.isVolume) MaterialTheme.typography.titleSmall
                        else MaterialTheme.typography.bodyMedium,
                        fontWeight = if (chapter.isVolume || isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (isCurrent) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressSheet(
    percent: Float,
    chapterIndex: Int,
    chapterCount: Int,
    chapterTitle: String,
    onJump: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    var draft by remember { mutableStateOf(percent) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text("阅读进度", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = chapterTitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text("${(draft * 100).toInt()}%  ·  第 ${chapterIndex + 1}/$chapterCount 章")
            Slider(
                value = draft,
                onValueChange = { draft = it },
                onValueChangeFinished = { onJump(draft) }
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { draft = 0f; onJump(0f) }) { Text("开头") }
                OutlinedButton(onClick = { draft = 0.5f; onJump(0.5f) }) { Text("中间") }
                OutlinedButton(onClick = { draft = 1f; onJump(1f) }) { Text("结尾") }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
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
                    onChange { it.copy(backgroundType = BackgroundType.IMAGE, backgroundImageUri = target.absolutePath) }
                }
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Text("阅读设置", style = MaterialTheme.typography.titleMedium)

            SectionTitle("阅读方式")
            ChoiceRow(
                label = "排版方式",
                options = listOf(
                    PageMode.PAGED to "左右翻页",
                    PageMode.SCROLL to "上下滚动"
                ),
                selected = config.pageMode,
                onSelect = { mode -> onChange { it.copy(pageMode = mode) } },
                columns = 2
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

            SectionTitle("字体与排版")
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
            ChoiceRow(
                label = "字体",
                options = listOf(
                    FontFamilyKey.SYSTEM to "系统",
                    FontFamilyKey.SERIF to "宋体",
                    FontFamilyKey.SANS to "黑体",
                    FontFamilyKey.MONO to "等宽"
                ),
                selected = config.fontFamily,
                onSelect = { key -> onChange { it.copy(fontFamily = key) } },
                columns = 4
            )
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("加粗正文", modifier = Modifier.weight(1f))
                Switch(
                    checked = config.boldText,
                    onCheckedChange = { value -> onChange { it.copy(boldText = value) } }
                )
            }

            SectionTitle("背景与主题")
            ChoiceRow(
                label = "主题",
                options = listOf(
                    ThemeKey.LIGHT to "白",
                    ThemeKey.PAPER to "纸色",
                    ThemeKey.SEPIA to "米黄",
                    ThemeKey.GREEN to "护眼",
                    ThemeKey.DARK to "夜间",
                    ThemeKey.BLACK to "纯黑",
                    ThemeKey.SYSTEM to "跟随系统"
                ),
                selected = config.theme,
                onSelect = { key -> onChange { it.copy(theme = key) } },
                columns = 4
            )
            ChoiceRow(
                label = "背景类型",
                options = listOf(
                    BackgroundType.COLOR to "纯色",
                    BackgroundType.IMAGE to "图片"
                ),
                selected = config.backgroundType,
                onSelect = { key -> onChange { it.copy(backgroundType = key) } },
                columns = 2
            )
            if (config.backgroundType == BackgroundType.IMAGE) {
                OutlinedButton(
                    onClick = { imagePicker.launch(arrayOf("image/*")) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("选择背景图片") }
                Spacer(modifier = Modifier.height(8.dp))
                LabeledSlider(
                    label = "背景不透明度",
                    value = config.backgroundImageAlpha,
                    valueText = String.format("%.2f", config.backgroundImageAlpha),
                    range = 0.1f..1f,
                    onChange = { value -> onChange { it.copy(backgroundImageAlpha = value) } }
                )
            } else {
                ColorDotRow(
                    colors = listOf(
                        0xFFFFFFFF.toInt(), 0xFFF5F1E6.toInt(), 0xFFEDE0C8.toInt(),
                        0xFFCCE8CF.toInt(), 0xFFB8D8E8.toInt(), 0xFF1A1C1E.toInt(),
                        0xFF000000.toInt()
                    ),
                    selected = config.backgroundColor,
                    onSelect = { color ->
                        onChange { it.copy(backgroundColor = color, theme = ThemeKey.CUSTOM) }
                    }
                )
            }

            SectionTitle("屏幕")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("亮度（-1 跟随系统）", modifier = Modifier.weight(1f))
                Switch(
                    checked = config.brightness < 0f,
                    onCheckedChange = { follow -> onChange { it.copy(brightness = if (follow) -1f else 0.6f) } }
                )
            }
            if (config.brightness >= 0f) {
                LabeledSlider(
                    label = "屏幕亮度",
                    value = config.brightness,
                    valueText = "${(config.brightness * 100).toInt()}%",
                    range = 0.05f..1f,
                    onChange = { value -> onChange { it.copy(brightness = value) } }
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("屏幕常亮", modifier = Modifier.weight(1f))
                Switch(
                    checked = config.keepScreenOn,
                    onCheckedChange = { value -> onChange { it.copy(keepScreenOn = value) } }
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("全屏沉浸", modifier = Modifier.weight(1f))
                Switch(
                    checked = config.fullScreen,
                    onCheckedChange = { value -> onChange { it.copy(fullScreen = value) } }
                )
            }
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

            SectionTitle("页眉页脚")
            SwitchRow("显示页眉", config.showHeader) { value -> onChange { it.copy(showHeader = value) } }
            SwitchRow("页眉显示章节名", config.showChapterTitleInHeader) { value ->
                onChange { it.copy(showChapterTitleInHeader = value) }
            }
            SwitchRow("显示页脚", config.showFooter) { value -> onChange { it.copy(showFooter = value) } }
            SwitchRow("显示页码", config.showPageNumber) { value -> onChange { it.copy(showPageNumber = value) } }
            SwitchRow("显示时间", config.showClock) { value -> onChange { it.copy(showClock = value) } }
            SwitchRow("显示电量", config.showBattery) { value -> onChange { it.copy(showBattery = value) } }

            SectionTitle("EPUB 排版")
            ChoiceRow(
                label = "原书样式",
                options = listOf(
                    EpubLayoutMode.UNIFIED to "统一排版",
                    EpubLayoutMode.ORIGINAL to "保留原书"
                ),
                selected = config.epubLayoutMode,
                onSelect = { key -> onChange { it.copy(epubLayoutMode = key) } },
                columns = 2
            )
            SwitchRow("显示插图", config.allowImages) { value -> onChange { it.copy(allowImages = value) } }

            SectionTitle("朗读")
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
            SwitchRow("读完自动下一章", config.ttsAutoNextChapter) { value ->
                onChange { it.copy(ttsAutoNextChapter = value) }
            }

            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(onClick = onResetGlobal, modifier = Modifier.fillMaxWidth()) {
                Text("恢复全局默认设置")
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesSheet(
    container: AppContainer,
    session: ReaderSession,
    bookId: Long,
    onDismiss: () -> Unit,
    onJump: (chapterIndex: Int, offset: Int) -> Unit
) {
    val scope = rememberCoroutineScope()
    val highlights by container.notes.observeHighlights(bookId).collectAsState(initial = emptyList())
    val bookmarks by container.notes.observeBookmarks(bookId).collectAsState(initial = emptyList())
    var tab by remember { mutableStateOf(0) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { tab = 0 }) { Text("划线笔记 ${highlights.size}") }
                TextButton(onClick = { tab = 1 }) { Text("书签 ${bookmarks.size}") }
            }
            Spacer(modifier = Modifier.height(4.dp))
        }
        val listHeight = 420.dp
        if (tab == 0) {
            LazyColumn(modifier = Modifier
                .fillMaxWidth()
                .height(listHeight)) {
                if (highlights.isEmpty()) {
                    item { EmptyHint("还没有划线或笔记：长按选中文字后点「划线」或「笔记」") }
                }
                items(highlights, key = { it.id }) { highlight ->
                    NoteRow(
                        title = highlight.text,
                        subtitle = highlight.note,
                        meta = "第 ${highlight.chapterIndex + 1} 章",
                        onClick = { onJump(highlight.chapterIndex, highlight.startOffset) },
                        onDelete = { scope.launch { session.deleteHighlight(highlight) } }
                    )
                }
            }
        } else {
            LazyColumn(modifier = Modifier
                .fillMaxWidth()
                .height(listHeight)) {
                if (bookmarks.isEmpty()) {
                    item { EmptyHint("还没有书签：点顶部的星标即可添加") }
                }
                items(bookmarks, key = { it.id }) { bookmark ->
                    NoteRow(
                        title = bookmark.chapterTitle,
                        subtitle = bookmark.snippet,
                        meta = bookmark.note,
                        onClick = { onJump(bookmark.chapterIndex, bookmark.charOffset) },
                        onDelete = { scope.launch { container.notes.deleteBookmark(bookmark) } }
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp)
    )
}

@Composable
private fun NoteRow(
    title: String,
    subtitle: String?,
    meta: String?,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp)
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = meta.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onDelete) { Text("删除") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
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
    var scanned by remember { mutableStateOf(0) }

    fun runSearch() {
        val keyword = query.trim()
        if (keyword.isEmpty()) return
        results = emptyList()
        scanning = true
        scanned = 0
        scope.launch {
            container.search.search(book, session.chapters, keyword, SearchOptions())
                .collect { progress ->
                    when (progress) {
                        is SearchProgress.Scanning -> scanned = progress.chapterIndex
                        is SearchProgress.Done -> {
                            results = progress.results
                            scanning = false
                        }
                    }
                }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("全书搜索") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { runSearch() }) { Text("搜索") }
            }
            if (scanning) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = {
                        if (session.chapters.isEmpty()) 0f
                        else (scanned + 1).toFloat() / session.chapters.size
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Text("正在扫描第 ${scanned + 1} 章…", style = MaterialTheme.typography.labelSmall)
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
        LazyColumn(modifier = Modifier
            .fillMaxWidth()
            .height(420.dp)) {
            if (!scanning && results.isEmpty() && query.isNotBlank()) {
                item { EmptyHint("没有找到「$query」") }
            }
            items(results) { hit ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onJump(hit) }
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = hit.chapterTitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    val snippet = hit.snippet
                    if (hit.matchStart in 0..snippet.length && hit.matchEnd <= snippet.length) {
                        androidx.compose.ui.text.buildAnnotatedString {
                            append(snippet.substring(0, hit.matchStart))
                            withStyle(androidx.compose.ui.text.SpanStyle(
                                background = Color(0x66FF6B6B),
                                fontWeight = FontWeight.Bold
                            )) {
                                append(snippet.substring(hit.matchStart, hit.matchEnd))
                            }
                            append(snippet.substring(hit.matchEnd))
                        }.let { annotated ->
                            Text(text = annotated, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        }
                    } else {
                        Text(text = snippet, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    }
                }
            }
        }
    }
}

@Composable
fun TtsPanel(
    tts: TtsController,
    onStart: (resume: Boolean) -> Unit,
    onClose: () -> Unit
) {
    val state by TtsBus.state.collectAsState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(
            text = when {
                state.error != null -> state.error!!
                state.active -> "正在朗读 第 ${state.index + 1}/${state.total} 句"
                else -> "朗读当前章节"
            },
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { tts.previous() }) { Text("上一句") }
            TextButton(onClick = { if (state.active) tts.pause() else onStart(true) }) {
                Text(if (state.playing) "暂停" else "播放")
            }
            TextButton(onClick = { tts.next() }) { Text("下一句") }
            TextButton(onClick = { onStart(false) }) { Text("从头") }
            TextButton(onClick = { tts.stop(); onClose() }) { Text("停止") }
        }
        if (state.active) {
            LinearProgressIndicator(
                progress = {
                    if (state.total <= 0) 0f else (state.index + 1).toFloat() / state.total
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

private fun formatPercent(value: Float): String = "${(value * 100).toInt()}%"
