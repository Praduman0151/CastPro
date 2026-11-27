package com.castpro.core.streaming.encoder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AspectMatchedSizeTest {
    @Test
    fun nordFiveLandscapePreservesTwentyToNineAspectRatio() {
        assertSize(1920 to 864, computeAspectMatchedSize(2378, 1080))
    }

    @Test
    fun portraitDisplayKeepsOrientationAndEncoderAlignment() {
        assertSize(864 to 1920, computeAspectMatchedSize(1080, 2378))
    }

    @Test
    fun fourToThreeInputStaysWithinShortEdgeLimit() {
        assertSize(1440 to 1072, computeAspectMatchedSize(2000, 1500))
    }

    @Test
    fun fullHdInputIsAlignedDownToMultipleOfSixteen() {
        assertSize(1920 to 1072, computeAspectMatchedSize(1920, 1080))
    }

    @Test
    fun squareInputIsCappedAndAligned() {
        assertSize(1072 to 1072, computeAspectMatchedSize(1500, 1500))
    }

    @Test
    fun alreadySmallInputIsNeverUpscaled() {
        assertSize(640 to 352, computeAspectMatchedSize(640, 360))
    }

    @Test
    fun zeroNegativeAndTinyInputsNeverReturnZeroOrThrow() {
        listOf(
            0 to 0,
            -1920 to 1080,
            1080 to -1920,
            1 to 1,
            15 to 15,
            8 to 15
        ).forEach { (inputW, inputH) ->
            val result = computeAspectMatchedSize(inputW, inputH)
            assertTrue("width must be positive for input ${inputW}x${inputH}", result.first > 0)
            assertTrue("height must be positive for input ${inputW}x${inputH}", result.second > 0)
            assertInvariants(result)
        }
    }

    private fun assertSize(expected: Pair<Int, Int>, actual: Pair<Int, Int>) {
        assertEquals(expected, actual)
        assertInvariants(actual)
    }

    private fun assertInvariants(size: Pair<Int, Int>) {
        val (width, height) = size
        assertEquals(0, width % 16)
        assertEquals(0, height % 16)
        assertTrue("short edge must not exceed 1080: ${width}x${height}", minOf(width, height) <= 1080)
    }
}
