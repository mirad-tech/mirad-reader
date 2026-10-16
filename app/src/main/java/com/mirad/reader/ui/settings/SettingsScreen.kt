package com.mirad.reader.ui.settings

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import com.mirad.reader.ui.common.BackgroundSection
import com.mirad.reader.ui.common.ChoiceRow
import com.mirad.reader.ui.common.ColorDotRow
import com.mirad.reader.ui.common.FontSection
import com.mirad.reader.ui.common.LabeledSlider
import com.mirad.reader.ui.common.SectionTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 全局默认阅读设置。阅读器内的每个设置都会写成本书覆盖，这里修改的是"新书默认值"。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf(ReaderConfig.DEFAULT) }
    var fonts by remember { mutableStateOf(container.fonts.available()) }

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
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Text(
                text = "这里的设置是新书的默认值；在阅读器里改动的设置只对当前这本书生效，可在阅读设置里「恢复全局默认」。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )

            SectionTitle("默认阅读方式")
            ChoiceRow(
                label = "排版方式",
                options = listOf(PageMode.PAGED to "左右翻页", PageMode.SCROLL to "上下滚动"),
                selected = config.pageMode,
                onSelect = { mode -> update { it.copy(pageMode = mode) } },
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
                onSelect = { anim -> update { it.copy(pageAnim = anim) } },
                columns = 3
            )

            SectionTitle("默认字体与排版")
            LabeledSlider(
                label = "字号",
                value = config.fontSizeSp,
                valueText = "${config.fontSizeSp.toInt()} px",
                range = 12f..34f,
                steps = 21,
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

            BackgroundSection(
                config = config,
                onChange = { transform -> update(transform) },
                onPickImage = { imagePicker.launch(arrayOf("image/*")) }
            )

            SectionTitle("自动夜间")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("定时进入夜间主题", modifier = Modifier.weight(1f))
                Switch(
                    checked = config.autoNightEnabled,
                    onCheckedChange = { value -> update { it.copy(autoNightEnabled = value) } }
                )
            }
            if (config.autoNightEnabled) {
                LabeledSlider(
                    label = "开始时间（小时）",
                    value = (config.autoNightStartMinute / 60).toFloat(),
                    valueText = "${config.autoNightStartMinute / 60}:00",
                    range = 0f..23f,
                    steps = 22,
                    onChange = { value -> update { it.copy(autoNightStartMinute = (value.toInt() * 60)) } }
                )
                LabeledSlider(
                    label = "结束时间（小时）",
                    value = (config.autoNightEndMinute / 60).toFloat(),
                    valueText = "${config.autoNightEndMinute / 60}:00",
                    range = 0f..23f,
                    steps = 22,
                    onChange = { value -> update { it.copy(autoNightEndMinute = (value.toInt() * 60)) } }
                )
            }

            SectionTitle("屏幕与交互")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("屏幕常亮", modifier = Modifier.weight(1f))
                Switch(
                    checked = config.keepScreenOn,
                    onCheckedChange = { value -> update { it.copy(keepScreenOn = value) } }
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("全屏沉浸", modifier = Modifier.weight(1f))
                Switch(
                    checked = config.fullScreen,
                    onCheckedChange = { value -> update { it.copy(fullScreen = value) } }
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

            SectionTitle("EPUB 与朗读")
            ChoiceRow(
                label = "原书样式",
                options = listOf(
                    EpubLayoutMode.UNIFIED to "统一排版",
                    EpubLayoutMode.ORIGINAL to "保留原书"
                ),
                selected = config.epubLayoutMode,
                onSelect = { key -> update { it.copy(epubLayoutMode = key) } },
                columns = 2
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("显示插图", modifier = Modifier.weight(1f))
                Switch(
                    checked = config.allowImages,
                    onCheckedChange = { value -> update { it.copy(allowImages = value) } }
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("朗读读完自动下一章", modifier = Modifier.weight(1f))
                Switch(
                    checked = config.ttsAutoNextChapter,
                    onCheckedChange = { value -> update { it.copy(ttsAutoNextChapter = value) } }
                )
            }

            SectionTitle("关于")
            Text(
                text = "墨读 · 本地离线小说阅读器\n支持 txt（自动识别 GBK/UTF-8 等编码并分章）与 epub（保留原书排版与图片）。\n所有书籍与阅读数据仅保存在本机。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}
