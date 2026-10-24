package com.mirad.reader.ui.settings.reading

import androidx.compose.runtime.Composable
import com.mirad.reader.core.model.ReaderConfig
import com.mirad.reader.core.model.ReadingFont
import com.mirad.reader.ui.settings.ReadingSettingsPage

/**
 * 阅读设置分类页分发：设置页详情与阅读面板共用同一组 Section，
 * 页面集合见 [ReadingSettingsPage]。
 */
@Composable
fun ReadingSettingsHost(
    page: ReadingSettingsPage,
    config: ReaderConfig,
    onChange: ((ReaderConfig) -> ReaderConfig) -> Unit,
    fonts: List<ReadingFont> = emptyList(),
    onImportFont: () -> Unit = {},
    onDeleteFont: (ReadingFont) -> Unit = {},
    onPickImage: (dark: Boolean) -> Unit = {},
    isEpub: Boolean? = null,
    onOpenPage: (ReadingSettingsPage) -> Unit = {}
) {
    when (page) {
        ReadingSettingsPage.TYPOGRAPHY -> TypographySection(
            config = config, onChange = onChange, fonts = fonts,
            onImportFont = onImportFont, onDeleteFont = onDeleteFont,
            isEpub = isEpub, onOpenPage = onOpenPage
        )
        ReadingSettingsPage.THEME -> ThemeSection(config, onChange, onPickImage)
        ReadingSettingsPage.INTERACTION -> InteractionSection(config, onChange)
        ReadingSettingsPage.SCREEN -> ScreenSection(config, onChange)
        ReadingSettingsPage.HEADER_FOOTER -> HeaderFooterSection(config, onChange)
        ReadingSettingsPage.EPUB -> EpubSection(config, onChange)
        ReadingSettingsPage.TTS -> TtsSection(config, onChange)
    }
}
