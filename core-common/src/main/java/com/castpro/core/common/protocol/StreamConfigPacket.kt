package com.castpro.core.common.protocol

import com.castpro.core.common.model.VideoCodec
import java.nio.ByteBuffer

/**
 * First packet of a stream (and the first packet after every reconnect).
 *
 * The TV used to assume H.264/1080p forever. Sending the real encoder
 * configuration lets the receiver build the right MediaCodec and lets a
 * reconnect recover even if the phone's settings changed between sessions.
 */
data class StreamConfigPacket(
    val codec: VideoCodec = VideoCodec.H264,
    val width: Int = 1920,
    val height: Int = 1080,
    val fps: Int = 60,
    val bitrate: Int = 12_000_000,
    val rotationDegrees: Int = 0,
    val isGamingMode: Boolean = true,
    val protocolVersion: Int = Constants.PROTOCOL_VERSION
) {
    fun toByteArray(): ByteArray {
        val buffer = ByteBuffer.allocate(SIZE)
        buffer.put(protocolVersion.toByte())
        buffer.put(codec.id)
        buffer.put(if (isGamingMode) 0x01 else 0x00)
        buffer.put(0x00)
        buffer.putInt(width)
        buffer.putInt(height)
        buffer.putInt(fps)
        buffer.putInt(bitrate)
        buffer.putInt(rotationDegrees)
        return buffer.array()
    }

    companion object {
        const val SIZE = 24

        fun fromByteArray(data: ByteArray, offset: Int = 0): StreamConfigPacket? {
            if (data.size - offset < SIZE) return null
            val buffer = ByteBuffer.wrap(data, offset, SIZE)
            val version = buffer.get().toInt() and 0xFF
            if (version <= 0 || version > Constants.PROTOCOL_VERSION) return null
            val codec = VideoCodec.fromId(buffer.get()) ?: return null
            val flags = buffer.get().toInt() and 0xFF
            buffer.get() // reserved
            val width = buffer.int
            val height = buffer.int
            val fps = buffer.int
            val bitrate = buffer.int
            val rotation = buffer.int
            if (width <= 0 || height <= 0 || fps <= 0 || bitrate <= 0) return null
            return StreamConfigPacket(
                codec = codec,
                width = width,
                height = height,
                fps = fps,
                bitrate = bitrate,
                rotationDegrees = rotation,
                isGamingMode = (flags and 0x01) != 0,
                protocolVersion = version
            )
        }
    }
}
