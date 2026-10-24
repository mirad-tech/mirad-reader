package com.mirad.reader.ui.settings.reading

import androidx.compose.runtime.Composable
import com.mirad.reader.core.model.EpubLayoutMode
import com.mirad.reader.core.model.ReaderConfig
import com.mirad.reader.ui.common.SegmentedRow
import com.mirad.reader.ui.common.SettingCard
import com.mirad.reader.ui.common.SettingSwitchRow

/** 「EPUB」分类页。 */
@Composable
fun EpubSection(
    config: ReaderConfig,
    onChange: ((ReaderConfig) -> ReaderConfig) -> Unit
) {
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
