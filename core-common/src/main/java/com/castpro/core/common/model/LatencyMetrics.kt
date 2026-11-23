package com.castpro.core.common.model

data class LatencyMetrics(
    val currentFps: Int = 0,
    val latencyMs: Float = 0.0f,
    val bitrateMbps: Float = 0.0f,
    val packetLossPercent: Float = 0.0f,
    val isGamingModeActive: Boolean = true
)
