package com.mirad.reader.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mirad.reader.AppContainer
import com.mirad.reader.BuildConfig
import com.mirad.reader.core.model.BackgroundType
import com.mirad.reader.core.model.EpubLayoutMode
import com.mirad.reader.core.model.FontFamilyKey
import com.mirad.reader.core.model.OrientationKey
import com.mirad.reader.core.model.PageAnim
import com.mirad.reader.core.model.PageMode
import com.mirad.reader.core.model.ReaderConfig
import com.mirad.reader.core.model.TapZoneKey
import com.mirad.reader.core.model.TextAlignKey
import com.mirad.reader.data.db.entity.GroupEntity
import com.mirad.reader.ui.common.BackgroundSection
import com.mirad.reader.ui.common.CardDivider
import com.mirad.reader.ui.common.ChoiceRow
import com.mirad.reader.ui.common.FontSection
import com.mirad.reader.ui.common.InlineBanner
import com.mirad.reader.ui.common.LabeledSlider
import com.mirad.reader.ui.common.ListRow
import com.mirad.reader.ui.common.RowDivider
import com.mirad.reader.ui.common.ScreenHeader
import com.mirad.reader.ui.common.SegmentedRow
import com.mirad.reader.ui.common.SettingCard
import com.mirad.reader.ui.common.SettingNavRow
import com.mirad.reader.ui.common.SettingSwitchRow
import com.mirad.reader.ui.design.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 全局默认阅读设置。阅读器内改的设置只对那本书生效，这里改的是"新书默认值"。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenHidden: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf(ReaderConfig.DEFAULT) }
    var fonts by remember { mutableStateOf(container.fonts.available()) }
    val groups by container.groups.observeGroups().collectAsState(initial = emptyList())
    val shelf by container.books.observeShelf().collectAsState(initial = emptyList())
    var renameTarget by remember { mutableStateOf<GroupEntity?>(null) }
    var renameDraft by remember { mutableStateOf("") }

    val hiddenCount = shelf.count { it.book.isHidden }

    LaunchedEffect(Unit) {
        config = container.settings.currentGlobal()
        fonts = withContext(Dispatchers.IO) { container.fonts.available() }
    }

    fun update(transform: (ReaderConfig) -> ReaderConfig) {
        val updated = transform(config)
        config = updated
        scope.launch { container.settings.saveGlobal(updated) }
    }

    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val imported = withContext(Dispatchers.IO) { container.fonts.import(uri) }
                if (imported != null) {
                    fonts = withContext(Dispatchers.IO) { container.fonts.available() }
                    update {
                        it.copy(
                            fontFamily = FontFamilyKey.CUSTOM,
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
                val target = File(container.paths.backgroundsDir, "bg_default.jpg")
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            target.outputStream().use { output -> input.copyTo(output) }
                        }
                        true
                    }.getOrDefault(false)
                }
                if (ok) update { it.copy(backgroundType = BackgroundType.IMAGE, backgroundImageUri = target.absolutePath) }
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { ScreenHeader(title = "设置", onBack = onBack) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.xl)
        ) {
            Spacer(modifier = Modifier.height(Spacing.sm))
            InlineBanner("这里是新书的默认设置。在阅读器里改动只对当前这本书生效，可在阅读设置里「恢复全局默认」。")
            Spacer(modifier = Modifier.height(Spacing.lg))

            SettingCard(title = "阅读方式") {
                SegmentedRow(
                    options = listOf(PageMode.PAGED to "左右翻页", PageMode.SCROLL to "上下滚动"),
                    selected = config.pageMode,
                    onSelect = { mode -> update { it.copy(pageMode = mode) } }
                )
                ChoiceRow(
                    label = "翻页动画",
                    options = listOf(
                        PageAnim.NONE to "无动画",
                        PageAnim.SLIDE to "滑动",
                        PageAnim.COVER to "覆盖"
                    ),
                    selected = config.pageAnim,
                    onSelect = { anim -> update { it.copy(pageAnim = anim) } },
                    columns = 3
                )
            }

            FontSection(
                config = config,
                customFonts = fonts,
                onChange = { transform -> update(transform) },
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
                    range = 12f..60f,
                    steps = 47,
                    onChange = { value -> update { it.copy(fontSizeSp = value) } }
                )
                LabeledSlider(
                    label = "行距",
                    value = config.lineHeightMultiplier,
                    valueText = String.format("%.2f 倍", config.lineHeightMultiplier),
                    range = 1.1f..2.8f,
                    steps = 16,
                    onChange = { value -> update { it.copy(lineHeightMultiplier = value) } }
                )
                LabeledSlider(
                    label = "段间距",
                    value = config.paragraphSpacingEm,
                    valueText = String.format("%.2f em", config.paragraphSpacingEm),
                    range = 0f..1.6f,
                    steps = 15,
                    onChange = { value -> update { it.copy(paragraphSpacingEm = value) } }
                )
                LabeledSlider(
                    label = "首行缩进",
                    value = config.firstLineIndentEm,
                    valueText = String.format("%.1f 字", config.firstLineIndentEm),
                    range = 0f..3f,
                    steps = 5,
                    onChange = { value -> update { it.copy(firstLineIndentEm = value) } }
                )
                LabeledSlider(
                    label = "页边距",
                    value = config.horizontalPaddingDp.toFloat(),
                    valueText = "${config.horizontalPaddingDp} dp",
                    range = 6f..40f,
                    steps = 16,
                    onChange = { value -> update { it.copy(horizontalPaddingDp = value.toInt()) } }
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
                    onSelect = { key -> update { it.copy(textAlign = key) } },
                    columns = 3
                )
                SettingSwitchRow(
                    label = "加粗正文",
                    checked = config.boldText,
                    onCheckedChange = { value -> update { it.copy(boldText = value) } }
                )
            }

            BackgroundSection(
                config = config,
                onChange = { transform -> update(transform) },
                onPickImage = { imagePicker.launch(arrayOf("image/*")) }
            )

            SettingCard(title = "自动夜间") {
                SettingSwitchRow(
                    label = "定时进入夜间主题",
                    checked = config.autoNightEnabled,
                    onCheckedChange = { value -> update { it.copy(autoNightEnabled = value) } }
                )
                if (config.autoNightEnabled) {
                    LabeledSlider(
                        label = "开始时间",
                        value = (config.autoNightStartMinute / 60).toFloat(),
                        valueText = "%d:00".format(config.autoNightStartMinute / 60),
                        range = 0f..23f,
                        steps = 22,
                        onChange = { value -> update { it.copy(autoNightStartMinute = value.toInt() * 60) } }
                    )
                    LabeledSlider(
                        label = "结束时间",
                        value = (config.autoNightEndMinute / 60).toFloat(),
                        valueText = "%d:00".format(config.autoNightEndMinute / 60),
                        range = 0f..23f,
                        steps = 22,
                        onChange = { value -> update { it.copy(autoNightEndMinute = value.toInt() * 60) } }
                    )
                }
            }

            SettingCard(title = "屏幕与交互") {
                SettingSwitchRow(
                    label = "屏幕常亮",
                    checked = config.keepScreenOn,
                    onCheckedChange = { value -> update { it.copy(keepScreenOn = value) } }
                )
                SettingSwitchRow(
                    label = "全屏沉浸",
                    checked = config.fullScreen,
                    onCheckedChange = { value -> update { it.copy(fullScreen = value) } }
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
                    onSelect = { key -> update { it.copy(orientation = key) } },
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
                    onSelect = { key -> update { it.copy(tapZone = key) } },
                    columns = 3
                )
            }

            SettingCard(title = "页眉页脚") {
                SettingSwitchRow(
                    label = "显示页眉",
                    checked = config.showHeader,
                    onCheckedChange = { value -> update { it.copy(showHeader = value) } }
                )
                SettingSwitchRow(
                    label = "显示页脚",
                    checked = config.showFooter,
                    onCheckedChange = { value -> update { it.copy(showFooter = value) } }
                )
                SettingSwitchRow(
                    label = "显示页码",
                    checked = config.showPageNumber,
                    onCheckedChange = { value -> update { it.copy(showPageNumber = value) } }
                )
                SettingSwitchRow(
                    label = "显示时间与电量",
                    subtitle = "页眉右侧显示当前时间与电量",
                    checked = config.showClock || config.showBattery,
                    onCheckedChange = { value ->
                        update { it.copy(showClock = value, showBattery = value) }
                    }
                )
            }

            SettingCard(title = "EPUB 与朗读") {
                SegmentedRow(
                    options = listOf(EpubLayoutMode.UNIFIED to "统一排版", EpubLayoutMode.ORIGINAL to "保留原书"),
                    selected = config.epubLayoutMode,
                    onSelect = { key -> update { it.copy(epubLayoutMode = key) } }
                )
                SettingSwitchRow(
                    label = "显示插图",
                    checked = config.allowImages,
                    onCheckedChange = { value -> update { it.copy(allowImages = value) } }
                )
                CardDivider()
                LabeledSlider(
                    label = "朗读语速",
                    value = config.ttsSpeechRate,
                    valueText = String.format("%.2f", config.ttsSpeechRate),
                    range = 0.5f..2f,
                    onChange = { value -> update { it.copy(ttsSpeechRate = value) } }
                )
                SettingSwitchRow(
                    label = "读完自动下一章",
                    checked = config.ttsAutoNextChapter,
                    onCheckedChange = { value -> update { it.copy(ttsAutoNextChapter = value) } }
                )
            }

            SettingCard(title = "书架管理") {
                SettingNavRow(
                    label = "隐藏的书籍",
                    value = if (hiddenCount == 0) "无" else "$hiddenCount 本",
                    subtitle = "隐藏后仍会保留阅读记录，可随时恢复",
                    onClick = onOpenHidden
                )
                CardDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "分组",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = if (groups.isEmpty()) "还没有分组" else "${groups.size} 个",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (groups.isEmpty()) {
                    Text(
                        text = "在书架上长按选中的书即可「移入分组」，也可以新建分组。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    groups.forEach { group ->
                        val count = shelf.count { it.book.groupId == group.id }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "$group.name（$count 本）",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = {
                                renameTarget = group
                                renameDraft = group.name
                            }) { Text("重命名") }
                            TextButton(onClick = {
                                scope.launch { container.groups.delete(group) }
                            }) { Text("删除") }
                        }
                    }
                }
            }

            SettingCard(title = "关于") {
                Text(
                    text = "墨读 v${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "本地离线小说阅读器。支持 txt（自动识别 GBK/UTF-8 等编码并分章）与 epub（保留原书排版与图片）；" +
                        "内置 7 款阅读背景与 2 款常用字子集字体；所有书籍与阅读数据只保存在本机。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(Spacing.xxxl))
        }
    }

    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名分组") },
            text = {
                OutlinedTextField(
                    value = renameDraft,
                    onValueChange = { renameDraft = it },
                    label = { Text("分组名称") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = renameDraft.trim()
                    renameTarget = null
                    if (name.isNotEmpty() && name != target.name) {
                        scope.launch { container.groups.rename(target, name) }
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("取消") } }
        )
    }
}
