package com.castpro.core.streaming.decoder

/** Prevents access units reaching MediaCodec until valid CSD and an IDR are available. */
class VideoDecodeGate {
    private var hasCodecConfig = false
    private var waitingForKeyframe = true

    fun markCodecConfigAvailable() {
        hasCodecConfig = true
    }

    fun shouldQueueFrame(isKeyframe: Boolean): Boolean {
        if (!hasCodecConfig) return false
        if (waitingForKeyframe && !isKeyframe) return false
        if (isKeyframe) waitingForKeyframe = false
        return true
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
