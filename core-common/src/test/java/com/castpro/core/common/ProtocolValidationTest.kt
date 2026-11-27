package com.castpro.core.common

import com.castpro.core.common.model.GeometryPacket
import com.castpro.core.common.model.LatencyMetrics
import com.castpro.core.common.protocol.Constants
import com.castpro.core.common.protocol.PacketHeader
import com.castpro.core.common.protocol.StreamConfigPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * Guards the "never trust the wire" paths: a malformed or newer packet must be
 * rejected instead of reaching MediaCodec or an allocation.
 */
class ProtocolValidationTest {

    @Test
    fun headerRejectsShortBufferAndWrongMagic() {
        assertNull(PacketHeader.readFrom(ByteBuffer.allocate(PacketHeader.HEADER_SIZE - 1)))

        val header = PacketHeader(
            type = Constants.TYPE_VIDEO_NAL,
            flags = 0,
            sequenceNumber = 1,
            timestampNano = 1L,
            payloadLength = 0
        )
        val bytes = ByteBuffer.allocate(PacketHeader.HEADER_SIZE).also(header::writeTo).array()
        bytes[0] = 0x00 // corrupt the first magic byte
        assertNull(PacketHeader.readFrom(ByteBuffer.wrap(bytes)))
    }

    @Test
    fun streamConfigRejectsZeroDimensions() {
        val valid = StreamConfigPacket(width = 1920, height = 1080)
        val bytes = valid.toByteArray()
        // version(1) codec(1) flags(1) reserved(1) then width(4)
        ByteBuffer.wrap(bytes).putInt(4, 0)
        assertNull(StreamConfigPacket.fromByteArray(bytes))
    }

    @Test
    fun streamConfigRejectsNewerProtocolVersion() {
        val bytes = StreamConfigPacket().toByteArray()
        bytes[0] = (Constants.PROTOCOL_VERSION + 1).toByte()
        assertNull(StreamConfigPacket.fromByteArray(bytes))
    }

    @Test
    fun streamConfigAcceptsH264AndHevc() {
        val h264 = StreamConfigPacket(
            codec = com.castpro.core.common.model.VideoCodec.H264,
            rotationDegrees = 270
        )
        val h265 = StreamConfigPacket(
            codec = com.castpro.core.common.model.VideoCodec.HEVC,
            rotationDegrees = 90
        )
        assertEquals(h264, StreamConfigPacket.fromByteArray(h264.toByteArray()))
        assertEquals(h265, StreamConfigPacket.fromByteArray(h265.toByteArray()))
    }

    @Test
    fun geometryRejectsShortPayload() {
        assertNull(GeometryPacket.fromByteArray(ByteArray(15)))
        assertNotNull(GeometryPacket.fromByteArray(ByteArray(16)))
    }

    @Test
    fun latencyIsUnknownUntilCalibrated() {
        // A zero default used to show a fake 0 ms latency before the first
        // heartbeat calibrated the TV/phone clock difference.
        assertTrue(LatencyMetrics().latencyMs < 0f)
    }
}
