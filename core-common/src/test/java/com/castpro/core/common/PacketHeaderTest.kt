package com.castpro.core.common

import com.castpro.core.common.protocol.Constants
import com.castpro.core.common.protocol.PacketHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.nio.ByteBuffer

class PacketHeaderTest {

    @Test
    fun testPacketHeaderSerializationAndDeserialization() {
        val original = PacketHeader(
            type = Constants.TYPE_VIDEO_NAL,
            flags = (Constants.FLAG_KEYFRAME.toInt() or Constants.FLAG_GAMING_MODE.toInt()).toByte(),
            sequenceNumber = 42,
            timestampNano = 123456789012345L,
            payloadLength = 4096
        )

        val buffer = ByteBuffer.allocate(PacketHeader.HEADER_SIZE)
        original.writeTo(buffer)

        buffer.flip()
        val deserialized = PacketHeader.readFrom(buffer)

        assertNotNull(deserialized)
        assertEquals(original.type, deserialized!!.type)
        assertEquals(original.flags, deserialized.flags)
        assertEquals(original.sequenceNumber, deserialized.sequenceNumber)
        assertEquals(original.timestampNano, deserialized.timestampNano)
        assertEquals(original.payloadLength, deserialized.payloadLength)
    }

    @Test
    fun testAudioPacketHeaderSerialization() {
        val audioHeader = PacketHeader(
            type = Constants.TYPE_AUDIO_DATA,
            flags = 0,
            sequenceNumber = 101,
            timestampNano = 998877665544L,
            payloadLength = Constants.AUDIO_BUFFER_SIZE
        )

        val buffer = ByteBuffer.allocate(PacketHeader.HEADER_SIZE)
        audioHeader.writeTo(buffer)

        buffer.flip()
        val readHeader = PacketHeader.readFrom(buffer)

        assertNotNull(readHeader)
        assertEquals(Constants.TYPE_AUDIO_DATA, readHeader!!.type)
        assertEquals(101, readHeader.sequenceNumber)
        assertEquals(Constants.AUDIO_BUFFER_SIZE, readHeader.payloadLength)
    }

    @Test
    fun testVideoFlagsSurviveTheWire() {
        // The TV needs all three bits: keyframe starts decoding, codec config
        // carries SPS/PPS, gaming mode drives the HUD.
        val flags = (Constants.FLAG_KEYFRAME.toInt() or
            Constants.FLAG_CODEC_CONFIG.toInt() or
            Constants.FLAG_GAMING_MODE.toInt()).toByte()
        val original = PacketHeader(
            type = Constants.TYPE_VIDEO_NAL,
            flags = flags,
            sequenceNumber = 7,
            timestampNano = 1_000L,
            payloadLength = 128
        )

        val buffer = ByteBuffer.allocate(PacketHeader.HEADER_SIZE)
        original.writeTo(buffer)
        buffer.flip()
        val decoded = PacketHeader.readFrom(buffer)!!

        assertEquals(flags, decoded.flags)
        assertEquals(true, (decoded.flags.toInt() and Constants.FLAG_KEYFRAME.toInt()) != 0)
        assertEquals(true, (decoded.flags.toInt() and Constants.FLAG_CODEC_CONFIG.toInt()) != 0)
        assertEquals(true, (decoded.flags.toInt() and Constants.FLAG_GAMING_MODE.toInt()) != 0)
    }

    @Test
    fun testCorruptedMagicBytesAreRejected() {
        val buffer = ByteBuffer.allocate(PacketHeader.HEADER_SIZE)
        buffer.put(0x00) // Invalid magic
        buffer.put(0x00)
        buffer.put(Constants.TYPE_VIDEO_NAL)
        buffer.put(0)
        buffer.putInt(1)
        buffer.putLong(100L)
        buffer.putInt(50)

        buffer.flip()
        val result = PacketHeader.readFrom(buffer)
        assertEquals(null, result)
    }
}
