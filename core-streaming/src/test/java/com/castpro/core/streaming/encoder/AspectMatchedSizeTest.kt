package com.castpro.core.streaming.encoder

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
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
    fun fractionalScaleDoesNotLoseAnExtraSixteenPixelStepFor2127By1080() {
        assertSize(1920 to 960, computeAspectMatchedSize(2127, 1080))
    }

    @Test
    fun fractionalScaleProducesExactAlignedOutputFor2174By1080() {
        assertSize(1920 to 944, computeAspectMatchedSize(2174, 1080))
    }

    @Test
    fun fractionalScaleProducesExactAlignedOutputFor2333By1080() {
        assertSize(1920 to 880, computeAspectMatchedSize(2333, 1080))
    }

    @Test
    fun alreadySmall320By240InputIsNotDistortedOrUpscaled() {
        assertSize(320 to 240, computeAspectMatchedSize(320, 240))
    }

    @Test
    fun veryWideShortInputIsNotUpscaledOnItsShortAxis() {
        assertSize(1280 to 96, computeAspectMatchedSize(1280, 100))
    }

    @Test
    fun alreadySmallInputIsNeverUpscaled() {
        assertSize(640 to 352, computeAspectMatchedSize(640, 360))
    }

    @Test
    fun zeroAndNegativeDimensionsFailLoudly() {
        listOf(
            0 to 0,
            -1920 to 1080,
            1080 to -1920,
            1920 to 0
        ).forEach { (width, height) ->
            assertThrowsIllegalArgument { computeAspectMatchedSize(width, height) }
        }
    }

    @Test
    fun positiveDimensionsBelowSixteenFailRatherThanUpscaleOrReturnZero() {
        listOf(1 to 1, 15 to 15, 8 to 15, 15 to 320).forEach { (width, height) ->
            assertThrowsIllegalArgument { computeAspectMatchedSize(width, height) }
        }
    }

    private fun assertThrowsIllegalArgument(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected: a positive 16-aligned result is impossible below 16px.
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
        assertEquals(true, minOf(width, height) <= 1080)
    }
}
