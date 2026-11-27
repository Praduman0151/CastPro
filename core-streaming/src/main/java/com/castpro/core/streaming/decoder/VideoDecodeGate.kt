package com.castpro.core.streaming.decoder

import com.castpro.core.common.protocol.AnnexBCodecConfig

/** Prevents access units reaching MediaCodec until valid CSD and an IDR are available. */
class VideoDecodeGate {
    private var hasCodecConfig = false
    private var waitingForKeyframe = true

    /** Extract inline SPS/PPS (or VPS/SPS/PPS) from an IDR access unit. */
    fun extractInlineParameterSets(
        codecMime: String,
        data: ByteArray,
        offset: Int,
        length: Int
    ): ByteArray? = AnnexBCodecConfig.extractParameterSets(codecMime, data, offset, length)

    fun markCodecConfigAvailable() {
        hasCodecConfig = true
    }

    fun canQueueFrame(isKeyframe: Boolean): Boolean =
        hasCodecConfig && (!waitingForKeyframe || isKeyframe)

    fun markKeyframeQueued() {
        waitingForKeyframe = false
    }

    fun resetForFlush() {
        waitingForKeyframe = true
    }

    /** A new TCP peer must supply fresh parameter sets and an IDR. */
    fun resetForSession() = resetForReconfigure()

    fun resetForReconfigure() {
        hasCodecConfig = false
        waitingForKeyframe = true
    }

    fun hasCodecConfig(): Boolean = hasCodecConfig
}
