package com.castpro.core.common.model

data class StreamConfig(
    val width: Int = 1920,
    val height: Int = 1080,
    val dpi: Int = 320,
    val fps: Int = 60,
    val bitrate: Int = 12_000_000,
    val isGamingMode: Boolean = true,
    val codecMime: String = "video/avc",
    val port: Int = 8998
)

data class DiscoveredReceiver(
    val deviceName: String,
    val hostAddress: String,
    val port: Int,
    val isAvailable: Boolean = true
)
