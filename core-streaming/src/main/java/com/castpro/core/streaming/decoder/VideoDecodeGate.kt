package com.castpro.core.streaming.decoder

/** Prevents access units reaching MediaCodec until valid CSD and an IDR are available. */
class VideoDecodeGate {
    private var hasCodecConfig = false
    private var waitingForKeyframe = true

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

    fun resetForReconfigure() {
        hasCodecConfig = false
        waitingForKeyframe = true
    }

    fun hasCodecConfig(): Boolean = hasCodecConfig
}
