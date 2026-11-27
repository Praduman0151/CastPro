package com.castpro.core.streaming.lifecycle

/**
 * Serializes encoder installation with session teardown and releases prepared
 * replacements that belong to a session which has already stopped/restarted.
 *
 * The caller owns generation/state; this class owns the shared pipeline lock.
 * Rejected replacement cleanup is deliberately performed after releasing the
 * lock because codec stop/release may block.
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
    ): Boolean {
        val installed = synchronized(pipelineLock) {
            if (isStopping() || !isStarted() || currentGeneration() != expectedGeneration) {
                false
            } else {
                install(replacement)
                true
            }
        }
        if (!installed) {
            runCatching { release(replacement) }
        }
        return installed
    }

    fun <T> withPipelineLock(action: () -> T): T =
        synchronized(pipelineLock) { action() }
}
