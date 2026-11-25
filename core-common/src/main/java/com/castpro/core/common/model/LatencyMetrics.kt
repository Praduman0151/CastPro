package com.castpro.core.common.model

data class LatencyMetrics(
    val currentFps: Int = 0,
    /** Negative means no trustworthy latency sample is available yet. */
    val latencyMs: Float = -1.0f,
    val bitrateMbps: Float = 0.0f,
    val packetLossPercent: Float = 0.0f,
    val isGamingModeActive: Boolean = true,
    /** Actual decoded resolution reported by MediaCodec, e.g. 1920x1080. */
    val resolutionLabel: String = "",
    /** Codec and profile actually used, e.g. H.264 High. */
    val codecLabel: String = "",
    /** Bitrate requested by the phone, for comparison with measured bitrate. */
    val targetBitrateMbps: Float = 0.0f
)
