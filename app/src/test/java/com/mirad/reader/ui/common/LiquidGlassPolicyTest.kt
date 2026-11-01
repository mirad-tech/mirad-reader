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

    @Test
    fun zeroBlurKeepsEnoughPaddingForIndependentRefraction() {
        assertEquals(0f, liquidBlurDp(GlassStyle.Toolbar, 100), 0f)
        assertEquals(0f, liquidBlurDp(GlassStyle.Panel, 100), 0f)
        assertEquals(11, liquidMaterialPaddingPx(0f, true, 2f))
        assertEquals(0, liquidMaterialPaddingPx(0f, false, 2f))
        // 模糊留白与折射位移相加：20px 模糊需要 40px，再加折射的 4.6dp×2 + 1px。
        assertEquals(51, liquidMaterialPaddingPx(20f, true, 2f))
        assertEquals(40, liquidMaterialPaddingPx(20f, false, 2f))
        // 采样留白必须覆盖最大位移，否则边缘会读到自己的空白 padding。
        assertTrue(liquidMaterialPaddingPx(0f, true, 3f) >= LIQUID_REFRACTION_STRENGTH_DP * 3f)
        // 真正要守的是"余量"：留白减掉模糊占用的 2 倍半径后仍要覆盖最大位移。
        // 清晰度约 87 以上半径会小于位移，旧版 max(2r, 位移+1) 正是在那里余量归零；
        // 只断言 ">= 相加值" 会因 ceil(x) >= x 恒成立而挡不住它。
        for (density in listOf(2f, 2.75f, 3.5f)) {
            for (clarity in listOf(0, 60, 85, 90, 95, 100)) {
                val blur = liquidBlurDp(GlassStyle.Toolbar, clarity) * density
                assertTrue(
                    "density=$density clarity=$clarity",
                    liquidMaterialPaddingPx(blur, true, density) - blur * 2f >=
                        LIQUID_REFRACTION_STRENGTH_DP * density - .001f
                )
            }
        }
        assertEquals(8.64f, liquidRefractionRimDp(48f), .001f)
        assertEquals(LIQUID_REFRACTION_RIM_DP, liquidRefractionRimDp(100f), .001f)
    }

    @Test
    fun refractionStrengthIsCappedByTheShortSideSoSmallControlsDoNotStretch() {
        // 大栏、大面板用满上限；小胶囊按短边收窄。
        assertEquals(LIQUID_REFRACTION_STRENGTH_DP, liquidRefractionStrengthDp(120f), .001f)
        assertEquals(4.32f, liquidRefractionStrengthDp(48f), .001f)
        assertEquals(1.8f, liquidRefractionStrengthDp(20f), .001f)
        assertEquals(0f, liquidRefractionStrengthDp(0f), .001f)
        // 影响带始终大于位移，位移才能在带内连续归零而不在边界被裁断。
        for (shortSide in listOf(16f, 32f, 48f, 80f, 240f)) {
            assertTrue(liquidRefractionRimDp(shortSide) > liquidRefractionStrengthDp(shortSide))
            assertTrue(liquidRefractionRimDp(shortSide) <= LIQUID_REFRACTION_RIM_DP)
            assertTrue(liquidRefractionStrengthDp(shortSide) <= LIQUID_REFRACTION_STRENGTH_DP)
        }
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
