package com.castpro.core.common.protocol

import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer

data class StreamPacket(val header: PacketHeader, val payload: ByteArray)

/** Reads complete CastPro packets from a TCP byte stream, independent of read chunking. */
object PacketStreamReader {
    @Throws(IOException::class, IllegalArgumentException::class)
    fun readPacket(input: InputStream, maxPayloadBytes: Int = Constants.MAX_PAYLOAD_SIZE): StreamPacket? {
        val headerBytes = ByteArray(PacketHeader.HEADER_SIZE)
        if (!readFully(input, headerBytes)) return null
        val header = PacketHeader.readFrom(ByteBuffer.wrap(headerBytes))
            ?: throw IOException("Invalid CastPro packet magic/header")
        if (header.payloadLength < 0 || header.payloadLength > maxPayloadBytes) {
            throw IllegalArgumentException("Invalid CastPro payload length: ${header.payloadLength}")
        }
        val payload = ByteArray(header.payloadLength)
        if (!readFully(input, payload)) return null
        return StreamPacket(header, payload)
    }

    private fun readFully(input: InputStream, target: ByteArray): Boolean {
        var offset = 0
        while (offset < target.size) {
            val count = input.read(target, offset, target.size - offset)
            if (count < 0) return false
            if (count == 0) continue
            offset += count
        }
        return true
    }
}
