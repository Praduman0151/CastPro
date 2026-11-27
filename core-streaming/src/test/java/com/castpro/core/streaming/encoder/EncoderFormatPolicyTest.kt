package com.castpro.core.streaming.encoder

import android.media.MediaFormat
import org.junit.Assert.assertEquals
import org.junit.Test

class EncoderFormatPolicyTest {
    @Test fun fallbackContainsOnlyRequiredSurfaceEncoderKeys() {
        val values = EncoderFormatPolicy.minimalFallbackValues(8_000_000, 60, 1)
        assertEquals(
            setOf(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaFormat.KEY_BIT_RATE,
                MediaFormat.KEY_FRAME_RATE,
                MediaFormat.KEY_I_FRAME_INTERVAL
            ),
            values.keys
        )
        assertEquals(8_000_000, values[MediaFormat.KEY_BIT_RATE])
        assertEquals(60, values[MediaFormat.KEY_FRAME_RATE])
    }
}
