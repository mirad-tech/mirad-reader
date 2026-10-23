package com.mirad.reader.ui.reader

import org.junit.Assert.*
import org.junit.Test

class BackdropCaptureControllerTest {
    private class Harness {
        var now = 0L
        val queue = linkedSetOf<Runnable>()
        val capturedAt = mutableListOf<Long>()
        val published = mutableListOf<Int>()
        val discarded = mutableListOf<Int>()
        var clears = 0
        var captureAction: () -> Int? = { capturedAt.size }
        var changed = true
        val controller = BackdropCaptureController(
            nowNanos = { now },
            schedule = { queue.add(it) },
            cancel = { queue.remove(it) },
            capture = { capturedAt += now; captureAction() },
            publish = { published += it; changed },
            discard = { discarded += it },
            clear = { clears++ }
        )
        fun tick(time: Long) {
            now = time
            val callbacks = queue.toList()
            queue.clear()
            callbacks.forEach { it.run() }
        }
    }

    @Test fun continuousInvalidationsDoNotStarveCaptureOrExceedThirtyFps() {
        val h = Harness()
        h.controller.start()
        for (frame in 0..60) {
            repeat(10) { h.controller.invalidate() }
            assertEquals(1, h.queue.size)
            h.tick(frame * 16_666_667L)
        }
        assertEquals(31, h.capturedAt.size)
        assertTrue(h.capturedAt.last() >= 1_000_000_000L)
        assertTrue(h.capturedAt.zipWithNext().all { (a, b) -> b - a >= BackdropCaptureController.FRAME_INTERVAL_NS })
    }

    @Test fun cleanViewHasNoCaptureLoop() {
        val h = Harness()
        h.controller.start()
        h.tick(0)
        h.tick(1_000_000_000L)
        assertEquals(1L, h.controller.attempts)
        assertTrue(h.queue.isEmpty())
    }

    @Test fun burstUpdatesCoalesceWithoutMovingExistingDeadline() {
        val h = Harness()
        h.controller.start()
        h.tick(0)
        repeat(50) { h.controller.invalidate() }
        h.tick(16_000_000)
        h.now = 33_000_000
        h.controller.invalidate()
        h.tick(BackdropCaptureController.FRAME_INTERVAL_NS)
        assertEquals(listOf(0L, BackdropCaptureController.FRAME_INTERVAL_NS), h.capturedAt)
        assertTrue(h.queue.isEmpty())
    }

    @Test fun failedCaptureRetriesThreeTimesThenWaitsForNewInvalidation() {
        val h = Harness()
        h.captureAction = { null }
        h.controller.start()
        for (attempt in 0..3) h.tick(attempt * 100_000_000L)
        assertEquals(4L, h.controller.attempts)
        assertEquals(1, h.clears)
        assertTrue(h.queue.isEmpty())
        h.captureAction = { 9 }
        h.controller.invalidate()
        h.tick(500_000_000)
        assertEquals(listOf(9), h.published)
    }

    @Test fun stoppedCaptureCannotPublishItsStaleResult() {
        val h = Harness()
        h.captureAction = { h.controller.stop(); 7 }
        h.controller.start()
        h.tick(0)
        assertEquals(listOf(7), h.discarded)
        assertTrue(h.published.isEmpty())
        assertTrue(h.queue.isEmpty())
    }

    @Test fun restartDiscardsOldGenerationAndKeepsNewRequest() {
        val h = Harness()
        h.captureAction = {
            h.controller.stop(clearFrame = false)
            h.controller.start()
            h.captureAction = { 2 }
            1
        }
        h.controller.start()
        h.tick(0)
        h.tick(16_666_667)
        assertEquals(listOf(1), h.discarded)
        assertEquals(listOf(2), h.published)
    }

    @Test fun hidingCancelsSamplingWithoutClearingTheExitFrame() {
        val h = Harness()
        h.controller.start()
        h.tick(0)
        h.controller.invalidate()
        h.controller.stop(clearFrame = false)
        h.controller.invalidate()
        h.tick(1_000_000_000)
        assertEquals(1L, h.controller.attempts)
        assertEquals(0, h.clears)
        assertTrue(h.queue.isEmpty())
        h.controller.stop()
        assertEquals(1, h.clears)
    }

    @Test fun identicalPixelsDoNotPublishOrScheduleAnotherFrame() {
        val h = Harness()
        h.changed = false
        h.controller.start()
        h.tick(0)
        assertEquals(1L, h.controller.attempts)
        assertEquals(0L, h.controller.publications)
        assertTrue(h.queue.isEmpty())
    }

    @Test fun invalidationDuringCaptureIsProcessedAsOneFollowingFrame() {
        val h = Harness()
        h.captureAction = {
            repeat(20) { h.controller.invalidate() }
            h.captureAction = { 2 }
            1
        }
        h.controller.start()
        h.tick(0)
        assertEquals(1, h.queue.size)
        h.tick(BackdropCaptureController.FRAME_INTERVAL_NS)
        assertEquals(listOf(1, 2), h.published)
        assertTrue(h.queue.isEmpty())
    }
}
