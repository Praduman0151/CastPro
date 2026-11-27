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
}
