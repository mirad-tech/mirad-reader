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
        val expected = listOf(3.2f, 2.85f, 2.5f, 2.15f, 1.8f)
        widths.zip(expected).forEach { (actual, wanted) -> assertEquals(wanted, actual, .001f) }
    }

    @Test
    fun shortPanelKeepsTheSameThinEdgeWithoutAThickBevel() {
        val widths = listOf(0, 25, 50, 75, 100).map {
            GlassEdgeProfile.bandWidthDp(it, shortSideDp = 48f)
        }
        val expected = listOf(3.2f, 2.85f, 2.5f, 2.15f, 1.8f)
        widths.zip(expected).forEach { (actual, wanted) -> assertEquals(wanted, actual, .001f) }
        assertTrue(widths.all { it <= 48f * .12f + .001f })
    }

    @Test
    fun clarityIsClampedAndInvalidShortSidesProduceNoStrokeWidth() {
        assertEquals(3.2f, GlassEdgeProfile.bandWidthDp(-50, 200f), .001f)
        assertEquals(1.8f, GlassEdgeProfile.bandWidthDp(150, 200f), .001f)
        assertEquals(0f, GlassEdgeProfile.bandWidthDp(50, 0f), .001f)
        assertEquals(0f, GlassEdgeProfile.bandWidthDp(50, -20f), .001f)
        assertEquals(0f, GlassEdgeProfile.bandWidthDp(50, Float.NaN), .001f)
    }

    @Test
    fun edgeRemainsThinAtEveryClarityAndCapsToSmallControls() {
        for (clarity in 0..100) {
            assertTrue(GlassEdgeProfile.bandWidthDp(clarity, 200f) <= 3.2f)
            assertTrue(GlassEdgeProfile.contourAlpha(clarity) <= .07f)
            assertTrue(GlassEdgeProfile.bandWidthDp(clarity, 10f) <= 1.2f)
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