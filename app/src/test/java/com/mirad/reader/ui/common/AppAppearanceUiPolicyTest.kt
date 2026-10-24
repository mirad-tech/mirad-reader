package com.mirad.reader.ui.common

import com.mirad.reader.core.model.BuiltinBackgrounds
import com.mirad.reader.core.model.ReadingStyle
import com.mirad.reader.core.model.ThemeKey
import com.mirad.reader.core.model.ThemePaletteResolver
import org.junit.Assert.*
import org.junit.Test

class AppAppearanceUiPolicyTest {
    @Test fun fineGrainedChoicesBelongToExactlyTheCurrentGroup() {
        for (dark in listOf(false, true)) {
            val options = readingThemeChoices(dark)
            assertEquals(ReadingStyle.themes(dark), options.map { it.first })
            assertFalse(options.any { it.first == ThemeKey.SYSTEM })
        }
        assertEquals("深灰", readingThemeChoices(true).first().second)
    }

    @Test fun builtinBackgroundsCannotOfferAnOppositeMode() {
        val day = readingBackgroundChoices(false)
        val night = readingBackgroundChoices(true)
        assertTrue(day.isNotEmpty())
        assertTrue(night.isNotEmpty())
        assertTrue(day.none { it.isDark })
        assertTrue(night.all { it.isDark })
        assertEquals(BuiltinBackgrounds.ALL.toSet(), (day + night).toSet())
    }

    @Test fun migratedCustomColorRemainsSelectableOnlyInItsMatchingGroup() {
        val customLight = 0xFFEEEECC.toInt()
        val customDark = 0xFF183333.toInt()
        assertTrue(customLight in readingCustomColors(false, customLight))
        assertTrue(customDark in readingCustomColors(true, customDark))
        assertFalse(customLight in readingCustomColors(true, customLight))
        assertFalse(customDark in readingCustomColors(false, customDark))
        for (dark in listOf(false, true)) {
            assertTrue(readingCustomColors(dark, customLight).all { ThemePaletteResolver.isDarkColor(it) == dark })
        }
    }
}
