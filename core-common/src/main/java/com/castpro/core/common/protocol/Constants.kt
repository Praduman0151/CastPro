package com.castpro.core.common.protocol

object Constants {
    const val MAGIC_BYTE_1: Byte = 0x43 // 'C'
    const val MAGIC_BYTE_2: Byte = 0x50 // 'P'

    // Wire protocol version. Bump only together with both apps.
    const val PROTOCOL_VERSION = 1

    // Packet Types
    const val TYPE_VIDEO_NAL: Byte = 0x01
    const val TYPE_AUDIO_DATA: Byte = 0x02
    const val TYPE_HEARTBEAT: Byte = 0x03
    const val TYPE_HANDSHAKE: Byte = 0x04
    const val TYPE_GEOMETRY: Byte = 0x05
    const val TYPE_STREAM_CONFIG: Byte = 0x06
    const val TYPE_VIDEO_JPEG: Byte = 0x07

    /** A corrupt or hostile peer must never make the receiver allocate gigabytes. */
    const val MAX_PAYLOAD_SIZE = 32 * 1024 * 1024
    
    // Flags
    const val FLAG_KEYFRAME: Byte = 0x01
    const val FLAG_GAMING_MODE: Byte = 0x02
    /** SPS/PPS or VPS/SPS/PPS bytes that must reach the decoder before an IDR. */
    const val FLAG_CODEC_CONFIG: Byte = 0x04
    
    // Networking
    const val DEFAULT_STREAM_PORT = 8998
    const val DEFAULT_WEB_PORT = 8080
    const val DEFAULT_WS_PORT = 8081
    const val DEFAULT_DISCOVERY_PORT = 8999
    const val DISCOVERY_REQUEST = "CASTPRO_DISCOVER_V1"
    const val DISCOVERY_REPLY_PREFIX = "CASTPRO_TV_V1"
    const val NSD_SERVICE_TYPE = "_castpro._tcp"
    const val NSD_SERVICE_NAME_PREFIX = "CastPro-TV"

    // Reconnect cadence for a dropped TV connection.
    const val RECONNECT_DELAY_MS = 1_000L
    const val DISCOVERY_INTERVAL_MS = 2_000L
    const val RECEIVER_STALE_MS = 12_000L
    
    // Gaming Mode Defaults
    const val GAMING_FPS = 60
    const val GAMING_BITRATE_BPS = 12_000_000 // 12 Mbps
    const val NORMAL_BITRATE_BPS = 6_000_000  // 6 Mbps
    const val DEFAULT_I_FRAME_INTERVAL = 1    // 1 second

    // Audio Defaults
    const val AUDIO_SAMPLE_RATE = 48000
    const val AUDIO_CHANNELS = 2 // Stereo
    const val AUDIO_BUFFER_SIZE = 4096
}
