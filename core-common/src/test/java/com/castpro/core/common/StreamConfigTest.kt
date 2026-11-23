package com.castpro.core.common

import com.castpro.core.common.model.StreamConfig
import com.castpro.core.common.protocol.Constants
import org.junit.Assert.assertEquals
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
}
