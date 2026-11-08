package com.mirad.reader.ui.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SliderHapticTicksTest {
    @Test
    fun arrivalAtTheSameTickWorksInBothDirections() {
        val ticks = SliderHapticTicks(5f)
        ticks.start()
        assertFalse(ticks.update(14f, 0L))
        assertTrue(ticks.update(15f, 80L))
        assertFalse(ticks.update(16f, 160L))
        assertTrue(ticks.update(15f, 240L))
        assertFalse(ticks.update(14f, 320L))
    }

    @Test
    fun floatStepsRecognizeTheDisplayedTick() {
        val ticks = SliderHapticTicks(.02f)
        ticks.start()
        assertFalse(ticks.update(.078f, 0L))
        assertTrue(ticks.update(Math.nextDown(.08f), 100L))
        assertFalse(ticks.update(.081f, 200L))
    }

    @Test
    fun fastSweepsMergeCrossingsAndLimitRepeatedFeedback() {
        val ticks = SliderHapticTicks(.05f)
        ticks.start()
        assertFalse(ticks.update(.01f, 0L))
        assertTrue(ticks.update(.46f, 100L))
        assertFalse(ticks.update(.56f, 159L))
        assertTrue(ticks.update(.66f, 160L))
        assertFalse(ticks.update(.67f, 240L))
    }

    @Test
    fun cancellationAndNonDragUpdatesNeverCarryTheOldBaseline() {
        val ticks = SliderHapticTicks(5f)
        assertFalse(ticks.update(12f, 0L))
        ticks.start()
        assertFalse(ticks.update(14f, 80L))
        ticks.stop()
        assertFalse(ticks.update(25f, 160L))
        ticks.start()
        assertFalse(ticks.update(49f, 240L))
        assertTrue(ticks.update(50f, 320L))
    }
}
