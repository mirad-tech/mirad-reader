package com.mirad.reader.core.model

import org.junit.Assert.*
import org.junit.Test

class AppAppearanceTest {
    @Test fun oneModeControlsBothSystemAndExplicitDayNightResolution() {
        val defaults = AppAppearance.DEFAULT
        assertFalse(defaults.effectiveDark(false))
        assertTrue(defaults.effectiveDark(true))
        assertFalse(defaults.copy(mode = AppThemeMode.DAY).effectiveDark(true))
        assertTrue(defaults.copy(mode = AppThemeMode.NIGHT).effectiveDark(false))
        assertEquals(ThemeKey.PAPER, defaults.style(false).theme)
        assertEquals(ThemeKey.DARK, defaults.style(true).theme)
    }

    @Test fun projectionChangesOnlyTheFiveAppearanceFields() {
        val base = ReaderConfig.DEFAULT.copy(
            fontSizeSp = 31f,
            customFontPath = "/fonts/kept.ttf",
            pageMode = PageMode.SCROLL,
            lineHeightMultiplier = 2.1f,
            brightness = .42f,
            fullScreen = false,
            autoPageSeconds = 73,
            backgroundImageUri = "/old.jpg"
        )
        val night = ReadingStyle.NIGHT_DEFAULT.copy(
            theme = ThemeKey.BLACK,
            backgroundType = BackgroundType.IMAGE,
            backgroundImageUri = "/night.jpg",
            backgroundImageAlpha = .37f
        )
        val appearance = AppAppearance(mode = AppThemeMode.NIGHT, night = night)
        assertEquals(
            base.copy(
                theme = night.theme,
                backgroundType = night.backgroundType,
                backgroundColor = night.backgroundColor,
                backgroundImageUri = night.backgroundImageUri,
                backgroundImageAlpha = night.backgroundImageAlpha
            ),
            appearance.resolveReaderConfig(base, systemDark = false)
        )
    }

    @Test fun changingOneStylePreservesTheModeAndTheOtherStyle() {
        val day = ReadingStyle.DAY_DEFAULT.copy(theme = ThemeKey.GREEN, backgroundImageUri = "/day.jpg")
        val night = ReadingStyle.NIGHT_DEFAULT.copy(theme = ThemeKey.BLACK, backgroundImageUri = "/night.jpg")
        val appearance = AppAppearance.DEFAULT.copy(mode = AppThemeMode.NIGHT)
            .withReadingStyle(false, day)
            .withReadingStyle(true, night)
        assertEquals(AppThemeMode.NIGHT, appearance.mode)
        assertEquals(day, appearance.style(false))
        assertEquals(night, appearance.style(true))
        assertEquals(day, appearance.copy(mode = AppThemeMode.DAY).style(false))
    }

    @Test fun everyLegacyThemeMigratesIntoItsOwnGroupWithoutChangingGlobalMode() {
        val cases = listOf(
            ThemeKey.LIGHT to false,
            ThemeKey.PAPER to false,
            ThemeKey.SEPIA to false,
            ThemeKey.GREEN to false,
            ThemeKey.DARK to true,
            ThemeKey.BLACK to true,
            ThemeKey.CUSTOM to false
        )
        for ((theme, dark) in cases) {
            val old = ReaderConfig.DEFAULT.copy(theme = theme, backgroundColor = 0xFFEEDDBB.toInt())
            val appearance = AppAppearance.fromLegacy(old)
            assertEquals("$theme must retain its group selection", theme, appearance.style(dark).theme)
            assertEquals(AppThemeMode.SYSTEM, appearance.mode)
            assertEquals(if (dark) ReadingStyle.DAY_DEFAULT else ReadingStyle.NIGHT_DEFAULT, appearance.style(!dark))
        }
        val customNight = ReaderConfig.DEFAULT.copy(theme = ThemeKey.CUSTOM, backgroundColor = 0xFF192B30.toInt())
        assertEquals(0xFF192B30.toInt(), AppAppearance.fromLegacy(customNight).night.backgroundColor)
        assertEquals(ThemeKey.CUSTOM, AppAppearance.fromLegacy(customNight).night.theme)
    }

    @Test fun legacySystemBuildsSeparateLightAndDarkStylesAndKeepsPictureInOneGroup() {
        val old = ReaderConfig.DEFAULT.copy(
            theme = ThemeKey.SYSTEM,
            backgroundType = BackgroundType.IMAGE,
            backgroundImageUri = BuiltinBackgrounds.byKey(BuiltinBackgrounds.NIGHT)!!.uri,
            backgroundImageAlpha = .62f
        )
        val appearance = AppAppearance.fromLegacy(old)
        assertEquals(AppThemeMode.SYSTEM, appearance.mode)
        assertEquals(ThemeKey.LIGHT, appearance.day.theme)
        assertEquals(ThemeKey.DARK, appearance.night.theme)
        assertNull(appearance.day.backgroundImageUri)
        assertEquals(old.backgroundImageUri, appearance.night.backgroundImageUri)
        assertEquals(.62f, appearance.night.backgroundImageAlpha)
        assertEquals(BackgroundType.IMAGE, appearance.night.backgroundType)
    }

