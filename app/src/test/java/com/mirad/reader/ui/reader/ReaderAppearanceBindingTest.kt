package com.mirad.reader.ui.reader

import com.mirad.reader.core.model.AppAppearance
import com.mirad.reader.core.model.AppThemeMode
import com.mirad.reader.core.model.BackgroundType
import com.mirad.reader.core.model.ReaderConfig
import com.mirad.reader.core.model.ThemePaletteResolver
import com.mirad.reader.core.reader.ReaderCss
import com.mirad.reader.reader.ReaderBackgroundRevision
import org.junit.Assert.*
import org.junit.Test

class ReaderAppearanceBindingTest {
    @Test fun explicitModeChangeRefreshesEvenWhenLegacyThemeIsNotSystem() {
        val appearance = AppAppearance.DEFAULT
        val day = appearance.copy(mode = AppThemeMode.DAY).resolveReaderConfig(ReaderConfig.DEFAULT, false)
        val night = appearance.copy(mode = AppThemeMode.NIGHT).resolveReaderConfig(day, false)
        assertTrue(shouldRefreshReaderAppearance(day, night, false, true, false))
    }

    @Test fun sameAppliedConfigAvoidsDuplicateCssUpdatesButResumeAlwaysRefreshes() {
        val config = ReaderConfig.DEFAULT
        assertFalse(shouldRefreshReaderAppearance(config, config, false, false, false))
        assertTrue(shouldRefreshReaderAppearance(config, config, false, false, true))
        assertTrue(shouldRefreshReaderAppearance(config, config, false, true, false))
        assertTrue(shouldRefreshReaderAppearance(config, config.copy(fontSizeSp = 32f), false, false, false))
    }

    @Test fun switchingDayAndNightImagesChangesCssUrlWithoutReloadingForTypography() {
        val revisions = ReaderBackgroundRevision()
        val baseUrl = "https://mirad.local/book/1/bg"
        val day = revisions.url(baseUrl, "asset:backgrounds/paper.jpg")
        val sameImage = revisions.url(baseUrl, "asset:backgrounds/paper.jpg")
        val night = revisions.url(baseUrl, "asset:backgrounds/night.jpg")
        val dayAgain = revisions.url(baseUrl, "asset:backgrounds/paper.jpg")
        assertEquals(day, sameImage)
        assertNotEquals(day, night)
        assertNotEquals(day, dayAgain)
        val config = ReaderConfig.DEFAULT.copy(backgroundType = BackgroundType.IMAGE)
        val palette = ThemePaletteResolver.resolve(config, false)
        val dayCss = ReaderCss.themeCss(config, palette, backgroundImageUrl = day)
        val resizedCss = ReaderCss.themeCss(config.copy(fontSizeSp = 32f), palette, backgroundImageUrl = sameImage)
        assertTrue(dayCss.contains("url('$day')"))
        assertTrue(resizedCss.contains("url('$day')"))
        assertTrue(ReaderCss.themeCss(config, palette, backgroundImageUrl = night).contains("url('$night')"))
    }

    @Test fun changedContentsAtTheSameFilePathReceiveANewResourceRevision() {
        val revisions = ReaderBackgroundRevision()
        val first = revisions.url("https://mirad.local/book/1/bg", "file:bg_day.jpg:100:1024")
        val changed = revisions.url("https://mirad.local/book/1/bg", "file:bg_day.jpg:200:2048")
        assertNotEquals(first, changed)
    }
}
