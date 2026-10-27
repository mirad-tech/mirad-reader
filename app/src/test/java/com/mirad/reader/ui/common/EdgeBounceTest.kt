package com.mirad.reader.ui.common

import org.junit.Assert.*
import org.junit.Test

class EdgeBounceTest {
    @Test fun boundaryPullHasResistanceAndNeverExceedsTheLimit() {
        val state = EdgeBounceState()
        assertEquals(40f, state.pull(40f, 24f), 0f)
        assertEquals(10f, state.offsetPx, 0f)
        repeat(100) { state.pull(40f, 24f) }
        assertEquals(24f, state.offsetPx, 0f)
    }
    @Test fun reverseDragRestoresTheOffsetAndLeavesRemainingDeltaForScrolling() {
        val state = EdgeBounceState()
        state.pull(96f, 24f)
        assertEquals(-40f, state.unwind(-40f), 0f)
        assertEquals(14f, state.offsetPx, 0f)
        assertEquals(-56f, state.unwind(-100f), 0f)
        assertEquals(0f, state.offsetPx, 0f)
        assertEquals(0f, state.unwind(-50f), 0f)
    }
    @Test fun bottomBoundaryUsesTheSameResistanceAndRestoration() {
        val state = EdgeBounceState()
        state.pull(-1000f, 24f)
        assertEquals(-24f, state.offsetPx, 0f)
        assertEquals(96f, state.unwind(120f), 0f)
        assertEquals(0f, state.offsetPx, 0f)
    }
    @Test fun resetDiscardsBothAnActivePullAndAnAnimationPosition() {
        val state = EdgeBounceState()
        state.pull(40f, 24f)
        state.reset()
        assertEquals(0f, state.offsetPx, 0f)
        state.animate(-12f)
        state.reset()
        assertEquals(0f, state.offsetPx, 0f)
    }
    @Test fun invalidInputDoesNotCreateAnOffset() {
        val state = EdgeBounceState()
        assertEquals(0f, state.pull(Float.NaN, 24f), 0f)
        assertEquals(0f, state.pull(20f, 0f), 0f)
        assertEquals(0f, state.offsetPx, 0f)
    }
}
