package com.mirad.reader.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ReadingFontsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun legacyFontFamilyKeysStillHaveReadableFallbackStacks() {
        val keys = listOf(FontFamilyKey.SYSTEM, FontFamilyKey.SERIF, FontFamilyKey.SANS,
            FontFamilyKey.KAITI, FontFamilyKey.FANGSONG, FontFamilyKey.ROUND, FontFamilyKey.LIGHT, FontFamilyKey.MONO)
        for (key in keys) {
            val stack = ReadingFonts.stackOf(key, null)
            assertTrue("$key 的字体栈不应为空", stack.isNotBlank())
            assertTrue("字体栈应以通用族结尾", stack.endsWith("sans-serif") || stack.endsWith("serif") || stack.endsWith("monospace"))
        }
    }

    @Test
    fun kaitiAndFangsongFallBackToSerifFamily() {
        val kaiti = ReadingFonts.stackOf(FontFamilyKey.KAITI, null)
        assertTrue(kaiti.contains("KaiTi"))
        assertTrue(kaiti.endsWith("serif"))

        val fangsong = ReadingFonts.stackOf(FontFamilyKey.FANGSONG, null)
        assertTrue(fangsong.contains("FangSong"))
        assertTrue(fangsong.endsWith("serif"))
    }

    @Test
    fun lightPresetCarriesLighterWeight() {
        assertEquals(300, ReadingFonts.weightOf(FontFamilyKey.LIGHT))
        assertNull(ReadingFonts.weightOf(FontFamilyKey.SERIF))
    }

    @Test
    fun customFontWithoutUrlFallsBackToSystemStack() {
        val withoutUrl = ReadingFonts.stackOf(FontFamilyKey.CUSTOM, null)
        assertTrue(!withoutUrl.contains("MiradCustom"))

        val withUrl = ReadingFonts.stackOf(FontFamilyKey.CUSTOM, "https://mirad.local/book/1/font")
        assertTrue(withUrl.startsWith("\"MiradCustom\""))
    }

    @Test
    fun configuredImportLosesItsLabelWhenRemovedFromAvailableFonts() {
        val path = temporary.newFile("LegacyFont.woff2").absolutePath
        val config = ReaderConfig.DEFAULT.copy(customFontAsset = null, customFontPath = path)
        val imported = ReadingFont("LegacyFont", filePath = path)
        assertEquals("LegacyFont", ReadingFonts.customLabel(config, listOf(imported)))
        assertNull(ReadingFonts.customLabel(config, emptyList()))
    }

    @Test
    fun configuredBundledFontTakesPriorityOverAnImportedPath() {
        val path = temporary.newFile("LegacyFont.woff2").absolutePath
        val config = ReaderConfig.DEFAULT.copy(customFontAsset = ReadingFonts.BUNDLED_SERIF, customFontPath = path)
        val imported = ReadingFont("LegacyFont", filePath = path)
        val bundled = ReadingFont("思源宋体", assetName = ReadingFonts.BUNDLED_SERIF)
        assertEquals("思源宋体", ReadingFonts.customLabel(config, listOf(imported, bundled)))
        assertEquals("思源宋体", ReadingFonts.customLabel(config, emptyList()))
        assertNull(ReadingFonts.customLabel(config.copy(customFontAsset = "fonts/missing.woff2"), listOf(imported)))
    }

    @Test
    fun retiredPreviewFontFallsBackWithoutChangingOtherReadingSettings() {
        for (name in listOf("BarlowCondensed-Bold", "BarlowCondensed-Bold.ttf", "BarlowCondensed-Bold-2.OTF")) {
            val config = ReaderConfig.DEFAULT.copy(
                customFontAsset = null,
                customFontPath = "/data/user/0/com.mirad.reader/files/fonts/$name",
                fontSizeSp = 31f,
                pageMode = PageMode.SCROLL
            )
            assertEquals(
                config.copy(customFontPath = null, customFontAsset = ReadingFonts.DEFAULT_CUSTOM_FONT),
                ReaderConfigJson.decode(ReaderConfigJson.encode(config))
            )
        }
    }

    @Test
    fun retiredFontDisplayNamesAreFilteredWithoutMatchingOtherFonts() {
        assertTrue(ReadingFonts.isRetiredPreviewFont("BarlowCondensed-Bold"))
        assertTrue(ReadingFonts.isRetiredPreviewFont("barlowcondensed-bold-2"))
        assertTrue(!ReadingFonts.isRetiredPreviewFont("BarlowCondensed-BoldItalic"))
        assertTrue(!ReadingFonts.isRetiredPreviewFont("思源宋体"))
    }

    @Test
    fun otherImportedFontsAndSelectedSystemFontsKeepTheirSettings() {
        val imported = ReaderConfig.DEFAULT.copy(
            customFontAsset = null,
            customFontPath = "/data/user/0/com.mirad.reader/files/fonts/BarlowCondensed-BoldItalic.ttf"
        )
        assertEquals(imported, ReaderConfigJson.decode(ReaderConfigJson.encode(imported)))
        val system = imported.copy(
            fontFamily = FontFamilyKey.SERIF,
            customFontPath = "/data/user/0/com.mirad.reader/files/fonts/BarlowCondensed-Bold.ttf"
        )
        val decoded = ReaderConfigJson.decode(ReaderConfigJson.encode(system))
        assertEquals(FontFamilyKey.SERIF, decoded.fontFamily)
        assertNull(decoded.customFontPath)
    }

    @Test
    fun bundledLabelLookup() {
        assertEquals("霞鹜文楷", ReadingFonts.bundledLabel("lxgw-wenkai-regular.woff2"))
        assertEquals("思源宋体", ReadingFonts.bundledLabel("noto-serif-sc-regular.woff2"))
        assertEquals("OtherFont", ReadingFonts.bundledLabel("OtherFont.ttf"))
    }

    @Test
    fun bundledFontsAreRealFilesWithReadableLabelsAndKeepTheExistingDefault() {
        assertEquals(ReadingFonts.BUNDLED_WENKAI, ReadingFonts.DEFAULT_CUSTOM_FONT)
        val assets = File("src/main/assets")
        assumeTrue("不在模块目录下运行，跳过素材检查", assets.isDirectory)
        for (path in listOf(ReadingFonts.BUNDLED_WENKAI, ReadingFonts.BUNDLED_SERIF,
            ReadingFonts.BUNDLED_SANS, ReadingFonts.BUNDLED_FANGSONG)) {
            val file = File(assets, path)
            assertTrue("缺少字体：$path", file.isFile && file.length() > 1024)
            assertTrue("字体显示名未登记：$path", ReadingFonts.bundledLabel(file.name) != file.nameWithoutExtension)
            assertEquals("字体容器必须是 WOFF2：$path", "wOF2", file.inputStream().use {
                String(ByteArray(4).also { header -> assertEquals(4, it.read(header)) }, Charsets.US_ASCII)
            })
        }
    }
}

