package com.castpro.core.common.model

data class CastingSessionStats(
    val sessionDurationMs: Long = 0L,
    val totalFramesEncoded: Long = 0L,
    val totalFramesDropped: Long = 0L,
    val currentFps: Int = 0,
    val averageFps: Float = 0f,
    val currentLatencyMs: Float = 0f,
    val currentBitrateMbps: Float = 0f,
    val totalBytesTransferred: Long = 0L,
    val activeReceiversCount: Int = 0,
    val isPaused: Boolean = false,
    val isAudioMuted: Boolean = false
) {
    val durationFormatted: String
        get() {
            val seconds = (sessionDurationMs / 1000) % 60
            val minutes = (sessionDurationMs / (1000 * 60)) % 60
            val hours = (sessionDurationMs / (1000 * 60 * 60))
            return if (hours > 0) {
                String.format("%02d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format("%02d:%02d", minutes, seconds)
            }
        }
}
