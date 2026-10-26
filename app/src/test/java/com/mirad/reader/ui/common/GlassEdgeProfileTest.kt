package com.mirad.reader.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassEdgeProfileTest {

    @Test
    fun broadBandWidthTracksClarityWhenThePanelIsTallEnough() {
        val widths = listOf(0, 25, 50, 75, 100).map {
            GlassEdgeProfile.bandWidthDp(it, shortSideDp = 200f)
        }
        val expected = listOf(12f, 10.8f, 9.6f, 8.4f, 7.2f)
        widths.zip(expected).forEach { (actual, wanted) -> assertEquals(wanted, actual, .001f) }
    }

    @Test
    fun shortPanelCapsBroadBandAtTwentyPercentAndPreservesHighClaritySteps() {
        val widths = listOf(0, 25, 50, 75, 100).map {
            GlassEdgeProfile.bandWidthDp(it, shortSideDp = 48f)
        }
        val expected = listOf(9.6f, 9.6f, 9.6f, 8.4f, 7.2f)
        widths.zip(expected).forEach { (actual, wanted) -> assertEquals(wanted, actual, .001f) }
        assertTrue(widths.all { it <= 48f * .20f + .001f })
    }

    @Test
    fun clarityIsClampedAndInvalidShortSidesProduceNoStrokeWidth() {
        assertEquals(12f, GlassEdgeProfile.bandWidthDp(-50, 200f), .001f)
        assertEquals(7.2f, GlassEdgeProfile.bandWidthDp(150, 200f), .001f)
        assertEquals(0f, GlassEdgeProfile.bandWidthDp(50, 0f), .001f)
        assertEquals(0f, GlassEdgeProfile.bandWidthDp(50, -20f), .001f)
        assertEquals(0f, GlassEdgeProfile.bandWidthDp(50, Float.NaN), .001f)
    }

    @Test
    fun edgeUsesSixScatterBandsThenOneContourAndOneSpecularStroke() {
        assertEquals(6, GlassEdgeProfile.BROAD_BAND_COUNT)
        assertEquals(1, GlassEdgeProfile.CONTOUR_STROKE_COUNT)
        assertEquals(1, GlassEdgeProfile.SPECULAR_STROKE_COUNT)
        assertEquals(8, GlassEdgeProfile.TOTAL_STROKE_COUNT)
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
    fun thinContourAndSpecularWidthsNarrowAsClarityRises() {
        val contour = listOf(0, 25, 50, 75, 100).map {
            GlassEdgeProfile.contourWidthDp(it, shortSideDp = 48f)
        }
        val specular = listOf(0, 25, 50, 75, 100).map {
            GlassEdgeProfile.specularWidthDp(it, shortSideDp = 48f)
        }
        contour.zipWithNext().forEach { (before, after) -> assertTrue(after <= before) }
        specular.zipWithNext().forEach { (before, after) -> assertTrue(after <= before) }
    }
}