package com.castpro.core.streaming.decoder

import com.castpro.core.common.model.LatencyMetrics
import com.castpro.core.common.model.StreamConfig

/** Small renderer-facing contract so surface lifecycle can be tested without MediaCodec. */
interface VideoDecoderController {
    fun start()
    fun stop()
    fun reset()
    fun resetSession()
    fun reconfigure(newConfig: StreamConfig)
    fun setVideoScalingMode(mode: Int)
    fun decodeFrame(
        data: ByteArray,
        offset: Int,
        length: Int,
        timestampNano: Long,
        isGamingMode: Boolean,
        isCodecConfig: Boolean,
        isKeyframe: Boolean
    )
}
