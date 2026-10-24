package com.mirad.reader.ui.settings.reading

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mirad.reader.core.model.ReaderConfig
import com.mirad.reader.ui.common.CardDivider
import com.mirad.reader.ui.common.SettingCard
import com.mirad.reader.ui.common.SettingSwitchRow

/** 「页眉页脚」分类页：从「屏幕与信息」拆出的独立分类。 */
@Composable
fun HeaderFooterSection(
    config: ReaderConfig,
    onChange: ((ReaderConfig) -> ReaderConfig) -> Unit
) {
    SettingCard(title = "页眉页脚") {
        SettingSwitchRow(
            label = "显示页眉",
            checked = config.showHeader,
            onCheckedChange = { value -> onChange { it.copy(showHeader = value) } }
        )
        if (config.showHeader) {
            Column(Modifier.padding(start = 16.dp)) {
                SettingSwitchRow(
                    label = "显示章节名",
                    checked = config.showChapterTitleInHeader,
                    onCheckedChange = { value -> onChange { it.copy(showChapterTitleInHeader = value) } }
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
        CardDivider()
        SettingSwitchRow(
            label = "显示页脚",
            checked = config.showFooter,
            onCheckedChange = { value -> onChange { it.copy(showFooter = value) } }
        )
        if (config.showFooter) {
            Column(Modifier.padding(start = 16.dp)) {
                SettingSwitchRow(
                    label = "显示页码",
                    checked = config.showPageNumber,
                    onCheckedChange = { value -> onChange { it.copy(showPageNumber = value) } }
                )
            }
        }
    }
}
