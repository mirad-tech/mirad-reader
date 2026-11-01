package com.mirad.reader.ui.reader

import org.junit.Assert.*
import org.junit.Test

class TocFastScrollbarTest {
    @Test fun fittingEmptyAndUnmeasuredListsHaveNoScrollbar() {
        assertNull(tocScrollbarGeometry(200f, 500, 500, 0, 32f))
        assertNull(tocScrollbarGeometry(200f, 500, 0, 0, 32f))
        assertNull(tocScrollbarGeometry(0f, 500, 5000, 0, 32f))
        assertNull(tocScrollbarGeometry(200f, 500, Int.MAX_VALUE, 0, 32f))
        assertNull(tocScrollbarGeometry(200f, 500, 5000, Int.MAX_VALUE, 32f))
    }

    @Test fun thumbRemainsVisibleAndReachesBothTrackEnds() {
        val start = tocScrollbarGeometry(200f, 500, 100000, 0, 32f)!!
        val end = tocScrollbarGeometry(200f, 500, 100000, 99500, 32f)!!
        assertEquals(32f, start.thumbSize, .001f)
        assertEquals(0f, start.thumbTop, .001f)
        assertEquals(200f, end.thumbTop + end.thumbSize, .001f)
        val tiny = tocScrollbarGeometry(10f, 100, 100000, 0, 32f)!!
        assertTrue(tiny.thumbSize > 0f && tiny.travel > 0f)
        for (offset in -100..100100 step 100) {
            val geometry = tocScrollbarGeometry(200f, 500, 100000, offset, 32f)!!
            assertTrue(geometry.thumbTop >= 0f)
            assertTrue(geometry.thumbTop + geometry.thumbSize <= 200.001f)
        }
    }

    @Test fun endpointsAndFilteredIndicesStayInsideTheCurrentList() {
        assertEquals(0 to 0, tocScrollTarget(-1f, 1000, 100000, 1000))
        assertEquals(999 to 0, tocScrollTarget(2f, 1000, 100000, 1000))
        assertEquals(495 to 0, tocScrollTarget(.5f, 1000, 100000, 1000))
        assertEquals(7 to 0, tocScrollTarget(.5f, 17, 1700, 300))
        assertEquals(16 to 0, tocScrollTarget(1f, 17, 1700, 300))
        assertEquals(0 to 0, tocScrollTarget(1f, 1, 100, 300))
    }

    @Test fun longDirectoryTargetsAdvanceMonotonicallyWithoutInvalidIndices() {
        var previous = 0.0
        for (step in 0..1000) {
            val (index, offset) = tocScrollTarget(step / 1000f, 5000, 5000000, 800)
            assertTrue(index in 0..4999 && offset >= 0)
            val position = index + offset / 1000.0
            assertTrue(position >= previous)
            previous = position
        }
    }
}