class ReadingStyleNormalizationTest {

    @Test
    fun nightGroupDropsBuiltinDaylightTexturesAndDayGroupDropsNightTextures() {
        val lightPreset = BuiltinBackgrounds.ALL.first { !it.isDark }
        val darkPreset = BuiltinBackgrounds.ALL.first { it.isDark }
        val night = ReadingStyle.NIGHT_DEFAULT
            .copy(backgroundType = BackgroundType.IMAGE, backgroundImageUri = lightPreset.uri)
            .normalized(true)
        assertNull("夜间组不允许保留内置浅色纹理", night.backgroundImageUri)
        val day = ReadingStyle.DAY_DEFAULT
            .copy(backgroundType = BackgroundType.IMAGE, backgroundImageUri = darkPreset.uri)
            .normalized(false)
        assertNull("日间组不允许保留内置深色纹理", day.backgroundImageUri)
    }

    @Test
    fun customImagesSurviveNormalizationInBothGroups() {
        val custom = ReadingStyle.NIGHT_DEFAULT
            .copy(backgroundType = BackgroundType.IMAGE, backgroundImageUri = "/data/bg/night.jpg")
            .normalized(true)
        assertEquals("本机图片不参与内置素材的深浅约束", "/data/bg/night.jpg", custom.backgroundImageUri)
    }
}

class BuiltinBackgroundsTest {

    @Test
    fun keysAndUrisRoundTrip() {
        for (preset in BuiltinBackgrounds.ALL) {
            assertEquals(preset, BuiltinBackgrounds.fromUri(preset.uri))
            assertEquals(preset, BuiltinBackgrounds.byKey(preset.key))
        }
    }

    @Test
    fun nonBuiltinUrisAreRejected() {
        assertNull(BuiltinBackgrounds.fromUri(null))
        assertNull(BuiltinBackgrounds.fromUri(""))
        assertNull(BuiltinBackgrounds.fromUri("/data/user/0/com.mirad.reader/files/backgrounds/bg_1.jpg"))
        assertNull(BuiltinBackgrounds.fromUri("builtin:not-exist"))
    }

    @Test
    fun darkPresetSuggestsDarkThemeSoTextStaysReadable() {
        val night = BuiltinBackgrounds.byKey(BuiltinBackgrounds.NIGHT)
        assertNotNull(night)
        assertTrue(night!!.isDark)
        assertEquals(ThemeKey.DARK, night.suggestedTheme)
        for (preset in BuiltinBackgrounds.ALL.filter { it.isDark }) {
            assertEquals(ThemeKey.DARK, preset.suggestedTheme)
        }
    }

    @Test
    fun assetNamesLiveUnderTheBackgroundsDirectory() {
        assertTrue(BuiltinBackgrounds.assetNames().isNotEmpty())
        assertTrue(BuiltinBackgrounds.assetNames().all { it.startsWith("${BuiltinBackgrounds.ASSET_DIR}/") })
    }

    /** 素材必须真的存在，避免设置里出现点不动的空白预览。 */
    @Test
    fun bundledAssetFilesExistOnDisk() {
        val dir = File("src/main/assets")
        assumeTrue("不在模块目录下运行，跳过素材检查", dir.isDirectory)
        for (name in BuiltinBackgrounds.assetNames()) {
            val file = File(dir, name)
            assertTrue("缺少内置背景素材：$name", file.isFile)
            assertTrue("内置背景素材过小，可能损坏：$name", file.length() > 1024)
        }
    }
}
