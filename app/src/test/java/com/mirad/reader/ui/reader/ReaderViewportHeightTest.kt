package com.mirad.reader.ui.reader

import com.mirad.reader.ui.common.*

import org.junit.Assert.assertEquals
import org.junit.Test
import androidx.compose.ui.unit.dp

class GlassViewportHeightTest {
    @Test fun fullScreenKeepsTheTopLayoutWhenSystemBarsAreHiddenOrRevealed() {
        for (systemTop in listOf(0.dp, 24.dp, 36.dp)) {
            assertEquals(0, readerTopInsetDp(fullScreen = true, systemTop))
        }
    }

    @Test fun nonFullScreenUsesOneRoundedSafeTopForHeaderAndBody() {
        assertEquals(0, readerTopInsetDp(fullScreen = false, 0.dp))
        assertEquals(24, readerTopInsetDp(fullScreen = false, 24.dp))
        assertEquals(25, readerTopInsetDp(fullScreen = false, 24.2f.dp))
    }

    @Test fun keyboardResizeDoesNotChangeTheDocumentHeight() {
        val viewport = GlassViewportHeight()
        assertEquals(2400, viewport.measure(1080, 2400, false))
        assertEquals(2400, viewport.measure(1080, 1450, true))
        assertEquals(2400, viewport.measure(1080, 1600, true))
        assertEquals(2400, viewport.measure(1080, 2400, false))
    }

    @Test fun anEdgeToEdgeKeyboardDoesNotAddItsHeightToTheDocument() {
        val viewport = GlassViewportHeight()
        assertEquals(2400, viewport.measure(1080, 2400, false))
        assertEquals(2400, viewport.measure(1080, 2400, true))
    }

    @Test fun rotationAndARealResizeEstablishNewDocumentBounds() {
        val viewport = GlassViewportHeight()
        viewport.measure(1080, 2400, false)
        assertEquals(1080, viewport.measure(2400, 1080, true))
        assertEquals(1900, viewport.measure(1080, 1900, false))
        assertEquals(1900, viewport.measure(1080, 1250, true))
    }
}
