package com.mirad.reader.core.reader

import com.mirad.reader.core.model.EpubLayoutMode
import com.mirad.reader.core.model.FontFamilyKey
import com.mirad.reader.core.model.ReaderConfig
import com.mirad.reader.core.model.ReaderConfigJson
import com.mirad.reader.core.model.ThemeKey
import com.mirad.reader.core.model.ThemePaletteResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderCssTest {

    private val palette = ThemePaletteResolver.resolve(ReaderConfig.DEFAULT, systemDark = false)

    @Test
    fun unifiedLayoutForcesTypography() {
        val config = ReaderConfig.DEFAULT.copy(
            epubLayoutMode = EpubLayoutMode.UNIFIED,
            fontSizeSp = 21f,
            lineHeightMultiplier = 2.0f,
            paragraphSpacingEm = 0.8f,
            firstLineIndentEm = 2f
        )
        val css = ReaderCss.themeCss(config, palette)
        assertTrue(css.contains("font-size:21.0px"))
        assertTrue(css.contains("line-height:2.0 !important"))
        assertTrue(css.contains("text-indent:2.0em !important"))
        assertTrue(css.contains("margin:0 0 0.8em 0 !important"))
    }

    @Test
    fun originalLayoutDoesNotForceFontSize() {
        val config = ReaderConfig.DEFAULT.copy(epubLayoutMode = EpubLayoutMode.ORIGINAL)
        val css = ReaderCss.themeCss(config, palette)
        assertTrue(!css.contains("font-size:inherit !important"))
        assertTrue(css.contains("max-width:100% !important"))
    }

    @Test
    fun imagesCanBeDisabled() {
        val css = ReaderCss.themeCss(ReaderConfig.DEFAULT.copy(allowImages = false), palette)
        assertTrue(css.contains("img,svg,image{display:none !important;}"))
    }

    @Test
    fun imageBackgroundUsesUrlAndKeepsTextColor() {
        val config = ReaderConfig.DEFAULT.copy(
            backgroundType = com.mirad.reader.core.model.BackgroundType.IMAGE,
            backgroundImageUri = "content://x/y"
        )
        val css = ReaderCss.themeCss(config, palette, backgroundImageUrl = "https://appassets.androidplatform.net/bg/1.jpg")
        assertTrue(css.contains("background-image:url('https://appassets.androidplatform.net/bg/1.jpg')"))
        assertTrue(css.contains("color:"))
    }

    @Test
    fun customFontFamilyUsesFontFace() {
        val config = ReaderConfig.DEFAULT.copy(fontFamily = FontFamilyKey.CUSTOM)
        val css = ReaderCss.themeCss(config, palette, customFontUrl = "u.ttf")
        assertTrue(css.contains("MiradCustom"))
        assertTrue(ReaderCss.fontFaceCss("u.ttf").contains("@font-face"))
    }

    @Test
    fun colorIsConvertedToRgb() {
        assertEquals("rgb(31,111,92)", ReaderCss.argbToCss(0xFF1F6F5C.toInt()))
    }

    @Test
    fun pageMathMatchesColumnLayout() {
        assertEquals(5, ReaderCss.PageMath.pageCount(scrollWidth = 5 * 1000 + 4 * 20, viewportWidth = 1000, gap = 20))
        assertEquals(1, ReaderCss.PageMath.pageCount(scrollWidth = 900, viewportWidth = 1000, gap = 20))
        assertEquals(2040, ReaderCss.PageMath.offsetForPage(2, 1000, 20))
    }

    @Test
    fun configJsonRoundTripsAndToleratesUnknownKeys() {
        val config = ReaderConfig.DEFAULT.copy(theme = ThemeKey.GREEN, fontSizeSp = 23.5f, pageMode = "SCROLL")
        val encoded = ReaderConfigJson.encode(config)
        assertEquals(config, ReaderConfigJson.decode(encoded))

        val patched = encoded.dropLast(1) + ",\"futureField\":123}"
        assertEquals(config, ReaderConfigJson.decode(patched))
        assertEquals(ReaderConfig.DEFAULT, ReaderConfigJson.decode("not json"))
        assertEquals(ReaderConfig.DEFAULT, ReaderConfigJson.decode(null))
    }
}
