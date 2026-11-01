package com.mirad.reader.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassEdgeProfileTest {

    @Test
    fun bandWidthNarrowsWithClarityAndBecomesReadableAtDefault() {
        val widths = listOf(0, 25, 50, 75, 100).map {
            GlassEdgeProfile.bandWidthDp(it, shortSideDp = 200f)
        }
        val expected = listOf(3.4f, 3.025f, 2.65f, 2.275f, 1.9f)
        widths.zip(expected).forEach { (actual, wanted) -> assertEquals(wanted, actual, .001f) }
        // 默认 60 档必须仍然有可辨认的过渡带，而不是只剩一条描边。
        assertTrue(GlassEdgeProfile.bandWidthDp(60, 200f) >= 2.3f)
    }

    @Test
    fun thicknessBandIsWiderThanTheScatteringBandAndAlsoShrinksWithClarity() {
        for (clarity in listOf(0, 50, 100)) {
            val band = GlassEdgeProfile.bandWidthDp(clarity, 200f)
            val thickness = GlassEdgeProfile.thicknessWidthDp(clarity, 200f)
            assertTrue("clarity=$clarity", thickness > band)
            assertTrue("clarity=$clarity", thickness <= band * 1.75f)
        }
        for (clarity in 1..100) {
            assertTrue(
                GlassEdgeProfile.thicknessWidthDp(clarity, 200f) <=
                    GlassEdgeProfile.thicknessWidthDp(clarity - 1, 200f)
            )
        }
    }

    @Test
    fun smallControlsStayCappedByTheirShortSide() {
        // 小胶囊不能被折射带铺满：三条线的宽度都必须留在短边的固定比例内。
        for (shortSide in listOf(10f, 24f, 48f)) {
            for (clarity in listOf(0, 60, 100)) {
                assertTrue(GlassEdgeProfile.bandWidthDp(clarity, shortSide) <= shortSide * .12f + .001f)
                assertTrue(GlassEdgeProfile.thicknessWidthDp(clarity, shortSide) <= shortSide * .18f + .001f)
                assertTrue(GlassEdgeProfile.contourWidthDp(clarity, shortSide) <= shortSide * .05f + .001f)
                assertTrue(GlassEdgeProfile.specularWidthDp(clarity, shortSide) <= shortSide * .04f + .001f)
                assertTrue(GlassEdgeProfile.glowWidthDp(clarity, shortSide) <= shortSide * .07f + .001f)
            }
        }
        assertEquals(1.2f, GlassEdgeProfile.bandWidthDp(50, 10f), .001f)
        assertEquals(1.8f, GlassEdgeProfile.thicknessWidthDp(50, 10f), .001f)
    }

    @Test
    fun clarityIsClampedAndInvalidShortSidesProduceNoStrokeWidth() {
        assertEquals(3.4f, GlassEdgeProfile.bandWidthDp(-50, 200f), .001f)
        assertEquals(1.9f, GlassEdgeProfile.bandWidthDp(150, 200f), .001f)
        for (shortSide in listOf(0f, -20f, Float.NaN)) {
            assertEquals(0f, GlassEdgeProfile.bandWidthDp(50, shortSide), .001f)
            assertEquals(0f, GlassEdgeProfile.thicknessWidthDp(50, shortSide), .001f)
            assertEquals(0f, GlassEdgeProfile.contourWidthDp(50, shortSide), .001f)
            assertEquals(0f, GlassEdgeProfile.specularWidthDp(50, shortSide), .001f)
            assertEquals(0f, GlassEdgeProfile.glowWidthDp(50, shortSide), .001f)
            assertTrue(shortSide.isNaN() || shortSide <= 0f)
        }
    }

    @Test
    fun glowIsWiderThanTheHairlineAndNeverBecomesALoudRim() {
        for (clarity in listOf(0, 60, 100)) {
            assertTrue(GlassEdgeProfile.glowWidthDp(clarity, 200f) > GlassEdgeProfile.specularWidthDp(clarity, 200f))
            // 柔光只是亮线的辅助层，强度必须低于亮线，避免整圈白框。
            for (dark in listOf(false, true)) {
                assertTrue(
                    GlassEdgeProfile.glowIntensity(clarity, dark) <
                        GlassEdgeProfile.specularIntensity(clarity, dark)
                )
            }
        }
        for (clarity in 0..100) {
            assertTrue(GlassEdgeProfile.contourAlpha(clarity) <= .05f)
            assertTrue(GlassEdgeProfile.contourAlpha(clarity) >= 0f)
        }
    }

    @Test
    fun matteKeepsTheWideScatteringStrongerWhileClearGlassSharpensTheHighlight() {
        assertTrue(
            GlassEdgeProfile.scatteringIntensity(0, darkSurface = false) >
                GlassEdgeProfile.scatteringIntensity(100, darkSurface = false)
        )
        assertTrue(
            GlassEdgeProfile.specularIntensity(100, darkSurface = false) >
                GlassEdgeProfile.specularIntensity(0, darkSurface = false)
        )
        assertTrue(
            GlassEdgeProfile.contourAlpha(0) > GlassEdgeProfile.contourAlpha(100)
        )
    }

    @Test
    fun darkSurfacesCarryMoreEdgeContrastThanLightOnes() {
        for (clarity in listOf(0, 60, 100)) {
            assertTrue(
                GlassEdgeProfile.specularIntensity(clarity, darkSurface = true) >
                    GlassEdgeProfile.specularIntensity(clarity, darkSurface = false)
            )
            assertTrue(
                GlassEdgeProfile.scatteringIntensity(clarity, darkSurface = true) >
                    GlassEdgeProfile.scatteringIntensity(clarity, darkSurface = false)
            )
        }
    }

    @Test
    fun hairlineWidthsNarrowAsClarityRises() {
        val contour = listOf(0, 25, 50, 75, 100).map {
            GlassEdgeProfile.contourWidthDp(it, shortSideDp = 48f)
        }
        val specular = listOf(0, 25, 50, 75, 100).map {
            GlassEdgeProfile.specularWidthDp(it, shortSideDp = 48f)
        }
        contour.zipWithNext().forEach { (before, after) -> assertTrue(after <= before) }
        specular.zipWithNext().forEach { (before, after) -> assertTrue(after <= before) }
        val band = listOf(0, 25, 50, 75, 100).map {
            GlassEdgeProfile.bandWidthDp(it, shortSideDp = 48f)
        }
        band.zipWithNext().forEach { (before, after) -> assertTrue(after < before) }
    }

    @Test
    fun broadBandsStayOrderedFromTightToWide() {
        assertEquals(2, GlassEdgeProfile.BROAD_BAND_COUNT)
        assertTrue(GlassEdgeProfile.broadBandScale(0) < GlassEdgeProfile.broadBandScale(1))
        assertTrue(GlassEdgeProfile.broadBandScale(1) <= 1f)
    }
}
