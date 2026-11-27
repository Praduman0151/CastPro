package com.castpro.core.streaming.lifecycle

/** Small pure policies shared by Android callbacks and deterministic JVM tests. */
object OrientationReconfigurationPolicy {
    const val DEBOUNCE_MS = 250L
    fun shouldReconfigure(currentWidth: Int, currentHeight: Int, displayWidth: Int, displayHeight: Int): Boolean {
        if (currentWidth <= 0 || currentHeight <= 0 || displayWidth <= 0 || displayHeight <= 0) return false
        return (currentWidth >= currentHeight) != (displayWidth >= displayHeight)
    }
}

enum class VideoStallAction { NONE, REQUEST_KEYFRAME, RECREATE_ENCODER }

object VideoStallPolicy {
    const val REQUEST_KEYFRAME_AFTER_MS = 3_000L
    const val RECREATE_ENCODER_AFTER_MS = 8_000L

    fun action(stalledForMs: Long): VideoStallAction = when {
        stalledForMs >= RECREATE_ENCODER_AFTER_MS -> VideoStallAction.RECREATE_ENCODER
        stalledForMs >= REQUEST_KEYFRAME_AFTER_MS -> VideoStallAction.REQUEST_KEYFRAME
        else -> VideoStallAction.NONE
    }
}

data class CleanupStep(val name: String, val action: () -> Unit)

object IndependentCleanup {
    /** Every step runs even if a previous resource's release throws. */
    fun run(steps: List<CleanupStep>, onFailure: (String, Throwable) -> Unit = { _, _ -> }) {
        steps.forEach { step ->
            runCatching { step.action() }.onFailure { error ->
                runCatching { onFailure(step.name, error) }
            }
        }
    }
}
