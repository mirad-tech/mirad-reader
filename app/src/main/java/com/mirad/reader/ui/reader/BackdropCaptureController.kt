package com.mirad.reader.ui.reader

/** Event-driven sampling: invalidations never postpone an already scheduled frame. */
internal class BackdropCaptureController<Frame>(
    private val nowNanos: () -> Long,
    private val schedule: (Runnable) -> Unit,
    private val cancel: (Runnable) -> Unit,
    private val capture: () -> Frame?,
    private val publish: (Frame) -> Boolean,
    private val discard: (Frame) -> Unit,
    private val clear: () -> Unit
) {
    private var active = false
    private var pending = false
    private var scheduled = false
    private var inFlight = false
    private var generation = 0L
    private var deadline = 0L
    private var failures = 0
    var attempts = 0L
        private set
    var publications = 0L
        private set
    var captureFailures = 0L
        private set
    private val frame = Runnable { sample() }

    fun start() {
        if (active) return
        active = true
        generation++
        deadline = 0L
        invalidate()
    }

    fun invalidate() {
        if (!active) return
        pending = true
        failures = 0
        enqueue()
    }

    fun stop(clearFrame: Boolean = true) {
        active = false
        pending = false
        generation++
        cancel(frame)
        scheduled = false
        if (clearFrame) clear()
    }

    private fun enqueue() {
        if (active && pending && !scheduled && !inFlight) {
            scheduled = true
            schedule(frame)
        }
    }

    private fun sample() {
        scheduled = false
        if (!active || !pending || inFlight) return
        val started = nowNanos()
        if (started < deadline) {
            enqueue()
            return
        }
        val token = generation
        pending = false
        inFlight = true
        attempts++
        val result = try { capture() } catch (_: Exception) { null }
        inFlight = false
        if (!active || generation != token) {
            if (result != null) discard(result)
            enqueue()
            return
        }
        deadline = started + FRAME_INTERVAL_NS
        if (result != null) {
            failures = 0
            if (publish(result)) publications++
        } else {
            captureFailures++
            failures++
            if (failures <= MAX_RETRIES) {
                pending = true
                deadline = nowNanos() + RETRY_INTERVAL_NS
            } else clear()
        }
        enqueue()
    }

    companion object {
        const val FRAME_INTERVAL_NS = 33_333_334L
        const val RETRY_INTERVAL_NS = 100_000_000L
        const val MAX_RETRIES = 3
    }
}
