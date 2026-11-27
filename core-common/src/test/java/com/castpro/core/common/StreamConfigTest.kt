package com.castpro.core.common

import com.castpro.core.common.model.StreamConfig
import com.castpro.core.common.protocol.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamConfigTest {

    @Test
    fun testDefaultGamingModeConfig() {
        val config = StreamConfig(isGamingMode = true, fps = 60, bitrate = Constants.GAMING_BITRATE_BPS)
        assertTrue(config.isGamingMode)
        assertEquals(60, config.fps)
        assertEquals(8_000_000, config.bitrate)
        assertEquals(1920, config.width)
        assertEquals(1080, config.height)
    }

    @Test
    fun testGeometryPacketSerialization() {
        val original = com.castpro.core.common.model.GeometryPacket(
            width = 1920,
            height = 1080,
            aspectRatio = 1.777f,
            rotationDegrees = 90
        )
        val bytes = original.toByteArray()
        val deserialized = com.castpro.core.common.model.GeometryPacket.fromByteArray(bytes)

        assertNotNull(deserialized)
        assertEquals(1920, deserialized!!.width)
        assertEquals(1080, deserialized.height)
        assertEquals(1.777f, deserialized.aspectRatio, 0.001f)
        assertEquals(90, deserialized.rotationDegrees)
    }

    @Test
    fun testStreamingSettingsConversion() {
        val settings = com.castpro.core.common.model.StreamingSettings(
            resolution = com.castpro.core.common.model.Resolution.HD_720P,
            framerate = com.castpro.core.common.model.Framerate.FPS_120,
            bitrate = com.castpro.core.common.model.BitratePreset.ULTRA_25M,
            codec = com.castpro.core.common.model.VideoCodec.HEVC
        )
        val config = settings.toStreamConfig()
        assertEquals(1280, config.width)
        assertEquals(720, config.height)
        assertEquals(120, config.fps)
        assertEquals(25_000_000, config.bitrate)
        assertEquals("video/hevc", config.codecMime)
    }

    @Test
    fun testPairingInfoRoundTrip() {
        val info = com.castpro.core.common.model.PairingInfo(
            host = "192.168.1.50",
            port = 8998,
            name = "Living Room TV"
        )
        val parsed = com.castpro.core.common.model.PairingInfo.parse(info.toUri())
        assertNotNull(parsed)
        assertEquals("192.168.1.50", parsed!!.host)
        assertEquals(8998, parsed.port)
        assertEquals("Living Room TV", parsed.name)
    }

    @Test
    fun testPairingAcceptsPlainAddressAndPort() {
        val parsed = com.castpro.core.common.model.PairingInfo.parse("192.168.0.20:9001")
        assertNotNull(parsed)
        assertEquals("192.168.0.20", parsed!!.host)
        assertEquals(9001, parsed.port)

        val web = com.castpro.core.common.model.PairingInfo.parse("http://192.168.0.20:8080")
        assertNotNull(web)
        assertEquals("192.168.0.20", web!!.host)
        assertEquals(8998, web.port)
        assertEquals(8080, web.webPort)
    }

    @Test
    fun testPairingRejectsGarbage() {
        assertEquals(null, com.castpro.core.common.model.PairingInfo.parse(""))
        assertEquals(null, com.castpro.core.common.model.PairingInfo.parse("not a host"))
        assertEquals(null, com.castpro.core.common.model.PairingInfo.parse("castpro://connect?port=8998"))
    }

    @Test
    fun testStreamConfigPacketRoundTrip() {
        val original = com.castpro.core.common.protocol.StreamConfigPacket(
            codec = com.castpro.core.common.model.VideoCodec.HEVC,
            width = 1280,
            height = 720,
            fps = 120,
            bitrate = 25_000_000,
            rotationDegrees = 90,
            isGamingMode = true
        )
        val parsed = com.castpro.core.common.protocol.StreamConfigPacket.fromByteArray(original.toByteArray())
        assertNotNull(parsed)
        assertEquals(original, parsed)
    }
}
