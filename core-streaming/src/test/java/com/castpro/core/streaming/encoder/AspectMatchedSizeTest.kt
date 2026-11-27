package com.castpro.core.streaming.encoder

import org.junit.Assert.assertEquals
import org.junit.Test

class AspectMatchedSizeTest {
    @Test
    fun nordFiveLandscapePreservesTwentyToNineAspectRatio() {
        assertEquals(1920 to 864, computeAspectMatchedSize(2378, 1080))
    }

    @Test
    fun portraitDisplayKeepsOrientationAndEncoderAlignment() {
        val (width, height) = computeAspectMatchedSize(1080, 2378)
        assertEquals(864, width)
        assertEquals(1920, height)
        assertEquals(0, width % 16)
        assertEquals(0, height % 16)
    }

    @Test
    fun shortEdgeNeverExceeds1080() {
        assertEquals(1440 to 1080, computeAspectMatchedSize(2000, 1500))
    }
}
