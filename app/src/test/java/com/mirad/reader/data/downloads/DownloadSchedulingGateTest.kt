package com.mirad.reader.data.downloads

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class DownloadSchedulingGateTest {
    @Test fun enqueueDuringFinishSchedulesANewRunAfterTheOldOneFinishes() {
        val pending = AtomicBoolean(false)
        val finishing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val scheduled = AtomicInteger()
        val gate = DownloadSchedulingGate { pending.get() }
        gate.started("old")
        val finish = Thread { gate.finished("old") { retry ->
            assertFalse(retry)
            finishing.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
        } }.apply { start() }
        assertTrue(finishing.await(5, TimeUnit.SECONDS))
        pending.set(true)
        val enqueue = Thread { gate.schedule { scheduled.incrementAndGet() } }.apply { start() }
        release.countDown()
        finish.join(5000)
        enqueue.join(5000)
        assertFalse(finish.isAlive)
        assertFalse(enqueue.isAlive)
        assertEquals(1, scheduled.get())
    }

    @Test fun pendingQueueIsRetriedAndCannotBeCancelledByAnOlderPause() {
        var pending = true
        var starts = 0
        var cancellations = 0
        val gate = DownloadSchedulingGate { pending }
        var token = ""
        gate.schedule { starts++; token = it }
        assertTrue(gate.finished(token))
        gate.cancelIfIdle { cancellations++ }
        gate.schedule { starts++ }
        assertEquals(1, starts)
        assertEquals(0, cancellations)
        pending = false
        gate.cancelIfIdle { cancellations++ }
        pending = true
        gate.schedule { starts++ }
        assertEquals(1, cancellations)
        assertEquals(2, starts)
    }

    @Test fun cancelledWorkerCallbackCannotClaimANewQueuedTask() {
        var pending = false
        var newStarts = 0
        val gate = DownloadSchedulingGate { pending }
        gate.started("old")
        gate.cancelIfIdle { }
        pending = true
        assertFalse(gate.stopped("old", mayRetry = true))
        var next = ""
        gate.schedule { next = it; newStarts++ }
        assertEquals(1, newStarts)
        assertFalse(gate.started("old"))
        assertTrue(gate.started(next))
        assertFalse(gate.finished("old"))
        assertTrue(gate.finished(next))
    }

    @Test fun stoppedJobReadsPendingInsideTheSchedulingHandoff() {
        var pending = true
        val gate = DownloadSchedulingGate { pending }
        gate.started("current")
        assertTrue(gate.stopped("current", mayRetry = true))
        pending = false
        assertFalse(gate.stopped("current", mayRetry = true))
        pending = true
        var scheduled = false
        gate.schedule { scheduled = true }
        assertTrue(scheduled)
    }
}
