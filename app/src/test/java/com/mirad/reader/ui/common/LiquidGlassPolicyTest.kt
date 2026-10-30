package com.mirad.reader.ui.common

import org.junit.Assert.*
import org.junit.Test

class LiquidGlassPolicyTest {
    @Test fun highClarityPanelsCapOnlyBlurAtEightyWithoutThickeningTintOrChangingSavedValues() {
        for (dark in listOf(false, true)) {
            val clear = GlassAppearance(enabled = true, clarityPercent = 100)
            assertEquals(liquidBaseOpacity(100, dark), uniformGlassOpacity(clear, dark, true), 0f)
            assertTrue(uniformGlassOpacity(clear, dark, true) < uniformGlassOpacity(clear.copy(clarityPercent = 80), dark, true))
            assertEquals(liquidBlurDp(GlassStyle.Panel, 80), liquidBlurDp(GlassStyle.Panel, 100), 0f)
            assertTrue(liquidBlurDp(GlassStyle.Panel, 100) > 0f)
            assertEquals(0f, liquidBlurDp(GlassStyle.Toolbar, 100), 0f)
            assertEquals(100, clear.clarityPercent)
        }
    }

    @Test fun disabledPanelsKeepFourPointFiveContrastOverExtremeBackdrops() {
        val ink = com.mirad.reader.ui.theme.Ink
        for (dark in listOf(false, true)) {
            val surface = if (dark) ink.Night else ink.Paper
            val foregrounds = if (dark) listOf(ink.NightText, ink.NightTextMuted, ink.Green80)
                else listOf(ink.InkText, ink.InkTextMuted, ink.Green40)
            val opacity = uniformGlassOpacity(GlassAppearance(enabled = false, clarityPercent = 100), dark, true)
            for (backdrop in listOf(0f, 1f)) {
                val background = relativeLuminanceFromSrgb(
                    surface.red * opacity + backdrop * (1 - opacity),
                    surface.green * opacity + backdrop * (1 - opacity),
                    surface.blue * opacity + backdrop * (1 - opacity)
                )
                foregrounds.forEach { foreground ->
                    assertTrue(contrastRatio(relativeLuminanceFromSrgb(foreground.red, foreground.green, foreground.blue), background) >= 4.5f)
                }
            }
        }
    }

    @Test fun analysisReadbackAlwaysFits64PixelsAndPreservesAspectRatio() {
        assertNull(liquidAnalysisDimensions(0, 2000))
        assertNull(liquidAnalysisDimensions(1000, -1))
        assertEquals(29 to 64, liquidAnalysisDimensions(1080, 2400))
        assertEquals(64 to 29, liquidAnalysisDimensions(2400, 1080))
        assertEquals(1 to 64, liquidAnalysisDimensions(1, Int.MAX_VALUE))
        assertEquals(64 to 1, liquidAnalysisDimensions(Int.MAX_VALUE, 1))
        assertEquals(1 to 1, liquidAnalysisDimensions(1, 1))
    }

    @Test fun highTransparencyRemainsThinAndMonotonicallyDecreases() {
        assertEquals(1f, liquidBaseOpacity(0), .0001f)
        assertEquals(1f, liquidBaseOpacity(-3), .0001f)
        assertEquals(.08f, liquidBaseOpacity(100), .0001f)
        assertEquals(.10f, liquidBaseOpacity(100, isDark = true), .0001f)
        assertEquals(.08f, liquidBaseOpacity(105), .0001f)
        for (percent in 1..100) assertTrue(liquidBaseOpacity(percent) < liquidBaseOpacity(percent - 1))
        assertTrue(liquidBaseOpacity(60) - liquidBaseOpacity(100) > .1f)
    }

    @Test fun blurUsesTwoFixedTiersIndependentOfTransparencyAndMetrics() {
        assertEquals(10f, liquidBlurDp(GlassStyle.Toolbar), 0f)
        assertEquals(14f, liquidBlurDp(GlassStyle.Panel), 0f)
    }

    @Test fun pixelAnalysisRetainsExtremaWithoutKeepingTheBitmap() {
        val first = analyzeLiquidPixels(intArrayOf(0xffffffff.toInt(), 0xff000000.toInt(), 0xff808080.toInt()))!!
        assertEquals(0xff000000.toInt(), first.darkest)
        assertEquals(0xffffffff.toInt(), first.lightest)
        assertEquals(first, analyzeLiquidPixels(intArrayOf(0xffffffff.toInt(), 0xff000000.toInt(), 0xff808080.toInt())))
        assertNotEquals(first.fingerprint, analyzeLiquidPixels(intArrayOf(0xff000000.toInt(), 0xffffffff.toInt(), 0xff808080.toInt()))!!.fingerprint)
        assertNull(analyzeLiquidPixels(intArrayOf()))
    }

