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
        assertEquals(12_000_000, config.bitrate)
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
}
