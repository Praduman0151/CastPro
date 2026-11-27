package com.castpro.core.common.protocol

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteBuffer

class PacketStreamReaderTest {
    @Test fun reassemblesHeaderAndPayloadAcrossFragmentedReads() {
        val payload = byteArrayOf(1, 2, 3, 4, 5, 6)
        val header = PacketHeader(Constants.TYPE_VIDEO_NAL, Constants.FLAG_KEYFRAME, 7, 123L, payload.size)
        val bytes = ByteBuffer.allocate(PacketHeader.HEADER_SIZE + payload.size).apply {
            header.writeTo(this)
            put(payload)
        }.array()
        val fragmented = object : InputStream() {
            private val source = ByteArrayInputStream(bytes)
            override fun read(): Int = source.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = source.read(b, off, minOf(len, 2))
        }
        val packet = PacketStreamReader.readPacket(fragmented)
        assertNotNull(packet)
        assertEquals(7, packet!!.header.sequenceNumber)
        assertArrayEquals(payload, packet.payload)
    }

    @Test fun rejectsOversizedAndMalformedHeaders() {
        val oversized = ByteBuffer.allocate(PacketHeader.HEADER_SIZE).apply {
            PacketHeader(Constants.TYPE_VIDEO_NAL, 0, 1, 1L, 1025).writeTo(this)
        }.array()
        try {
            PacketStreamReader.readPacket(ByteArrayInputStream(oversized), maxPayloadBytes = 1024)
            fail("Oversized payload should be rejected")
        } catch (_: IllegalArgumentException) { }

        val malformed = ByteArray(PacketHeader.HEADER_SIZE)
        try {
            PacketStreamReader.readPacket(ByteArrayInputStream(malformed))
            fail("Bad magic should be rejected")
        } catch (_: java.io.IOException) { }
    }
}
