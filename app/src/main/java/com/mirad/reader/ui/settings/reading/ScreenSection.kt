package com.mirad.reader.ui.settings.reading

import androidx.compose.runtime.Composable
import com.mirad.reader.core.model.OrientationKey
import com.mirad.reader.core.model.ReaderConfig
import com.mirad.reader.ui.common.CardDivider
import com.mirad.reader.ui.common.ChoiceRow
import com.mirad.reader.ui.common.LabeledSlider
import com.mirad.reader.ui.common.SettingCard
import com.mirad.reader.ui.common.SettingSwitchRow

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

/** 「屏幕」分类页：亮度与屏幕行为，页眉页脚已拆到独立分类。 */
@Composable
fun ScreenSection(
    config: ReaderConfig,
    onChange: ((ReaderConfig) -> ReaderConfig) -> Unit
) {
    BrightnessSettings(config, onChange)
    SettingCard(title = "屏幕") {
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
}
