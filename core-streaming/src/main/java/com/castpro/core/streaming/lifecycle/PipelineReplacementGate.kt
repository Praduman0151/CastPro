package com.castpro.core.streaming.lifecycle

/**
 * Serializes encoder installation with session teardown and releases prepared
 * replacements that belong to a session which has already stopped/restarted.
 *
 * The caller owns generation/state; this class owns the shared pipeline lock.
 */
class PipelineReplacementGate(private val pipelineLock: Any) {
    fun <T> installOrRelease(
        expectedGeneration: Int,
        currentGeneration: () -> Int,
        isStarted: () -> Boolean,
        isStopping: () -> Boolean,
        replacement: T,
        release: (T) -> Unit,
        install: (T) -> Unit
    ): Boolean = synchronized(pipelineLock) {
        if (isStopping() || !isStarted() || currentGeneration() != expectedGeneration) {
            runCatching { release(replacement) }
            false
        } else {
            install(replacement)
            true
        }
    }

    fun <T> withPipelineLock(action: () -> T): T =
        synchronized(pipelineLock) { action() }
}
