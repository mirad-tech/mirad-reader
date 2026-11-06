package com.mirad.reader.ui.common

import org.junit.Assert.*
import org.junit.Test

class LiquidGlassPolicyTest {
    @Test fun highClarityKeepsThinTintForEveryRoleAndBlurReachesZeroAtHundred() {
        for (dark in listOf(false, true)) {
            val clear = GlassAppearance(enabled = true, clarityPercent = 100)
            assertEquals(liquidPanelOpacity(100, dark), uniformGlassOpacity(clear, dark, true, GlassStyle.Panel), 0f)
            assertEquals(liquidBarOpacity(100, dark), uniformGlassOpacity(clear, dark, true), 0f)
            assertTrue(uniformGlassOpacity(clear, dark, true) < uniformGlassOpacity(clear.copy(clarityPercent = 80), dark, true))
            // 弹窗不再封顶到 80：100 档与工具栏一样走零模糊，用户已确认可以接受看不清文字。
            assertEquals(0f, liquidBlurDp(GlassStyle.Panel, 100), 0f)
            assertEquals(0f, liquidBlurDp(GlassStyle.Toolbar, 100), 0f)
            assertTrue(liquidBlurDp(GlassStyle.Panel, 80) > 0f)
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

    @Test fun panelCurveStaysMonotonicAndThinAtHighClarity() {
        assertEquals(1f, liquidPanelOpacity(0), .0001f)
        assertEquals(1f, liquidPanelOpacity(-3), .0001f)
        assertEquals(.08f, liquidPanelOpacity(100), .0001f)
        assertEquals(.10f, liquidPanelOpacity(100, isDark = true), .0001f)
        assertEquals(.08f, liquidPanelOpacity(105), .0001f)
        for (percent in 1..100) assertTrue(liquidPanelOpacity(percent) < liquidPanelOpacity(percent - 1))
        assertTrue(liquidPanelOpacity(60) - liquidPanelOpacity(100) > .1f)
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
        val base = liquidPanelOpacity(100)
        val strong = liquidProtectionOpacity(dark, paper, ink, base)
        val relaxed = liquidProtectionOpacity(clear, paper, ink, base)
        assertTrue(strong > relaxed)
        assertTrue(strong in .12f.. .98f)
        assertEquals(.12f, relaxed, .001f)
        assertEquals(.08f, base, .0001f)
    }

    @Test fun toolbarTintDependsOnlyOnClarityThemeAndAvailability() {
        val clear = GlassAppearance(enabled = true, clarityPercent = 100)
        assertEquals(.08f, uniformGlassOpacity(clear, false, true), .0001f)
        assertEquals(.10f, uniformGlassOpacity(clear, true, true), .0001f)
        assertEquals(.94f, uniformGlassOpacity(clear, false, false), .0001f)
        assertEquals(1f, uniformGlassOpacity(clear.copy(enabled = false), false, true), 0f)
        assertEquals(.31f, liquidPanelOpacity(50), .0001f)
    }

    @Test fun barsNeverTurnIntoAnOpaqueThemeSlabAtAnyClarity() {
        for (dark in listOf(false, true)) {
            for (clarity in 0..100) {
                val bar = liquidBarOpacity(clarity, dark)
                // 旧版五处调用点传入的整栏下限（日 .66／夜 .78）已被否定，任何档位都不能接近它。
                assertTrue("clarity=$clarity dark=$dark bar=$bar", bar <= liquidBarCeiling(dark) + .0001f)
                assertTrue("clarity=$clarity dark=$dark bar=$bar", bar < .66f)
                assertTrue("clarity=$clarity dark=$dark bar=$bar", bar < .78f)
            }
            // 0 档是最朦胧的一档：仍然可以看见背后的颜色与结构，而不是关闭状态。
            assertTrue(liquidBarOpacity(0, dark) >= .4f)
            assertTrue(liquidBarOpacity(0, true) > liquidBarOpacity(0, false))
            assertTrue(liquidBarOpacity(100, dark) < liquidBarOpacity(60, dark))
            assertTrue(liquidBarOpacity(60, dark) < liquidBarOpacity(0, dark))
            for (clarity in 1..100) {
                assertTrue(liquidBarOpacity(clarity, dark) < liquidBarOpacity(clarity - 1, dark))
            }
        }
        assertEquals(.08f, liquidBarOpacity(100), .0001f)
        assertEquals(.10f, liquidBarOpacity(100, isDark = true), .0001f)
    }

    @Test fun barTintStaysLockedToTheAcceptedSamples() {
        // 验收实测值：日 .1408／夜 .1672（默认 60），0 档 .46／.52，100 档 .08／.10。
        // 任何"再厚一点更保险"的改动都会在这里失败，必须重新走界面验收。
        assertEquals(.1408f, liquidBarOpacity(60), .0001f)
        assertEquals(.1672f, liquidBarOpacity(60, isDark = true), .0001f)
        assertEquals(LIQUID_BAR_CEILING_LIGHT, liquidBarOpacity(0), .0001f)
        assertEquals(LIQUID_BAR_CEILING_DARK, liquidBarOpacity(0, isDark = true), .0001f)
        assertEquals(liquidTintFloor(false), liquidBarOpacity(100), .0001f)
        assertEquals(liquidTintFloor(true), liquidBarOpacity(100, isDark = true), .0001f)
        // 面板曲线保持改造前的观感，避免这次修复顺手改掉已接受的弹窗效果。
        assertEquals(1f, liquidPanelOpacity(0), .0001f)
        assertEquals(.31f, liquidPanelOpacity(50), .0001f)
    }

    @Test fun barsStayThinnerThanPanelsButKeepPanelCurveUntouched() {
        for (dark in listOf(false, true)) {
            for (clarity in 0..99) {
                assertTrue(
                    "clarity=$clarity dark=$dark",
                    liquidBarOpacity(clarity, dark) < liquidPanelOpacity(clarity, dark)
                )
            }
            // 100 档只保留同一层薄底，两者都不该再被抬底色。
            assertEquals(liquidPanelOpacity(100, dark), liquidBarOpacity(100, dark), .0001f)
            assertEquals(
                liquidPanelOpacity(80, dark),
                liquidSurfaceOpacity(80, dark, GlassStyle.Panel),
                .0001f
            )
            assertEquals(
                liquidBarOpacity(80, dark),
                liquidSurfaceOpacity(80, dark, GlassStyle.Toolbar),
                .0001f
            )
        }
    }

    @Test fun glassSwitchAndFallbackKeepTheirOwnMeaningIndependentOfClarity() {
        val zero = GlassAppearance(enabled = true, clarityPercent = 0)
        // 清晰度 0 不是关闭：仍走玻璃底色，只是最朦胧的一档。
        assertEquals(liquidBarOpacity(0, false), uniformGlassOpacity(zero, false, true), .0001f)
        assertTrue(uniformGlassOpacity(zero, false, true) < 1f)
        // 关闭与 GPU 不可用仍分别是不透明稳定表面和 .94 降级底，语义不变。
        assertEquals(1f, uniformGlassOpacity(zero.copy(enabled = false), false, true), 0f)
        assertEquals(.94f, uniformGlassOpacity(zero, false, false), 0f)
        assertEquals(
            liquidBarOpacity(0, true),
            uniformGlassOpacity(zero, true, true, GlassStyle.Toolbar),
            .0001f
        )
    }

    @Test fun materialPaddingCoversOnlyTheBlurRadius() {
        assertEquals(0, liquidMaterialPaddingPx(liquidBlurDp(GlassStyle.Toolbar, 100)))
        assertEquals(0, liquidMaterialPaddingPx(liquidBlurDp(GlassStyle.Panel, 100)))
        assertEquals(40, liquidMaterialPaddingPx(20f))
        assertEquals(41, liquidMaterialPaddingPx(20.25f))
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
