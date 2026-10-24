package com.mirad.reader.ui.settings.reading

import androidx.compose.runtime.Composable
import com.mirad.reader.core.model.PageAnim
import com.mirad.reader.core.model.PageMode
import com.mirad.reader.core.model.ReaderConfig
import com.mirad.reader.core.model.TapZoneKey
import com.mirad.reader.ui.common.ChoiceRow
import com.mirad.reader.ui.common.SegmentedRow
import com.mirad.reader.ui.common.SettingCard
import com.mirad.reader.ui.common.SettingSwitchRow

/** 「翻页与交互」分类页。 */
@Composable
fun InteractionSection(
    config: ReaderConfig,
    onChange: ((ReaderConfig) -> ReaderConfig) -> Unit
) {
    SettingCard(title = "阅读方式") {
        SegmentedRow(
            options = listOf(PageMode.PAGED to "左右翻页", PageMode.SCROLL to "上下滚动"),
            selected = config.pageMode,
            onSelect = { mode -> onChange { it.copy(pageMode = mode) } }
        )
        if (config.pageMode == PageMode.PAGED) {
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
        SettingSwitchRow(
            label = "音量键翻页",
            subtitle = "关闭后音量键交还系统调节音量",
            checked = config.volumeKeyPage,
            onCheckedChange = { value -> onChange { it.copy(volumeKeyPage = value) } }
        )
    }
    SettingCard(title = "自动阅读") {
        AutoReadSpeedControls(config, onChange)
    }
}
