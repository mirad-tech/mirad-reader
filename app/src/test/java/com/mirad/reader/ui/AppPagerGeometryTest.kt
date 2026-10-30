package com.mirad.reader.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AppPagerGeometryTest {
    @Test fun halfPagesRetainAContinuousOffsetInsteadOfJumpingToTheNearestTab() {
        assertEquals(1 to -.5f, navigationPagePosition(.5f))
        assertEquals(2 to -.5f, navigationPagePosition(1.5f))
        assertEquals(3 to -.5f, navigationPagePosition(2.5f))
        val (page, offset) = navigationPagePosition(1.25f)
        assertEquals(1.25f, page + offset, 0f)
    }

    @Test fun pushingPastEitherEndDoesNotCreateAnInvalidPageOrWrapAround() {
        assertEquals(0 to 0f, navigationPagePosition(-.5f))
        assertEquals(3 to 0f, navigationPagePosition(3.8f))
    }
}