    @Test fun builtinImageMetadataRepairsLegacyMismatchedTextColors() {
        val night = BuiltinBackgrounds.byKey(BuiltinBackgrounds.NIGHT)!!
        val parchment = BuiltinBackgrounds.byKey(BuiltinBackgrounds.PARCHMENT)!!
        val onNight = AppAppearance.fromLegacy(ReaderConfig.DEFAULT.copy(
            theme = ThemeKey.PAPER,
            backgroundType = BackgroundType.IMAGE,
            backgroundImageUri = night.uri
        ))
        assertEquals(ThemeKey.DARK, onNight.night.theme)
        assertEquals(night.uri, onNight.night.backgroundImageUri)
        assertEquals(ReadingStyle.DAY_DEFAULT, onNight.day)

        val onDay = AppAppearance.fromLegacy(ReaderConfig.DEFAULT.copy(
            theme = ThemeKey.BLACK,
            backgroundType = BackgroundType.IMAGE,
            backgroundImageUri = parchment.uri
        ))
        assertEquals(parchment.suggestedTheme, onDay.day.theme)
        assertEquals(parchment.uri, onDay.day.backgroundImageUri)
        assertEquals(ReadingStyle.NIGHT_DEFAULT, onDay.night)
    }

    @Test fun customImageKeepsItsPathAndStrengthInTheLegacyThemeGroup() {
        val old = ReaderConfig.DEFAULT.copy(
            theme = ThemeKey.CUSTOM,
            backgroundColor = 0xFF203A48.toInt(),
            backgroundType = BackgroundType.IMAGE,
            backgroundImageUri = "/data/user/0/com.mirad.reader/files/my background.jpg",
            backgroundImageAlpha = .43f
        )
        val migrated = AppAppearance.fromLegacy(old)
        assertEquals(ReadingStyle.from(old), migrated.night)
        assertEquals(ReadingStyle.DAY_DEFAULT, migrated.day)
    }

    @Test fun aDisabledOldPictureDoesNotOverrideTheSelectedColorGroup() {
        val old = ReaderConfig.DEFAULT.copy(
            theme = ThemeKey.GREEN,
            backgroundType = BackgroundType.COLOR,
            backgroundImageUri = BuiltinBackgrounds.byKey(BuiltinBackgrounds.NIGHT)!!.uri
        )
        val migrated = AppAppearance.fromLegacy(old)
        assertEquals(ThemeKey.GREEN, migrated.day.theme)
        assertEquals(old.backgroundImageUri, migrated.day.backgroundImageUri)
        assertEquals(ReadingStyle.NIGHT_DEFAULT, migrated.night)
    }

    @Test fun styleChoicesCannotReintroduceModeOrOppositeGroupColors() {
        assertFalse(ThemeKey.SYSTEM in ReadingStyle.themes(false))
        assertFalse(ThemeKey.SYSTEM in ReadingStyle.themes(true))
        assertFalse(ThemeKey.BLACK in ReadingStyle.themes(false))
        assertFalse(ThemeKey.PAPER in ReadingStyle.themes(true))
        assertEquals(ThemeKey.PAPER, ReadingStyle.DAY_DEFAULT.copy(theme = ThemeKey.BLACK).normalized(false).theme)
        val wrongCustom = ReadingStyle.NIGHT_DEFAULT.copy(theme = ThemeKey.CUSTOM, backgroundColor = 0xFFFFFFFF.toInt())
        assertEquals(ThemeKey.DARK, wrongCustom.normalized(true).theme)
        assertEquals(ReadingStyle.NIGHT_DEFAULT.backgroundColor, wrongCustom.normalized(true).backgroundColor)
        val wrongImage = ReadingStyle.DAY_DEFAULT.copy(
            backgroundType = BackgroundType.IMAGE,
            backgroundImageUri = BuiltinBackgrounds.byKey(BuiltinBackgrounds.NIGHT)!!.uri
        ).normalized(false)
        assertEquals(BackgroundType.IMAGE, wrongImage.backgroundType)
        assertNull(wrongImage.backgroundImageUri)
    }

    @Test fun serializationPreservesBothStylesAndToleratesFutureFields() {
        val value = AppAppearance(
            mode = AppThemeMode.DAY,
            day = ReadingStyle.DAY_DEFAULT.copy(theme = ThemeKey.SEPIA, backgroundImageUri = "/day.jpg", backgroundImageAlpha = .24f),
            night = ReadingStyle.NIGHT_DEFAULT.copy(theme = ThemeKey.BLACK, backgroundImageUri = "/night.jpg", backgroundImageAlpha = .86f)
        )
        val json = AppAppearanceJson.encode(value)
        assertEquals(value, AppAppearanceJson.decode(json))
        assertEquals(value, AppAppearanceJson.decode(json.dropLast(1) + ",\"futureFlag\":{\"enabled\":true}}"))
    }

    @Test fun malformedAndFutureValuesHaveSafeGroupDefaults() {
        assertEquals(AppAppearance.DEFAULT, AppAppearanceJson.decode("{broken"))
        val unknownMode = AppAppearanceJson.decode("""{"mode":"FUTURE","day":{"theme":"GREEN"},"night":{"theme":"LIGHT"}}""")
        assertEquals(AppThemeMode.SYSTEM, unknownMode.mode)
        assertEquals(ThemeKey.GREEN, unknownMode.day.theme)
        assertEquals(ThemeKey.DARK, unknownMode.night.theme)
        assertEquals(1f, ReadingStyle.DAY_DEFAULT.copy(backgroundImageAlpha = Float.NaN).normalized(false).backgroundImageAlpha)
        assertEquals(0f, ReadingStyle.DAY_DEFAULT.copy(backgroundImageAlpha = -1f).normalized(false).backgroundImageAlpha)
    }
}
