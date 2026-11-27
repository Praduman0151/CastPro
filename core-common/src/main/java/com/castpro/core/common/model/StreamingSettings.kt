package com.castpro.core.common.model

enum class Resolution(val displayName: String, val width: Int, val height: Int) {
    HD_720P("720p HD", 1280, 720),
    FHD_1080P("1080p Full HD", 1920, 1080),
    QHD_1440P("1440p 2K QHD", 2560, 1440)
}

enum class Framerate(val displayName: String, val fps: Int) {
    FPS_30("30 FPS (Power Saver)", 30),
    FPS_60("60 FPS (Competitive)", 60),
    FPS_120("120 FPS (Ultra Pro)", 120)
}

enum class BitratePreset(val displayName: String, val bps: Int) {
    BALANCED_8M("8 Mbps (Standard)", 8_000_000),
    GAMING_15M("15 Mbps (High Fidelity)", 15_000_000),
    ULTRA_25M("25 Mbps (Crisp Pro)", 25_000_000),
    EXTREME_40M("40 Mbps (Extreme)", 40_000_000)
}

enum class VideoCodec(
    val displayName: String,
    val mimeType: String,
    val id: Byte
) {
    H264("H.264 / AVC (Broad Compatibility)", "video/avc", 1),
    HEVC("H.265 / HEVC (High Efficiency)", "video/hevc", 2);

    companion object {
        fun fromId(id: Byte): VideoCodec? = values().firstOrNull { it.id == id }

        fun fromMime(mime: String?): VideoCodec =
            values().firstOrNull { it.mimeType.equals(mime, ignoreCase = true) } ?: H264
    }
}

enum class AudioMode(val displayName: String) {
    INTERNAL_GAME("Internal Game Audio (Android 10+)"),
    MICROPHONE("Microphone Voice"),
    MUTED("Muted")
}

/**
 * One-tap streaming profiles. CUSTOM keeps whatever the user tuned manually.
 */
enum class OptimizationProfile(
    val displayName: String,
    val description: String
) {
    CUSTOM("Custom", "Your manual settings"),
    BATTERY("Battery Saver", "720p · 30 FPS · 8 Mb/s · cooler phone"),
    BALANCED("Balanced", "1080p · 60 FPS · 15 Mb/s · best all-round"),
    LOW_LATENCY("Low Latency", "720p · 60 FPS · 8 Mb/s · fastest motion"),
    QUALITY("Max Quality", "1440p · 60 FPS · 25 Mb/s · sharpest image")
}

data class StreamingSettings(
    val profile: OptimizationProfile = OptimizationProfile.CUSTOM,
    val resolution: Resolution = Resolution.FHD_1080P,
    val framerate: Framerate = Framerate.FPS_60,
    val bitrate: BitratePreset = BitratePreset.GAMING_15M,
    val codec: VideoCodec = VideoCodec.H264,
    val audioMode: AudioMode = AudioMode.INTERNAL_GAME,
    val enableFloatingOverlay: Boolean = true,
    val enableKeepAwake: Boolean = true,
    val enableWebServer: Boolean = true,
    val webPort: Int = 8080,
    val isGamingMode: Boolean = true,
    val autoConnect: Boolean = true,
    /** Experimental NDK encoder; false keeps the stable MediaCodec encoder. */
    val useNativeEncoder: Boolean = false
) {
    fun toStreamConfig(targetPort: Int = 8998): StreamConfig {
        return StreamConfig(
            width = resolution.width,
            height = resolution.height,
            fps = framerate.fps,
            bitrate = bitrate.bps,
            isGamingMode = isGamingMode,
            codecMime = codec.mimeType,
            port = targetPort
        )
    }
}
