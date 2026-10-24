package com.mirad.reader.ui.settings.reading

import androidx.compose.runtime.Composable
import com.mirad.reader.core.model.ReaderConfig
import com.mirad.reader.ui.common.BackgroundSection

/** 「阅读主题与背景」分类页：正文样式按日/夜两组分区编辑。 */
@Composable
fun ThemeSection(
    config: ReaderConfig,
    onChange: ((ReaderConfig) -> ReaderConfig) -> Unit,
    onPickImage: (dark: Boolean) -> Unit
) {
    BackgroundSection(config, onChange, onPickImage)
}
