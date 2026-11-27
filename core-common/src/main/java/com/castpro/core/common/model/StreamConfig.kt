package com.castpro.core.common.model

data class StreamConfig(
    val width: Int = 1920,
    val height: Int = 1080,
    val dpi: Int = 320,
    val fps: Int = 60,
    val bitrate: Int = 8_000_000,
    val isGamingMode: Boolean = true,
    val codecMime: String = "video/avc",
    val port: Int = 8998
)

enum class DiscoverySource { NSD, UDP, MANUAL, QR }

data class DiscoveredReceiver(
    val deviceName: String,
    val hostAddress: String,
    val port: Int,
    val isAvailable: Boolean = true,
    val source: DiscoverySource = DiscoverySource.NSD,
    val lastSeenMs: Long = System.currentTimeMillis()
) {
    val id: String get() = "$hostAddress:$port"

    fun toPairingInfo(): PairingInfo = PairingInfo(hostAddress, port, deviceName)
}
