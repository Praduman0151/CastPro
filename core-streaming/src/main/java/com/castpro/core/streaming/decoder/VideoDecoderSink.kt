package com.castpro.core.streaming.decoder

import com.castpro.core.common.model.StreamConfig

/** The blocking side of the decoder, called only from the decoder thread. */
interface VideoDecoderSink {
    /** Returns true when the frame was accepted by MediaCodec. */
    fun decodeFrame(frame: DecoderCommand.Frame): Boolean
    fun reconfigure(config: StreamConfig)
    fun reset()
    fun stop()
}
