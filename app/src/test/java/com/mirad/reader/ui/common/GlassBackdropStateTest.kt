package com.mirad.reader.ui.common

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GlassBackdropStateTest {
    @Test fun sourceContentChangePropagatesAndStaticContentDoesNotSelfTrigger() = runTest {
        var virtualNanos = 1_000_000_000L
        var currentFingerprint = 101L
        val state = GlassBackdropState(
            nowNanos = { virtualNanos },
            syntheticSampleProvider = {
                SyntheticSample(width = 1080, height = 2400, fingerprint = currentFingerprint)
            }
        )
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        state.hostScope = scope
        state.enabled = true

        // 1. First real content change
        state.onSourceLayerRecorded(width = 1080, height = 2400, transparencyPercent = 60)
        advanceUntilIdle()
        assertEquals(1L, state.snapshotCount)
        assertEquals(1L, state.drawVersion)
        assertEquals(101L, state.lastSampleFingerprint)
        // R3: Readback dimensions bounded to 270x600 (<= 25% and <= 640px)
        assertEquals(270, state.lastReadbackWidth)
        assertEquals(600, state.lastReadbackHeight)

        // 2. When idle for 10 seconds with no source changes, zero additional samples or records occur
        virtualNanos += 10_000_000_000L
        advanceTimeBy(10_000)
        assertEquals(1L, state.sourceRecordCount)
        assertEquals(1L, state.snapshotCount)
        assertEquals(1L, state.drawVersion)

        // 3. Even if a pass re-records identical pixels (fingerprint = 101L), drawVersion must NOT increment
        state.onSourceLayerRecorded(width = 1080, height = 2400, transparencyPercent = 60)
        advanceUntilIdle()
        assertEquals(2L, state.snapshotCount)
        assertEquals(1L, state.drawVersion) // No state mutation when pixels are identical!

        // 4. When real content changes (fingerprint = 202L), consumer receives update
        virtualNanos += 200_000_000L
        currentFingerprint = 202L
        state.onSourceLayerRecorded(width = 1080, height = 2400, transparencyPercent = 60)
        advanceUntilIdle()
        assertEquals(3L, state.snapshotCount)
        assertEquals(2L, state.drawVersion)
        assertEquals(202L, state.lastSampleFingerprint)
    }

    /**
     * R1 behavioral test (3):
     * Updates arriving during the 100ms throttle cooldown (even when snapshotInFlight == false)
     * or while a snapshot is in flight are never dropped; the final update always runs after cooldown.
     */
    @Test fun trailingUpdateWithin100msThrottleWindowAlwaysCompletesAfterCooldown() = runTest {
        var virtualNanos = 1_000_000_000L
        var currentFingerprint = 1L
        val state = GlassBackdropState(
            nowNanos = { virtualNanos },
            syntheticSampleProvider = {
                SyntheticSample(width = 270, height = 600, fingerprint = currentFingerprint)
            }
        )
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        state.hostScope = scope
        state.enabled = true

        // Initial sample at t = 0ms (fingerprint = 1)
        state.onSourceLayerRecorded(1080, 2400, 60)
        advanceUntilIdle()
        assertEquals(1L, state.snapshotCount)
        assertEquals(1L, state.lastSampleFingerprint)

        // Burst of updates within cooldown at t = 20ms, 45ms, 70ms (snapshotInFlight is false!)
        virtualNanos += 20_000_000L
        currentFingerprint = 2L
        state.onSourceLayerRecorded(1080, 2400, 60)

        virtualNanos += 25_000_000L
        currentFingerprint = 3L
        state.onSourceLayerRecorded(1080, 2400, 60)

        virtualNanos += 25_000_000L
        currentFingerprint = 4L
        state.onSourceLayerRecorded(1080, 2400, 60)

        // Before 100ms cooldown expires, still only 1 snapshot has executed
        assertEquals(1L, state.snapshotCount)

        // Advance past remaining cooldown: the trailing task MUST fire and capture the latest state (fingerprint = 4)
        virtualNanos += 40_000_000L
        advanceTimeBy(100)
        advanceUntilIdle()
        assertEquals(2L, state.snapshotCount)
        assertEquals(4L, state.lastSampleFingerprint)
        assertEquals(2L, state.drawVersion)
    }

    /**
     * R4 behavioral test:
     * Switching from non-zero (60%/100%) to 0% cancels queued/in-flight work, invalidates generation,
     * and clears caches; switching back to non-zero resumes sampling and captures latest frame.
     */
    @Test fun switchingFromNonZeroToZeroCancelsTasksClearsCacheAndRecoversOnReopen() = runTest {
        var virtualNanos = 1_000_000_000L
        var currentFingerprint = 10L
        var simulateInFlightZeroSwitch = false
        lateinit var state: GlassBackdropState
        state = GlassBackdropState(
            nowNanos = { virtualNanos },
            syntheticSampleProvider = {
                if (simulateInFlightZeroSwitch) {
                    // Simulate user switching transparency to 0% while a snapshot is in flight
                    state.clearAndStop("switched_to_zero_in_flight")
                }
                SyntheticSample(width = 270, height = 600, fingerprint = currentFingerprint)
            }
        )
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        state.hostScope = scope
        state.enabled = true

        // 1. Establish cache at 60%
        state.onSourceLayerRecorded(1080, 2400, 60)
        advanceUntilIdle()
        assertEquals(1, state.activeSnapshotCount)
        assertEquals(10L, state.lastSampleFingerprint)

        // 2. Queue a trailing update in cooldown, then switch to 0% before cooldown finishes
        virtualNanos += 30_000_000L
        currentFingerprint = 20L
        state.onSourceLayerRecorded(1080, 2400, 60)
        state.notifySourceChanged(0) // Switch to 0%
        virtualNanos += 100_000_000L
        advanceTimeBy(150)
        advanceUntilIdle()

        // Queued trailing task must have been cancelled and cache cleared
        assertEquals(0, state.activeSnapshotCount)
        assertEquals(0L, state.lastSampleFingerprint)
        assertNull(state.sharedSnapshot)
        assertNull(state.analysisBitmap)
        assertEquals(1L, state.snapshotCount)

        // 3. In-flight invalidation when switching to 0%
        virtualNanos += 200_000_000L
        currentFingerprint = 30L
        simulateInFlightZeroSwitch = true
        state.onSourceLayerRecorded(1080, 2400, 100)
        advanceUntilIdle()
        // Stale in-flight result must be discarded!
        assertEquals(0, state.activeSnapshotCount)
        assertEquals(0L, state.lastSampleFingerprint)

        // 4. Switching back from 0% to 60% restores sampling and captures latest frame (fingerprint = 40)
        simulateInFlightZeroSwitch = false
        virtualNanos += 200_000_000L
        currentFingerprint = 40L
        state.notifySourceChanged(60, force = true)
        advanceUntilIdle()
        assertEquals(1, state.activeSnapshotCount)
        assertEquals(40L, state.lastSampleFingerprint)
    }

    @Test fun eventArrivingDuringInFlightSamplePublishesLatestWithoutDropping() = runTest {
        var virtualNanos = 1_000_000_000L
        var currentFingerprint = 1L
        var callCount = 0
        val firstSampleGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        lateinit var state: GlassBackdropState
        state = GlassBackdropState(
            nowNanos = { virtualNanos },
            syntheticSampleProvider = {
                callCount++
                if (callCount == 1) {
                    firstSampleGate.await()
                    SyntheticSample(width = 270, height = 600, fingerprint = 1L)
                } else {
                    SyntheticSample(width = 270, height = 600, fingerprint = currentFingerprint)
                }
            }
        )
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        state.hostScope = scope
        state.enabled = true

        // Start 1st sample, which suspends inside syntheticSampleProvider
        state.notifySourceChanged(60, false)
        testScheduler.runCurrent()
        assertEquals(1, callCount)
        assertEquals(1, state.inFlightSnapshotCount)

        // New frame arrives while 1st sample is truly suspended in-flight
        virtualNanos += 30_000_000L
        currentFingerprint = 2L
        state.notifySourceChanged(60, false)
        assertEquals("Max concurrent snapshots for a single source must never exceed 1", 1, state.maxObservedInFlightCount)

        // Resume 1st sample
        virtualNanos += 20_000_000L
        firstSampleGate.complete(Unit)
        testScheduler.runCurrent()
        assertEquals(1L, state.lastSampleFingerprint)

        // Advance past cooldown: trailing capture must execute and publish fingerprint 2L
        virtualNanos += 120_000_000L
        advanceTimeBy(150)
        advanceUntilIdle()

        assertEquals("Update arriving during suspended sample must get a trailing capture", 2, callCount)
        assertEquals("Latest fingerprint must be published without another external event", 2L, state.lastSampleFingerprint)
        assertEquals(1, state.maxObservedInFlightCount)
    }

    @Test fun staleGenerationFinallyDoesNotClearNewGenerationInFlightState() = runTest {
        var virtualNanos = 1_000_000_000L
        var callCount = 0
        val firstGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val secondGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val state = GlassBackdropState(
            nowNanos = { virtualNanos },
            syntheticSampleProvider = {
                callCount++
                if (callCount == 1) {
                    firstGate.await()
                    SyntheticSample(width = 270, height = 600, fingerprint = 11L)
                } else {
                    secondGate.await()
                    SyntheticSample(width = 270, height = 600, fingerprint = 22L)
                }
            }
        )
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        state.hostScope = scope
        state.enabled = true

        // 1. Start generation 0 capture (suspends on firstGate)
        state.notifySourceChanged(60, false)
        testScheduler.runCurrent()
        assertEquals(1, state.inFlightSnapshotCount)

        // 2. Switch to 0% (increments generation and cancels), then immediately re-enable 80% (starts generation 1 capture)
        state.notifySourceChanged(0, false)
        virtualNanos += 200_000_000L
        state.notifySourceChanged(80, true)
        testScheduler.runCurrent()
        assertEquals(2, callCount)
        assertEquals(1, state.inFlightSnapshotCount)

        // 3. Complete stale generation 0 capture while generation 1 capture is still suspended
        firstGate.complete(Unit)
        testScheduler.runCurrent()
        // Stale generation finally must NOT reset generation 1's inFlightSnapshotCount or publish 11L
        assertEquals(1, state.inFlightSnapshotCount)
        assertEquals(0L, state.lastSampleFingerprint)

        // 4. Complete generation 1 capture: must publish 22L cleanly
        secondGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(0, state.inFlightSnapshotCount)
        assertEquals(22L, state.lastSampleFingerprint)
    }

    @Test fun fullPixelFingerprintDetectsHorizontalVerticalCornerAndSinglePixelChanges() {
        val w = 128
        val h = 128
        val basePixels = IntArray(w * h) { 0xFFFFFFFF.toInt() }
        val baseFp = computeBitmapFingerprint(w, h) { x, y -> basePixels[y * w + x] }

        // 1. Identical copy must have identical fingerprint and pass pixel equality
        val copyPixels = basePixels.clone()
        assertEquals(baseFp, computeBitmapFingerprint(w, h) { x, y -> copyPixels[y * w + x] })
        assertTrue(
            areBoundedPixelsIdentical(
                w, h, { x, y -> basePixels[y * w + x] },
                w, h, { x, y -> copyPixels[y * w + x] }
            )
        )

        // 2. 8px horizontal stripe (y in 32 until 40) - Reviewer S2 counterexample
        val hStripePixels = basePixels.clone()
        for (y in 32 until 40) {
            for (x in 0 until w) {
                hStripePixels[y * w + x] = 0xFF000000.toInt()
            }
        }
        assertNotEquals(
            "Horizontal stripe change must alter fingerprint",
            baseFp,
            computeBitmapFingerprint(w, h) { x, y -> hStripePixels[y * w + x] }
        )
        assertFalse(
            areBoundedPixelsIdentical(
                w, h, { x, y -> basePixels[y * w + x] },
                w, h, { x, y -> hStripePixels[y * w + x] }
            )
        )

        // 3. 4px vertical stripe between old grid columns (x in 53 until 57)
        val vStripePixels = basePixels.clone()
        for (y in 0 until h) {
            for (x in 53 until 57) {
                vStripePixels[y * w + x] = 0xFF222222.toInt()
            }
        }
        assertNotEquals(
            "Vertical stripe change must alter fingerprint",
            baseFp,
            computeBitmapFingerprint(w, h) { x, y -> vStripePixels[y * w + x] }
        )
        assertFalse(
            areBoundedPixelsIdentical(
                w, h, { x, y -> basePixels[y * w + x] },
                w, h, { x, y -> vStripePixels[y * w + x] }
            )
        )

        // 4. Small corner patch (4x4 in bottom-right)
        val cornerPixels = basePixels.clone()
        for (y in 124 until 128) {
            for (x in 124 until 128) {
                cornerPixels[y * w + x] = 0xFF103050.toInt()
            }
        }
        assertNotEquals(
            "Corner patch change must alter fingerprint",
            baseFp,
            computeBitmapFingerprint(w, h) { x, y -> cornerPixels[y * w + x] }
        )
        assertFalse(
            areBoundedPixelsIdentical(
                w, h, { x, y -> basePixels[y * w + x] },
                w, h, { x, y -> cornerPixels[y * w + x] }
            )
        )

        // 5. Single character stroke / single pixel change at (73, 91)
        val singlePixels = basePixels.clone()
        singlePixels[91 * w + 73] = 0xFF191D1B.toInt()
        assertNotEquals(
            "Single pixel change must alter fingerprint",
            baseFp,
            computeBitmapFingerprint(w, h) { x, y -> singlePixels[y * w + x] }
        )
        assertFalse(
            areBoundedPixelsIdentical(
                w, h, { x, y -> basePixels[y * w + x] },
                w, h, { x, y -> singlePixels[y * w + x] }
            )
        )
    }

}
