package com.mirad.reader.ui.settings.reading

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import com.mirad.reader.core.model.ReaderConfig
import com.mirad.reader.core.model.ReadingFont
import com.mirad.reader.core.model.TextAlignKey
import com.mirad.reader.ui.common.CardDivider
import com.mirad.reader.ui.common.ChoiceRow
import com.mirad.reader.ui.common.FontSection
import com.mirad.reader.ui.common.LabeledSlider
import com.mirad.reader.ui.common.MiradTextButton
import com.mirad.reader.ui.common.SettingCard
import com.mirad.reader.ui.common.SettingNavRow
import com.mirad.reader.ui.common.SettingSwitchRow
import com.mirad.reader.ui.settings.ReadingSettingsPage
import com.mirad.reader.ui.settings.canEditReaderTypography

/** 字号快捷控件：阅读面板首页与「字体与排版」页共用。 */
@Composable
fun FontSizeControl(config: ReaderConfig, onChange: ((ReaderConfig) -> ReaderConfig) -> Unit, enabled: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        MiradTextButton(onClick = { onChange { it.copy(fontSizeSp = (it.fontSizeSp - 1f).coerceAtLeast(12f)) } }, enabled = enabled && config.fontSizeSp > 12f) { Text("减小") }
        Column(Modifier.weight(1f)) {
            LabeledSlider("字号", config.fontSizeSp, "${config.fontSizeSp.toInt()} px", 12f..60f, hapticStep = 5f,
                onChange = { value -> onChange { it.copy(fontSizeSp = value) } }, steps = 47, enabled = enabled)
        }
        MiradTextButton(onClick = { onChange { it.copy(fontSizeSp = (it.fontSizeSp + 1f).coerceAtMost(60f)) } }, enabled = enabled && config.fontSizeSp < 60f) { Text("增大") }
    }
}

/**
 * 「字体与排版」分类页。EPUB 保留原书时字体与文字排版由原书决定：
 * 字体与字号在此页，其他排版设置进入子页。
 */
@Composable
fun TypographySection(
    config: ReaderConfig,
    onChange: ((ReaderConfig) -> ReaderConfig) -> Unit,
    fonts: List<ReadingFont> = emptyList(),
    onDeleteFont: (ReadingFont) -> Unit = {},
    isEpub: Boolean? = null,
    onOpenPage: (ReadingSettingsPage) -> Unit = {}
) {
    val editableTypography = canEditReaderTypography(config, isEpub)
    val disabledAlpha = if (editableTypography) 1f else 0.45f
    val gatedChange: ((ReaderConfig) -> ReaderConfig) -> Unit =
        if (editableTypography) onChange else remember { { } }
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.alpha(disabledAlpha)) {
            FontSection(config, fonts, gatedChange, onDelete = onDeleteFont.takeIf { editableTypography })
        }
        if (!editableTypography) {
            SettingCard {
                SettingNavRow("EPUB 排版", value = "保留原书",
                    subtitle = "原书版式下仅保留页边距与版心设置",
                    onClick = { onOpenPage(ReadingSettingsPage.EPUB) })
            }
        }
        SettingCard(title = "排版") {
            Box(Modifier.alpha(disabledAlpha)) { FontSizeControl(config, gatedChange, enabled = editableTypography) }
            CardDivider()
            SettingNavRow("排版设置", onClick = { onOpenPage(ReadingSettingsPage.LAYOUT) })
        }
    }
}

/** 字体与排版的子页：间距、缩进与版面选项共用原有配置编辑链路。 */
@Composable
fun LayoutSection(
    config: ReaderConfig,
    onChange: ((ReaderConfig) -> ReaderConfig) -> Unit,
    isEpub: Boolean? = null,
    onOpenPage: (ReadingSettingsPage) -> Unit = {}
) {
    val editableTypography = canEditReaderTypography(config, isEpub)
    val disabledAlpha = if (editableTypography) 1f else 0.45f
    val gatedChange: ((ReaderConfig) -> ReaderConfig) -> Unit =
        if (editableTypography) onChange else remember { { } }
    Column(Modifier.fillMaxWidth()) {
        if (!editableTypography) {
            SettingCard {
                SettingNavRow("EPUB 排版", value = "保留原书",
                    subtitle = "原书版式下仅保留页边距与版心设置",
                    onClick = { onOpenPage(ReadingSettingsPage.EPUB) })
            }
        }
        SettingCard {
            Column(Modifier.alpha(disabledAlpha)) {
                LabeledSlider(
                    label = "行距",
                    value = config.lineHeightMultiplier,
                    valueText = String.format("%.2f 倍", config.lineHeightMultiplier),
                    range = 1.1f..2.8f,
                    hapticStep = 0.1f,
                    enabled = editableTypography,
                    steps = 16,
                    onChange = { value -> gatedChange { it.copy(lineHeightMultiplier = value) } }
                )
                LabeledSlider(
                    label = "段间距",
                    value = config.paragraphSpacingEm,
                    valueText = String.format("%.2f em", config.paragraphSpacingEm),
                    range = 0f..1.6f,
                    hapticStep = 0.1f,
                    enabled = editableTypography,
                    steps = 15,
                    onChange = { value -> gatedChange { it.copy(paragraphSpacingEm = value) } }
                )
                LabeledSlider(
                    label = "首行缩进",
                    value = config.firstLineIndentEm,
                    valueText = String.format("%.1f 字", config.firstLineIndentEm),
                    range = 0f..3f,
                    hapticStep = 0.5f,
                    enabled = editableTypography,
                    steps = 5,
                    onChange = { value -> gatedChange { it.copy(firstLineIndentEm = value) } }
                )
                LabeledSlider(
                    label = "字间距",
                    value = config.letterSpacingEm,
                    valueText = String.format("%.2f em", config.letterSpacingEm),
                    range = 0f..0.30f,
                    hapticStep = 0.02f,
                    enabled = editableTypography,
                    steps = 14,
                    onChange = { value -> gatedChange { it.copy(letterSpacingEm = value) } }
                )
            }
            LabeledSlider(
                label = "页边距",
                value = config.horizontalPaddingDp.toFloat(),
                valueText = "${config.horizontalPaddingDp} dp",
                range = 6f..40f,
                hapticStep = 4f,
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
            Column(Modifier.alpha(disabledAlpha)) {
                CardDivider()
                ChoiceRow(
                    label = "对齐",
                    options = listOf(
                        TextAlignKey.JUSTIFY to "两端对齐",
                        TextAlignKey.LEFT to "左对齐",
                        TextAlignKey.CENTER to "居中"
                    ),
                    selected = config.textAlign,
                    onSelect = { key -> gatedChange { it.copy(textAlign = key) } },
                    columns = 3
                )
                SettingSwitchRow(
                    label = "加粗正文",
                    checked = config.boldText,
                    onCheckedChange = { value -> gatedChange { it.copy(boldText = value) } }
                )
            }
        }
    }
}
