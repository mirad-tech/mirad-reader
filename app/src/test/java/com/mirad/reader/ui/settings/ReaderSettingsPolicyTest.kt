package com.mirad.reader.ui.settings

import com.mirad.reader.core.model.EpubLayoutMode
import com.mirad.reader.core.model.ReaderConfig
import org.junit.Assert.*
import org.junit.Test

class ReaderSettingsPolicyTest {
    @Test fun globalAndEpubEditorsExposeTheSameReadingPagesInOrder() {
        val expected = listOf(
            ReadingSettingsPage.TYPOGRAPHY, ReadingSettingsPage.THEME, ReadingSettingsPage.INTERACTION,
            ReadingSettingsPage.SCREEN, ReadingSettingsPage.HEADER_FOOTER, ReadingSettingsPage.EPUB, ReadingSettingsPage.TTS
        )
        assertEquals(expected, readingSettingsPages())
        assertEquals(expected, readingSettingsPages(isEpub = true))
        assertEquals(
            listOf(AppSettingsPage.APPEARANCE, AppSettingsPage.SHELF, AppSettingsPage.ABOUT),
            appSettingsPages
        )
        // 阅读级与应用级页面不得重名，路由 parse 才不会歧义。
        assertTrue(appSettingsPages.none { app ->
            readingSettingsPages().any { it.name == app.name }
        })
    }

    @Test fun routeParseKeepsLegacyScreenNameAndAppPages() {
        assertEquals(SettingsRoute.Reading(ReadingSettingsPage.SCREEN), SettingsRoute.parse("SCREEN"))
        assertEquals(SettingsRoute.Reading(ReadingSettingsPage.THEME), SettingsRoute.parse("theme"))
        assertEquals(SettingsRoute.App(AppSettingsPage.APPEARANCE), SettingsRoute.parse("APPEARANCE"))
        assertNull(SettingsRoute.parse(null))
        assertNull(SettingsRoute.parse("NO_SUCH_PAGE"))
    }

    @Test fun txtReaderHidesOnlyEpubAndRetainsSpeechSettings() {
        val pages = readingSettingsPages(isEpub = false)
        assertEquals(readingSettingsPages().filterNot { it == ReadingSettingsPage.EPUB }, pages)
        assertTrue(ReadingSettingsPage.TTS in pages)
    }

    @Test fun originalEpubDoesNotDisableTypographyForGlobalOrTxtSettings() {
        val config = ReaderConfig.DEFAULT.copy(epubLayoutMode = EpubLayoutMode.ORIGINAL)
        assertFalse(canEditReaderTypography(config, isEpub = true))
        assertTrue(canEditReaderTypography(config, isEpub = false))
        assertTrue(canEditReaderTypography(config, isEpub = null))
    }

    @Test fun unifiedLayoutAllowsTypographyForEveryEntry() {
        val config = ReaderConfig.DEFAULT.copy(epubLayoutMode = EpubLayoutMode.UNIFIED)
        listOf(true, false, null).forEach { isEpub ->
            assertTrue(canEditReaderTypography(config, isEpub))
        }
    }
}
