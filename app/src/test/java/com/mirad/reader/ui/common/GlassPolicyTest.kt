package com.mirad.reader.ui.common

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GlassPolicyTest {
    @Test fun oldAndroidAndPowerSaveNeverRequestGpuBlur() {
        for (api in 26..30) assertFalse(glassBlurAllowed(api, false, false))
        assertTrue(glassBlurAllowed(31, false, false))
        assertFalse(glassBlurAllowed(37, true, false))
        assertFalse(glassBlurAllowed(37, false, true))
    }

    @Test fun zeroSizedDetachedViewsDoNotAllocateBitmaps() {
        assertNull(glassSnapshotDimensions(0, 2400))
        assertNull(glassSnapshotDimensions(1080, 0))
        assertNull(glassSnapshotDimensions(-1, 2400))
    }

    @Test fun phoneSnapshotRetainsAspectRatioAtQuarterScale() {
        assertEquals(270 to 600, glassSnapshotDimensions(1080, 2400))
        assertEquals(600 to 270, glassSnapshotDimensions(2400, 1080))
    }

    @Test fun largeAndExtremelyNarrowViewportsStayBounded() {
        assertEquals(640 to 320, glassSnapshotDimensions(8192, 4096))
        assertEquals(1 to 640, glassSnapshotDimensions(1, Int.MAX_VALUE))
        assertEquals(1 to 1, glassSnapshotDimensions(1, 1))
    }

    @Test fun glassAppearanceDefaultsAndParsing() {
        assertEquals(60, GlassAppearance.DEFAULT.transparencyPercent)
        assertEquals(0.6f, GlassAppearance.DEFAULT.transparencyFraction, 0.001f)

        assertEquals(60, GlassAppearance.parse(null).transparencyPercent)
        assertEquals(60, GlassAppearance.parse("").transparencyPercent)
        assertEquals(60, GlassAppearance.parse("not-a-number").transparencyPercent)

        assertEquals(0, GlassAppearance.parse("0").transparencyPercent)
        assertEquals(45, GlassAppearance.parse("45").transparencyPercent)
        assertEquals(100, GlassAppearance.parse("100").transparencyPercent)

        assertEquals(0, GlassAppearance.parse("-15").transparencyPercent)
        assertEquals(100, GlassAppearance.parse("250").transparencyPercent)
    }

    @Test fun zeroTransparencySkipsBlurAndYieldsOpaque() {
        val params = computeGlassParams(
            transparencyPercent = 0,
            style = GlassStyle.Toolbar,
            isDark = false,
            canBlur = true,
            metrics = BackdropMetrics.NEUTRAL
        )
        assertEquals(1f, params.opacity, 0.001f)
        assertEquals(0.dp, params.blurRadius)
    }

    @Test fun fallbackModeYieldsSafeOpacity() {
        val params = computeGlassParams(
            transparencyPercent = 80,
            style = GlassStyle.Toolbar,
            isDark = false,
            canBlur = false,
            metrics = BackdropMetrics.NEUTRAL
        )
        assertTrue(params.opacity >= 0.94f)
        assertEquals(0.dp, params.blurRadius)
    }

    @Test fun manualBaseAndStyleAndThemeProtections() {
        // On a safe light background (luminance = 0.90), 60% transparency needs no extra contrast boost:
        // Toolbar = 0.40, Panel = 0.50
        val toolbarLight = computeGlassParams(
            transparencyPercent = 60,
            style = GlassStyle.Toolbar,
            isDark = false,
            canBlur = true,
            metrics = BackdropMetrics(p10 = 0.90f, p50 = 0.90f, p90 = 0.90f, span = 0f, edgeIntensity = 0f)
        )
        assertEquals(0.40f, toolbarLight.opacity, 0.01f)

        val panelLight = computeGlassParams(
            transparencyPercent = 60,
            style = GlassStyle.Panel,
            isDark = false,
            canBlur = true,
            metrics = BackdropMetrics(p10 = 0.90f, p50 = 0.90f, p90 = 0.90f, span = 0f, edgeIntensity = 0f)
        )
        assertEquals(0.50f, panelLight.opacity, 0.01f)

        // On a safe dark background (luminance = 0.02), 60% transparency needs no extra contrast boost:
        // Toolbar = 0.48, Panel = 0.58
        val toolbarDark = computeGlassParams(
            transparencyPercent = 60,
            style = GlassStyle.Toolbar,
            isDark = true,
            canBlur = true,
            metrics = BackdropMetrics(p10 = 0.02f, p50 = 0.02f, p90 = 0.02f, span = 0f, edgeIntensity = 0f)
        )
        assertEquals(0.48f, toolbarDark.opacity, 0.01f)

        val panelDark = computeGlassParams(
            transparencyPercent = 60,
            style = GlassStyle.Panel,
            isDark = true,
            canBlur = true,
            metrics = BackdropMetrics(p10 = 0.02f, p50 = 0.02f, p90 = 0.02f, span = 0f, edgeIntensity = 0f)
        )
        assertEquals(0.58f, panelDark.opacity, 0.01f)
    }

    @Test fun contrastProtectionBoostsOpacityOnAdverseBackground() {
        val adverseDark = computeGlassParams(
            transparencyPercent = 80,
            style = GlassStyle.Toolbar,
            isDark = true,
            canBlur = true,
            metrics = BackdropMetrics(p10 = 0.1f, p50 = 0.5f, p90 = 0.95f, span = 0.85f, edgeIntensity = 0.5f)
        )
        val neutralDark = computeGlassParams(
            transparencyPercent = 80,
            style = GlassStyle.Toolbar,
            isDark = true,
            canBlur = true,
            metrics = BackdropMetrics.NEUTRAL
        )
        assertTrue(adverseDark.opacity > neutralDark.opacity)

        val adverseLight = computeGlassParams(
            transparencyPercent = 80,
            style = GlassStyle.Toolbar,
            isDark = false,
            canBlur = true,
            metrics = BackdropMetrics(p10 = 0.05f, p50 = 0.4f, p90 = 0.8f, span = 0.75f, edgeIntensity = 0.4f)
        )
        val neutralLight = computeGlassParams(
            transparencyPercent = 80,
            style = GlassStyle.Toolbar,
            isDark = false,
            canBlur = true,
            metrics = BackdropMetrics.NEUTRAL
        )
        assertTrue(adverseLight.opacity > neutralLight.opacity)
    }

    @Test fun dynamicBlurRadiusStaysWithin12To24Dp() {
        for (percent in listOf(10, 40, 60, 80, 100)) {
            for (style in listOf(GlassStyle.Toolbar, GlassStyle.Panel)) {
                for (isDark in listOf(false, true)) {
                    val params = computeGlassParams(
                        transparencyPercent = percent,
                        style = style,
                        isDark = isDark,
                        canBlur = true,
                        metrics = BackdropMetrics(p10 = 0.1f, p50 = 0.5f, p90 = 0.9f, span = 0.8f, edgeIntensity = 0.6f)
                    )
                    assertTrue(params.blurRadius.value in 12f..24f)
                }
            }
        }
    }

    @Test fun hysteresisPreventsMicroFlickerButNeverDelaysProtectionBoost() {
        // R2: When targetOpacity > currentOpacity (protection boost), hysteresis must NOT delay it
        assertFalse(shouldApplyHysteresis(0.50f, 0.51f, 16.dp, 16.4.dp))
        assertFalse(shouldApplyHysteresis(0.50f, 0.80f, 16.dp, 16.dp))
        // When releasing protection by a tiny micro-amount (< 0.015f and < 0.8dp), hysteresis holds steady
        assertTrue(shouldApplyHysteresis(0.51f, 0.50f, 16.4.dp, 16.dp))
        assertFalse(shouldApplyHysteresis(0.55f, 0.50f, 16.dp, 16.dp))
        assertFalse(shouldApplyHysteresis(0.50f, 0.50f, 16.dp, 18.dp))
    }

    private fun linear(s: Double): Double = if (s <= 0.04045) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
    private fun luminance(r: Double, g: Double, b: Double): Double = 0.2126 * linear(r) + 0.7152 * linear(g) + 0.0722 * linear(b)
    private fun contrast(a: Double, b: Double): Double = (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05)

    @Test fun contrastGuardMustMeetClaimedRatioOnFlatBlackBackdrop() {
        val params = computeGlassParams(100, GlassStyle.Toolbar, false, true, BackdropMetrics(0f, 0f, 0f, 0f, 0f))
        val a = params.opacity.toDouble()
        val plain = luminance(252 / 255.0 * a, 251 / 255.0 * a, 247 / 255.0 * a)
        val text = luminance(25 / 255.0, 29 / 255.0, 27 / 255.0)
        assertTrue(contrast(plain, text) >= 4.5)

        val muted = luminance(76 / 255.0, 85 / 255.0, 79 / 255.0)
        val linearBlendedPaper = luminance(252 / 255.0, 251 / 255.0, 247 / 255.0) * a
        val bestLinearWithHighlight = linearBlendedPaper * 0.86 + 0.14
        assertTrue(contrast(bestLinearWithHighlight, muted) >= 4.5)
    }

    /** R2 counterexample 1 from second review: dark mode on #AAAAAA gray backdrop (no double linearSrgb). */
    @Test fun darkGrayBackdropMustProtectActualThemeText() {
        val m = analyzeLocalBackdrop(8, 8, Rect(0f, 0f, 8f, 8f)) { _, _ -> 0xFFAAAAAA.toInt() }
        val p = computeGlassParams(100, GlassStyle.Toolbar, true, true, m)
        val a = p.opacity.toDouble()
        val gray = 170.0 / 255.0
        val fg = luminance(227 / 255.0, 230 / 255.0, 226 / 255.0)
        val muted = luminance(183 / 255.0, 191 / 255.0, 185 / 255.0)
        val container = luminance(21 / 255.0, 24 / 255.0, 26 / 255.0)
        val srgb = luminance(
            gray * (1.0 - a) + 21 / 255.0 * a,
            gray * (1.0 - a) + 24 / 255.0 * a,
            gray * (1.0 - a) + 26 / 255.0 * a
        )
        val linearComp = luminance(gray, gray, gray) * (1.0 - a) + container * a
        assertTrue("Primary text sRGB contrast >= 4.5", contrast(fg, srgb) >= 4.5)
        assertTrue("Primary text linear contrast >= 4.5", contrast(fg, linearComp) >= 4.5)
        assertTrue("Secondary text sRGB contrast >= 4.5", contrast(muted, srgb) >= 4.5)
        assertTrue("Secondary text linear contrast >= 4.5", contrast(muted, linearComp) >= 4.5)
    }

    /** R2 counterexample 2 from second review: light mode secondary text at bottom (no highlight) on black backdrop. */
    @Test fun lightSecondaryTextMustNotRequireMaximumHighlight() {
        val p = computeGlassParams(100, GlassStyle.Panel, false, true, BackdropMetrics(0f, 0f, 0f, 0f, 0f))
        val a = p.opacity.toDouble()
        val fg = luminance(76 / 255.0, 85 / 255.0, 79 / 255.0)
        val srgb = luminance(252 / 255.0 * a, 251 / 255.0 * a, 247 / 255.0 * a)
        val linearComp = luminance(252 / 255.0, 251 / 255.0, 247 / 255.0) * a
        assertTrue("Bottom no-highlight sRGB contrast >= 4.5", contrast(fg, srgb) >= 4.5)
        assertTrue("Bottom no-highlight linear contrast >= 4.5", contrast(fg, linearComp) >= 4.5)
    }

    /** R2 full matrix: light/dark x Toolbar/Panel x 0/30/60/80/100% x 5 backdrops x primary/secondary x top/bottom. */
    @Test fun contrastMatrixMeets45AcrossThemesStylesTransparenciesBackdropsAndPositions() {
        val backdrops: List<Pair<String, BackdropMetrics?>> = listOf(
            "pure_black" to analyzeLocalBackdrop(8, 8, Rect(0f, 0f, 8f, 8f)) { _, _ -> 0xFF000000.toInt() },
            "pure_white" to analyzeLocalBackdrop(8, 8, Rect(0f, 0f, 8f, 8f)) { _, _ -> 0xFFFFFFFF.toInt() },
            "gray_aaaaaa" to analyzeLocalBackdrop(8, 8, Rect(0f, 0f, 8f, 8f)) { _, _ -> 0xFFAAAAAA.toInt() },
            "checkerboard" to analyzeLocalBackdrop(8, 8, Rect(0f, 0f, 8f, 8f)) { x, y ->
                if ((x + y) % 2 == 0) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            },
            "fine_edge" to analyzeLocalBackdrop(8, 8, Rect(0f, 0f, 8f, 8f)) { x, _ ->
                if (x == 3) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
            },
            "unsampled_null" to null,
            "neutral" to BackdropMetrics.NEUTRAL
        )

        for (isDark in listOf(false, true)) {
            val surfR = if (isDark) 21.0 / 255.0 else 252.0 / 255.0
            val surfG = if (isDark) 24.0 / 255.0 else 251.0 / 255.0
            val surfB = if (isDark) 26.0 / 255.0 else 247.0 / 255.0
            val surfLum = luminance(surfR, surfG, surfB)
            val primaryLum = if (isDark) luminance(227 / 255.0, 230 / 255.0, 226 / 255.0) else luminance(25 / 255.0, 29 / 255.0, 27 / 255.0)
            val secondaryLum = if (isDark) luminance(183 / 255.0, 191 / 255.0, 185 / 255.0) else luminance(76 / 255.0, 85 / 255.0, 79 / 255.0)

            for (style in listOf(GlassStyle.Toolbar, GlassStyle.Panel)) {
                for (percent in listOf(0, 30, 60, 80, 100)) {
                    for ((bgName, metrics) in backdrops) {
                        val params = computeGlassParams(percent, style, isDark, true, metrics)
                        val a = params.opacity.toDouble()
                        val adverseBgLum = when {
                            metrics == null -> if (isDark) 0.85 else 0.15
                            isDark -> Math.max(metrics.p90.toDouble(), metrics.maxLuminance.toDouble())
                            else -> Math.min(metrics.p10.toDouble(), metrics.minLuminance.toDouble())
                        }
                        val bgSrgb = srgbFromLinear(adverseBgLum.toFloat()).toDouble()
                        val highlights = if (percent == 0) listOf(0.0) else listOf(0.0, params.highlightAlpha.toDouble())

                        for (h in highlights) {
                            val r1 = surfR * a + bgSrgb * (1.0 - a)
                            val g1 = surfG * a + bgSrgb * (1.0 - a)
                            val b1 = surfB * a + bgSrgb * (1.0 - a)
                            val r2 = 1.0 * h + r1 * (1.0 - h)
                            val g2 = 1.0 * h + g1 * (1.0 - h)
                            val b2 = 1.0 * h + b1 * (1.0 - h)
                            val compSrgbLum = luminance(r2, g2, b2)
                            val compLinLum = 1.0 * h + (surfLum * a + adverseBgLum * (1.0 - a)) * (1.0 - h)

                            val caseDesc = "dark=$isDark style=$style pct=$percent bg=$bgName h=$h a=$a"
                            assertTrue("Primary sRGB failed: $caseDesc", contrast(primaryLum, compSrgbLum) >= 4.5)
                            assertTrue("Primary linear failed: $caseDesc", contrast(primaryLum, compLinLum) >= 4.5)
                            assertTrue("Secondary sRGB failed: $caseDesc", contrast(secondaryLum, compSrgbLum) >= 4.5)
                            assertTrue("Secondary linear failed: $caseDesc", contrast(secondaryLum, compLinLum) >= 4.5)
                        }
                    }
                }
            }
        }
    }

    /** F5: spatial edge metric distinguishes checkerboard from two solid blocks of identical quantiles. */
    @Test fun edgeMetricDistinguishesCheckerboardFromLargeBlocks() {
        val blockMetrics = analyzeLocalBackdrop(8, 8, Rect(0f, 0f, 8f, 8f)) { x, _ ->
            if (x < 4) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val checkerMetrics = analyzeLocalBackdrop(8, 8, Rect(0f, 0f, 8f, 8f)) { x, y ->
            if ((x + y) % 2 == 0) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        assertEquals(blockMetrics.p10, checkerMetrics.p10, 0.001f)
        assertEquals(blockMetrics.p90, checkerMetrics.p90, 0.001f)
        assertTrue(checkerMetrics.edgeIntensity > blockMetrics.edgeIntensity + 0.4f)
    }

    /**
     * R1 behavioral test (1) & (2):
     * - Real content changes propagate to consumers (snapshotCount and drawVersion advance).
     * - Static/unchanged content does not trigger repeated state updates or endless loops.
     */
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

    @Test fun saturatedBlueAndChromaticBackdropsProtectActualSecondaryTextInSrgbAndLinearModels() {
        val chromaticColors = listOf(
            "pureBlue" to 0xFF0000FF.toInt(),
            "pureRed" to 0xFFFF0000.toInt(),
            "pureGreen" to 0xFF00FF00.toInt(),
            "cyan" to 0xFF00FFFF.toInt(),
            "magenta" to 0xFFFF00FF.toInt(),
            "yellow" to 0xFFFFFF00.toInt(),
            "midGrayAA" to 0xFFAAAAAA.toInt(),
            "darkNavy" to 0xFF001B44.toInt()
        )
        for ((name, colorInt) in chromaticColors) {
            val m = analyzeLocalBackdrop(8, 8, Rect(0f, 0f, 8f, 8f)) { _, _ -> colorInt }
            val bgR = ((colorInt ushr 16) and 0xFF) / 255f
            val bgG = ((colorInt ushr 8) and 0xFF) / 255f
            val bgB = (colorInt and 0xFF) / 255f

            for (isDark in listOf(false, true)) {
                for (style in listOf(GlassStyle.Toolbar, GlassStyle.Panel)) {
                    for (percent in listOf(0, 30, 60, 80, 100)) {
                        val p = computeGlassParams(percent, style, isDark = isDark, canBlur = true, metrics = m)
                        assertTrue(
                            "Chromatic contrast failed for color=$name dark=$isDark style=$style pct=$percent opacity=${p.opacity}",
                            meetsThemeContrastForRgb(
                                isDark = isDark,
                                alpha = p.opacity,
                                topHighlightAlpha = if (percent == 0) 0f else p.highlightAlpha,
                                bgR = bgR,
                                bgG = bgG,
                                bgB = bgB
                            )
                        )
                    }
                }
            }
        }
    }

    @Test fun t1HundredPercentTransparencyIsSignificantlyLighterThanSixtyPercentwhilePreservingContrast() {
        // Simulate realistic bounded backdrop (160x64) of Reader paper with dark text lines (~6% ink pixels)
        val w = 160
        val h = 64
        val readerPixels = IntArray(w * h) { idx ->
            val x = idx % w
            val y = idx / w
            if (y % 12 == 5 && x in 16..144 && x % 3 != 0) 0xFF191D1B.toInt() else 0xFFFBF8F1.toInt()
        }
        val metrics = analyzeLocalBackdrop(w, h, Rect(0f, 0f, w.toFloat(), h.toFloat())) { x, y ->
            readerPixels[y * w + x]
        }

        for (style in listOf(GlassStyle.Toolbar, GlassStyle.Panel)) {
            val p60 = computeGlassParams(60, style, isDark = false, canBlur = true, metrics = metrics)
            val p100 = computeGlassParams(100, style, isDark = false, canBlur = true, metrics = metrics)

            // 100% ambient opacity must be significantly more transparent than 60% (at least 0.14 lower alpha)
            assertTrue(
                "T1: 100% ambientOpacity (${p100.ambientOpacity}) must be >= 0.14 lower than 60% (${p60.ambientOpacity}) for $style",
                p60.ambientOpacity - p100.ambientOpacity >= 0.14f
            )
            // 100% ambient opacity on light reader paper must be visibly sheer (<= 0.42), far below old ~0.82
            assertTrue(
                "T1: 100% ambientOpacity (${p100.ambientOpacity}) must be <= 0.42 for $style",
                p100.ambientOpacity <= 0.42f
            )
            // Contrast on ambient backdrop must still be >= 4.5:1
            assertTrue(
                "T1: 100% ambientOpacity must maintain >= 4.5:1 contrast on ambient backdrop",
                meetsThemeContrastForRgb(
                    isDark = false,
                    alpha = p100.ambientOpacity,
                    topHighlightAlpha = p100.highlightAlpha,
                    bgR = metrics.ambientLightModeR,
                    bgG = metrics.ambientLightModeG,
                    bgB = metrics.ambientLightModeB
                )
            )
            // Peak opacity must also protect the worst-case sample at >= 4.5:1
            assertTrue(
                "T1: 100% peak opacity must maintain >= 4.5:1 contrast on worst-case sample",
                meetsThemeContrastForRgb(
                    isDark = false,
                    alpha = p100.opacity,
                    topHighlightAlpha = p100.highlightAlpha,
                    bgR = metrics.adverseLightR,
                    bgG = metrics.adverseLightG,
                    bgB = metrics.adverseLightB
                )
            )
        }
    }

    @Test fun t11MixedBackdropContinuousProtectionMeets45ContrastAcrossBlockInteriorBoundariesAndShiftedPositions() {
        val bw = 247
        val bh = 247

        data class MixedCase(
            val name: String,
            val isDark: Boolean,
            val baseColor: Int,
            val blockColor: Int,
            val startX: Float,
            val startY: Float,
            val widthFrac: Float,
            val heightFrac: Float
        )

        val cases = listOf(
            MixedCase("review_white_blue_block", false, 0xFFFFFFFF.toInt(), 0xFF0000FF.toInt(), 2f / 8f, 2f / 8f, 3f / 8f, 3f / 8f),
            MixedCase("shifted_cross_cell_blue", false, 0xFFFFFFFF.toInt(), 0xFF0000FF.toInt(), 0.19f, 0.21f, 0.375f, 0.375f),
            MixedCase("shifted_corner_red", false, 0xFFFFFFFF.toInt(), 0xFFFF0000.toInt(), 0.31f, 0.33f, 0.35f, 0.35f),
            MixedCase("dark_mode_gray_block", true, 0xFF15181A.toInt(), 0xFFAAAAAA.toInt(), 0.22f, 0.24f, 0.38f, 0.38f)
        )

        for (c in cases) {
            val x0 = (bw * c.startX).toInt()
            val y0 = (bh * c.startY).toInt()
            val x1 = (bw * (c.startX + c.widthFrac)).toInt()
            val y1 = (bh * (c.startY + c.heightFrac)).toInt()

            val metrics = analyzeLocalBackdrop(bw, bh, Rect(0f, 0f, bw.toFloat(), bh.toFloat())) { x, y ->
                if (x in x0 until x1 && y in y0 until y1) c.blockColor else c.baseColor
            }
            val params = computeGlassParams(100, GlassStyle.Panel, isDark = c.isDark, canBlur = true, metrics = metrics)
            assertEquals("Ambient opacity on mild base must remain 0.04 for ${c.name}", 0.04f, params.ambientOpacity, 0.01f)

            val sourceCells = params.rawCellOpacities ?: params.cellProtectedOpacities!!
            val field = buildContinuousProtectionField(
                rawCellOpacities = sourceCells,
                cols = params.cellCols,
                rows = params.cellRows,
                ambientOpacity = params.ambientOpacity
            )

            val surfR = if (c.isDark) 21 else 252
            val surfG = if (c.isDark) 24 else 251
            val surfB = if (c.isDark) 26 else 247
            val bgR = (c.blockColor ushr 16) and 0xFF
            val bgG = (c.blockColor ushr 8) and 0xFF
            val bgB = c.blockColor and 0xFF
            val secondaryTextLum = if (c.isDark) {
                luminance(183 / 255.0, 191 / 255.0, 185 / 255.0)
            } else {
                luminance(76 / 255.0, 85 / 255.0, 79 / 255.0)
            }

            // Verify every field point inside the adverse block (including center, off-center, cell boundaries, and bottom no-highlight)
            val fxStart = kotlin.math.ceil(c.startX * PROTECTION_FIELD_SIZE).toInt()
            val fyStart = kotlin.math.ceil(c.startY * PROTECTION_FIELD_SIZE).toInt()
            val fxEnd = ((c.startX + c.widthFrac) * PROTECTION_FIELD_SIZE).toInt()
            val fyEnd = ((c.startY + c.heightFrac) * PROTECTION_FIELD_SIZE).toInt()

            for (fy in fyStart until fyEnd) {
                for (fx in fxStart until fxEnd) {
                    val alphaF = field[fy * PROTECTION_FIELD_SIZE + fx]
                    val aInt = kotlin.math.ceil(alphaF * 255f).toInt().coerceIn(0, 255)
                    // Simulate 8-bit integer compositing without highlight (worst-case for light mode on dark/blue block)
                    val compR = (surfR * aInt + bgR * (255 - aInt) + 127) / 255
                    val compG = (surfG * aInt + bgG * (255 - aInt) + 127) / 255
                    val compB = (surfB * aInt + bgB * (255 - aInt) + 127) / 255
                    val compLum = luminance(compR / 255.0, compG / 255.0, compB / 255.0)
                    val ratio = contrast(compLum, secondaryTextLum)
                    assertTrue(
                        "T1.1 failed at field($fx,$fy) in ${c.name}: alpha=$alphaF rgb=($compR,$compG,$compB) ratio=$ratio < 4.5",
                        ratio >= 4.5
                    )
                }
            }
        }
    }

    @Test fun t12ContinuousProtectionHasFlatPlateauNoGridOscillationAndCleanReaderTextUniformity() {
        val bw = 247
        val bh = 247
        val x0 = (bw * 2f / 8f).toInt()
        val y0 = (bh * 2f / 8f).toInt()
        val x1 = (bw * 5f / 8f).toInt()
        val y1 = (bh * 5f / 8f).toInt()

        val mixedMetrics = analyzeLocalBackdrop(bw, bh, Rect(0f, 0f, bw.toFloat(), bh.toFloat())) { x, y ->
            if (x in x0 until x1 && y in y0 until y1) 0xFF0000FF.toInt() else 0xFFFFFFFF.toInt()
        }
        val mixedParams = computeGlassParams(100, GlassStyle.Panel, isDark = false, canBlur = true, metrics = mixedMetrics)
        val field = buildContinuousProtectionField(
            rawCellOpacities = mixedParams.rawCellOpacities!!,
            cols = mixedParams.cellCols,
            rows = mixedParams.cellRows,
            ambientOpacity = mixedParams.ambientOpacity
        )

        // 1. Inside the 3x3 blue block [16..40) x [16..40) in 64x64 field, alpha must be a completely flat plateau (no radial dip or grid stripes)
        val centerAlpha = field[28 * PROTECTION_FIELD_SIZE + 28]
        for (fy in 16 until 40) {
            for (fx in 16 until 40) {
                assertEquals(
                    "Block interior must be a flat plateau without radial attenuation or grid seams at ($fx,$fy)",
                    centerAlpha,
                    field[fy * PROTECTION_FIELD_SIZE + fx],
                    0.002f
                )
            }
        }

        // 2. Dark reader page with multi-line light Chinese text strokes (~2px stroke width in sampled bitmap) must NOT trigger grid bands
        val rw = 270
        val rh = 300
        val darkReaderMetrics = analyzeLocalBackdrop(rw, rh, Rect(0f, 0f, rw.toFloat(), rh.toFloat())) { x, y ->
            val inMargin = x < 20 || x > 250
            val lineY = y % 28
            val inTextLine = lineY in 6..20
            val isStroke = !inMargin && inTextLine && ((x + y) % 4 == 0 || (x * 3 + y) % 7 == 0)
            if (isStroke) 0xFFB9C0BA.toInt() else 0xFF121417.toInt()
        }
        val darkReaderParams = computeGlassParams(100, GlassStyle.Panel, isDark = true, canBlur = true, metrics = darkReaderMetrics)
        val darkRawCells = darkReaderParams.rawCellOpacities!!
        val darkCellSpan = (darkRawCells.maxOrNull() ?: 0f) - (darkRawCells.minOrNull() ?: 0f)
        assertTrue(
            "T1.2: Dark reader text backdrop must not trigger per-cell grid stripes (span=$darkCellSpan)",
            darkCellSpan <= 0.015f
        )
        assertTrue(
            "T1: Dark reader 100% ambientOpacity (${darkReaderParams.ambientOpacity}) must stay sheer (<= 0.12)",
            darkReaderParams.ambientOpacity <= 0.12f
        )
    }
}
