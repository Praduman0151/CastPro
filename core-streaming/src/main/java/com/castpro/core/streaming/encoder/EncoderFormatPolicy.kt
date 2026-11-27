package com.castpro.core.streaming.encoder

import android.media.MediaCodecInfo
import android.media.MediaFormat

/** Pure format policy shared by the encoder and its JVM regression test. */
internal object EncoderFormatPolicy {
    fun minimalFallbackValues(bitrate: Int, frameRate: Int, iFrameInterval: Int): Map<String, Int> =
        linkedMapOf(
            MediaFormat.KEY_COLOR_FORMAT to MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            MediaFormat.KEY_BIT_RATE to bitrate,
            MediaFormat.KEY_FRAME_RATE to frameRate,
            MediaFormat.KEY_I_FRAME_INTERVAL to iFrameInterval
        )
}
