package com.mirad.reader.ui.common

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mirad.reader.core.model.*
import com.mirad.reader.ui.reader.openTtsSettings

enum class SettingsCategory(val title: String) {
    TYPOGRAPHY("字体与排版"), THEME("主题与背景"), INTERACTION("翻页与交互"),
    SCREEN("屏幕与页眉页脚"), EPUB("EPUB"), TTS("朗读"), SHELF("书架管理"), ABOUT("关于")
}

@Composable
fun FontSizeControl(config: ReaderConfig, onChange: ((ReaderConfig) -> ReaderConfig) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onChange { it.copy(fontSizeSp = (it.fontSizeSp - 1f).coerceAtLeast(12f)) } }, enabled = config.fontSizeSp > 12f) { Text("减小") }
        Column(Modifier.weight(1f)) {
            LabeledSlider("字号", config.fontSizeSp, "${config.fontSizeSp.toInt()} px", 12f..60f,
                onChange = { value -> onChange { it.copy(fontSizeSp = value) } }, steps = 47)
        }
        TextButton(onClick = { onChange { it.copy(fontSizeSp = (it.fontSizeSp + 1f).coerceAtMost(60f)) } }, enabled = config.fontSizeSp < 60f) { Text("增大") }
    }
}

@Composable
fun BrightnessSettings(config: ReaderConfig, onChange: ((ReaderConfig) -> ReaderConfig) -> Unit) {
            SettingCard(title = "亮度") {
                SettingSwitchRow(
                    label = "跟随系统亮度",
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
}

@Composable
fun ReaderSettingsContent(
    category: SettingsCategory,
    config: ReaderConfig,
    onChange: ((ReaderConfig) -> ReaderConfig) -> Unit,
    fonts: List<ReadingFont> = emptyList(),
    onImportFont: () -> Unit = {},
    onDeleteFont: (ReadingFont) -> Unit = {},
    onPickImage: () -> Unit = {}
) {
    val context = LocalContext.current
    when (category) {
        SettingsCategory.TYPOGRAPHY -> {
FontSection(config, fonts, onChange, onImportFont, onDeleteFont)
            SettingCard(title = "排版") {
                FontSizeControl(config, onChange)
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
                    label = "版心宽度（单行最长）",
                    options = listOf(0f to "全屏", 28f to "窄", 34f to "适中", 42f to "宽"),
                    selected = config.maxColumnWidthEm,
                    onSelect = { value -> onChange { it.copy(maxColumnWidthEm = value) } },
                    columns = 4
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
        }
        SettingsCategory.THEME -> {
BackgroundSection(config, onChange, onPickImage)
            SettingCard(title = "自动夜间") {
                SettingSwitchRow(
                    label = "定时进入夜间主题",
                    checked = config.autoNightEnabled,
                    onCheckedChange = { value -> onChange { it.copy(autoNightEnabled = value) } }
                )
                if (config.autoNightEnabled) {
                    LabeledSlider(
                        label = "开始时间",
                        value = (config.autoNightStartMinute / 60).toFloat(),
                        valueText = "%d:00".format(config.autoNightStartMinute / 60),
                        range = 0f..23f,
                        steps = 22,
                        onChange = { value -> onChange { it.copy(autoNightStartMinute = value.toInt() * 60) } }
                    )
                    LabeledSlider(
                        label = "结束时间",
                        value = (config.autoNightEndMinute / 60).toFloat(),
                        valueText = "%d:00".format(config.autoNightEndMinute / 60),
                        range = 0f..23f,
                        steps = 22,
                        onChange = { value -> onChange { it.copy(autoNightEndMinute = value.toInt() * 60) } }
                    )
                }
            }
        }
        SettingsCategory.INTERACTION -> {
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
        }
        SettingsCategory.SCREEN -> {
            BrightnessSettings(config, onChange)
            SettingCard(title = "屏幕与交互") {
                SettingSwitchRow(
                    label = "屏幕常亮",
                    checked = config.keepScreenOn,
                    onCheckedChange = { value -> onChange { it.copy(keepScreenOn = value) } }
                )
                SettingSwitchRow(
                    label = "全屏沉浸",
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
        }
        SettingsCategory.EPUB -> {
            SettingCard(title = "EPUB 排版") {
                SegmentedRow(
                    options = listOf(EpubLayoutMode.UNIFIED to "统一排版", EpubLayoutMode.ORIGINAL to "保留原书"),
                    selected = config.epubLayoutMode,
                    onSelect = { key -> onChange { it.copy(epubLayoutMode = key) } }
                )
                SettingSwitchRow(
                    label = "显示插图",
                    checked = config.allowImages,
                    onCheckedChange = { value -> onChange { it.copy(allowImages = value) } }
                )
            }
        }
        SettingsCategory.TTS -> {
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
                // 朗读读不出声/读错音，多半是系统 TTS 引擎或中文语音没装/没选对。
                // 这里直接把你送到系统设置，不自己管引擎列表。
                com.mirad.reader.ui.common.SettingNavRow(
                    label = "选择朗读语音 / 引擎",
                    onClick = { openTtsSettings(context) }
                )
            }
        }
        else -> Unit
    }
}
