package com.castpro.core.streaming.lifecycle

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

class PipelineReplacementGateTest {
    @Test fun stopDuringPreparationReleasesReplacementAndNeverInstallsIt() {
        val pipelineLock = Any()
        val gate = PipelineReplacementGate(pipelineLock)
        val generation = AtomicInteger(7)
        val started = AtomicBoolean(true)
        val stopping = AtomicBoolean(false)
        val prepared = CountDownLatch(1)
        val allowCommit = CountDownLatch(1)
        val installed = AtomicBoolean(false)
        val released = AtomicInteger(0)
        val replacement = Any()

        val worker = Thread {
            // Simulate expensive encoder preparation occurring outside the
            // pipeline lock, then race with stop before attempting installation.
            prepared.countDown()
            check(allowCommit.await(2, TimeUnit.SECONDS))
            gate.installOrRelease(
                expectedGeneration = 7,
                currentGeneration = { generation.get() },
                isStarted = { started.get() },
                isStopping = { stopping.get() },
                replacement = replacement,
                release = { released.incrementAndGet() },
                install = { installed.set(true) }
            )
        }
        worker.start()

        assertTrue(prepared.await(2, TimeUnit.SECONDS))
        // stopStreaming invalidates the session before waiting for pipelineLock.
        stopping.set(true)
        generation.incrementAndGet()
        allowCommit.countDown()
        worker.join(2_000)

        assertFalse("replacement must never enter the active pipeline", installed.get())
        assertEquals("prepared encoder must be released exactly once", 1, released.get())
        assertFalse("worker must finish", worker.isAlive)
    }

    @Test fun replacementCommitAndTeardownShareTheSamePipelineLock() {
        val lock = Any()
        val gate = PipelineReplacementGate(lock)
        val order = mutableListOf<String>()
        gate.withPipelineLock {
            order += "teardown-enter"
            order += "teardown-exit"
        }
        assertEquals(listOf("teardown-enter", "teardown-exit"), order)
    }
    @Test fun blockingShutdownOutsidePipelineLockDoesNotBlockStaleReplacementRejection() {
        val pipelineLock = Any()
        val gate = PipelineReplacementGate(pipelineLock)
        val generation = AtomicInteger(11)
        val started = AtomicBoolean(true)
        val stopping = AtomicBoolean(false)
        val shutdownEntered = CountDownLatch(1)
        val allowShutdownToFinish = CountDownLatch(1)
        val replacementFinished = CountDownLatch(1)
        val installed = AtomicBoolean(false)
        val released = AtomicInteger(0)

        // Match stopStreaming's ordering: invalidate the session, then detach
        // resources under the pipeline lock.
        stopping.set(true)
        generation.incrementAndGet()
        gate.withPipelineLock { /* resource references detached here */ }

        // Simulate a blocking socket/server/codec shutdown after lock release.
        val shutdown = Thread {
            shutdownEntered.countDown()
            check(allowShutdownToFinish.await(2, TimeUnit.SECONDS))
        }
        shutdown.start()
        assertTrue(shutdownEntered.await(2, TimeUnit.SECONDS))

        // A concurrent replacement must still acquire pipelineLock, observe
        // the invalidated generation, and release rather than install itself.
        val replacement = Thread {
            try {
                gate.installOrRelease(
                    expectedGeneration = 11,
                    currentGeneration = { generation.get() },
                    isStarted = { started.get() },
                    isStopping = { stopping.get() },
                    replacement = Any(),
                    release = { released.incrementAndGet() },
                    install = { installed.set(true) }
                )
            } finally {
                replacementFinished.countDown()
            }
        }
        replacement.start()

        assertTrue("replacement must not wait for blocking shutdown", replacementFinished.await(1, TimeUnit.SECONDS))
        assertFalse("stale replacement must never be installed", installed.get())
        assertEquals("stale replacement must be released once", 1, released.get())
        assertTrue("shutdown should still be blocked outside pipelineLock", shutdown.isAlive)

        allowShutdownToFinish.countDown()
        shutdown.join(2_000)
        replacement.join(2_000)
        assertFalse(shutdown.isAlive)
        assertFalse(replacement.isAlive)
    }

}