    @Test fun contrastProtectionChangesOnlyTheForegroundLayerStrength() {
        val paper = 0xfffcfbf7.toInt()
        val ink = listOf(0xff191d1b.toInt(), 0xff4c554f.toInt(), 0xff2f6b58.toInt())
        val dark = analyzeLiquidPixels(intArrayOf(0xff000000.toInt()))!!
        val clear = analyzeLiquidPixels(intArrayOf(paper))!!
        val base = liquidBaseOpacity(100)
        val strong = liquidProtectionOpacity(dark, paper, ink, base)
        val relaxed = liquidProtectionOpacity(clear, paper, ink, base)
        assertTrue(strong > relaxed)
        assertTrue(strong in .12f.. .98f)
        assertEquals(.12f, relaxed, .001f)
        assertEquals(.08f, base, .0001f)
    }

    @Test fun uniformTintDependsOnlyOnClarityThemeAndAvailability() {
        val clear = GlassAppearance(enabled = true, clarityPercent = 100)
        assertEquals(.08f, uniformGlassOpacity(clear, false, true), .0001f)
        assertEquals(.10f, uniformGlassOpacity(clear, true, true), .0001f)
        assertEquals(.94f, uniformGlassOpacity(clear, false, false), .0001f)
        assertEquals(1f, uniformGlassOpacity(clear.copy(enabled = false), false, true), 0f)
        assertEquals(.31f, liquidBaseOpacity(50), .0001f)
    }

    @Test fun zeroBlurKeepsEnoughPaddingForIndependentRefraction() {
        assertEquals(0f, liquidBlurDp(GlassStyle.Toolbar, 100), 0f)
        assertTrue(liquidBlurDp(GlassStyle.Panel, 100) > 0f)
        assertEquals(6, liquidMaterialPaddingPx(0f, true, 2f))
        assertEquals(0, liquidMaterialPaddingPx(0f, false, 2f))
        assertEquals(40, liquidMaterialPaddingPx(20f, true, 2f))
        assertEquals(5.76f, liquidRefractionRimDp(48f), .001f)
        assertEquals(6f, liquidRefractionRimDp(100f), .001f)
    }

    @Test fun nightProtectionAlsoAdaptsToBrightContentAndUnknownContentIsConservative() {
        val night = 0xff15181a.toInt()
        val text = listOf(0xffe3e6e2.toInt(), 0xffb7bfb9.toInt(), 0xff9bd3bb.toInt())
        val bright = analyzeLiquidPixels(intArrayOf(0xffffffff.toInt()))!!
        val dark = analyzeLiquidPixels(intArrayOf(night))!!
        assertTrue(liquidProtectionOpacity(bright, night, text, .02f) > liquidProtectionOpacity(dark, night, text, .02f))
        assertEquals(.9f, liquidProtectionOpacity(null, night, text, .02f), 0f)
    }

    @Test fun aBurstOfContentUpdatesHasOneInFlightSampleAndHalfSecondSpacing() {
        val gate = LiquidAnalysisGate()
        val first = gate.begin(1_000_000L)!!
        repeat(120) { gate.markDirty() }
        assertNull(gate.begin(2_000_000L))
        assertTrue(gate.finish(first))
        assertNull(gate.begin(500_999_999L))
        assertEquals(1L, gate.waitNanos(500_999_999L))
        val second = gate.begin(501_000_000L)!!
        assertTrue(gate.finish(second))
        assertFalse(gate.dirty)
        assertNull(gate.begin(1_001_000_000L))
    }

    @Test fun sceneChangesDiscardOldSamplesWithoutOverlappingOrResettingTheRateLimit() {
        val gate = LiquidAnalysisGate()
        val old = gate.begin(100L)!!
        gate.invalidate()
        assertNull(gate.begin(600_000_100L))
        assertFalse(gate.finish(old))
        assertTrue(gate.dirty)
        assertNull(gate.begin(400_000_100L))
        val current = gate.begin(600_000_100L)!!
        assertTrue(gate.finish(current))
        assertFalse(gate.inFlight)
        assertFalse(gate.dirty)
    }
}
