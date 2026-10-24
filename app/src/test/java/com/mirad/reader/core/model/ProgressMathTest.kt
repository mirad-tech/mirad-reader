package com.mirad.reader.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressMathTest {

    private val chapters = listOf(100L, 200L, 300L, 400L)

    @Test
    fun percentAtChapterStarts() {
        assertEquals(0f, ProgressMath.percent(chapters, 0, 0), 0.0001f)
        assertEquals(0.1f, ProgressMath.percent(chapters, 1, 0), 0.0001f)
        assertEquals(0.3f, ProgressMath.percent(chapters, 2, 0), 0.0001f)
    }

    @Test
    fun percentInsideChapter() {
        assertEquals(0.2f, ProgressMath.percent(chapters, 1, 100), 0.0001f)
        assertEquals(1.0f, ProgressMath.percent(chapters, 3, 400), 0.0001f)
    }

    @Test
    fun percentForEmptyBookIsZero() {
        assertEquals(0f, ProgressMath.percent(emptyList(), 0, 0), 0.0001f)
    }

    @Test
    fun fromPercentRoundTrips() {
        for (p in listOf(0f, 0.09f, 0.1f, 0.35f, 0.99f)) {
            val locator = ProgressMath.fromPercent(chapters, p)
            val back = ProgressMath.percent(chapters, locator.chapterIndex, locator.charOffset)
            assertTrue("percent $p -> $back", Math.abs(back - p) < 0.01f)
        }
    }

    @Test
    fun fromPercentClampsOutOfRange() {
        val locator = ProgressMath.fromPercent(chapters, 2f)
        assertEquals(3, locator.chapterIndex)
        assertEquals(400, locator.charOffset)
    }
}

class ThemePaletteResolverTest {

    @Test
    fun systemThemeFollowsDarkMode() {
        val light = ThemePaletteResolver.resolve(ReaderConfig.DEFAULT.copy(theme = ThemeKey.SYSTEM), systemDark = false)
        val dark = ThemePaletteResolver.resolve(ReaderConfig.DEFAULT.copy(theme = ThemeKey.SYSTEM), systemDark = true)
        assertEquals(false, light.isDark)
        assertEquals(true, dark.isDark)
    }

    @Test
    fun customThemeDerivesTextColorFromBackgroundLuminance() {
        val lightCustom = ThemePaletteResolver.resolve(
            ReaderConfig.DEFAULT.copy(theme = ThemeKey.CUSTOM, backgroundColor = 0xFFFFFFFF.toInt()),
            systemDark = false
        )
        val darkCustom = ThemePaletteResolver.resolve(
            ReaderConfig.DEFAULT.copy(theme = ThemeKey.CUSTOM, backgroundColor = 0xFF101010.toInt()),
            systemDark = false
        )
        assertEquals(false, lightCustom.isDark)
        assertEquals(true, darkCustom.isDark)
        assertTrue(darkCustom.text != lightCustom.text)
    }
}
