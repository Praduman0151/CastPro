package com.castpro.core.common.protocol

import java.nio.ByteBuffer

/**
 * 16-Byte Low-Latency Binary Header for Cast Pro stream packets.
 */
data class PacketHeader(
    val type: Byte,
    val flags: Byte,
    val sequenceNumber: Int,
    val timestampNano: Long,
    val payloadLength: Int
) {
    fun writeTo(buffer: ByteBuffer) {
        buffer.put(Constants.MAGIC_BYTE_1)
        buffer.put(Constants.MAGIC_BYTE_2)
        buffer.put(type)
        buffer.put(flags)
        buffer.putInt(sequenceNumber)
        buffer.putLong(timestampNano)
        buffer.putInt(payloadLength)
    }

    companion object {
        const val HEADER_SIZE = 20 // 2 (magic) + 1 (type) + 1 (flags) + 4 (seq) + 8 (nano) + 4 (len)

        fun readFrom(buffer: ByteBuffer): PacketHeader? {
            if (buffer.remaining() < HEADER_SIZE) return null
            val m1 = buffer.get()
            val m2 = buffer.get()
            if (m1 != Constants.MAGIC_BYTE_1 || m2 != Constants.MAGIC_BYTE_2) {
                return null
            }
            val type = buffer.get()
            val flags = buffer.get()
            val seq = buffer.int
            val timestampNano = buffer.long
            val payloadLength = buffer.int
            return PacketHeader(type, flags, seq, timestampNano, payloadLength)
        }
    }
}
