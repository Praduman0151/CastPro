package com.castpro.core.streaming.decoder

import org.junit.Assert.*
import org.junit.Test

class VideoDecodeGateTest {
    @Test fun lateJoinDropsFramesUntilConfigThenIdr() {
        val gate = VideoDecodeGate()
        assertFalse(gate.canQueueFrame(isKeyframe = false))
        assertFalse(gate.canQueueFrame(isKeyframe = true))
        gate.markCodecConfigAvailable()
        assertFalse(gate.canQueueFrame(isKeyframe = false))
        assertTrue(gate.canQueueFrame(isKeyframe = true))
        gate.markKeyframeQueued()
        assertTrue(gate.canQueueFrame(isKeyframe = false))
    }

    @Test fun flushRequiresFreshIdrButRetainsKnownConfig() {
        val gate = VideoDecodeGate()
        gate.markCodecConfigAvailable()
        assertTrue(gate.canQueueFrame(isKeyframe = true))
        gate.markKeyframeQueued()
        gate.resetForFlush()
        assertFalse(gate.canQueueFrame(isKeyframe = false))
        assertTrue(gate.canQueueFrame(isKeyframe = true))
    }

    @Test fun reconfigureRequiresNewConfigAndIdr() {
        val gate = VideoDecodeGate()
        gate.markCodecConfigAvailable()
        gate.resetForReconfigure()
        assertFalse(gate.hasCodecConfig())
        assertFalse(gate.canQueueFrame(isKeyframe = true))
    }
    @Test fun inlineAvcSpsPpsAndIdrUnlocksGateWithoutSeparateConfigPacket() {
        val gate = VideoDecodeGate()
        val accessUnit = byteArrayOf(
            0, 0, 0, 1, 0x67, 0x42, 0, 0x1E,
            0, 0, 1, 0x68, 0xCE.toByte(), 0x06,
            0, 0, 0, 1, 0x65, 0x11, 0x22
        )
        assertFalse(gate.hasCodecConfig())
        val inlineCsd = gate.extractInlineParameterSets("video/avc", accessUnit, 0, accessUnit.size)
        assertNotNull(inlineCsd)
        gate.markCodecConfigAvailable()
        assertTrue(gate.canQueueFrame(isKeyframe = true))
    }

    @Test fun recreatedDecoderGateAcceptsIdrAfterSessionCsdSeed() {
        val cachedCsd = byteArrayOf(
            0, 0, 0, 1, 0x67, 0x42, 0, 0x1E,
            0, 0, 1, 0x68, 0xCE.toByte(), 0x06
        )
        val gateForRecreatedDecoder = VideoDecodeGate()
        val validatedSeed = gateForRecreatedDecoder.extractInlineParameterSets(
            "video/avc", cachedCsd, 0, cachedCsd.size
        )
        assertNotNull(validatedSeed)
        gateForRecreatedDecoder.markCodecConfigAvailable()
        assertTrue(gateForRecreatedDecoder.canQueueFrame(isKeyframe = true))
    }

    @Test fun recreatedTextureSurfaceRequiresCachedCsdAndFreshIdrBeforePFrames() {
        val cachedSpsPps = byteArrayOf(
            0, 0, 0, 1, 0x67, 0x42, 0, 0x1E,
            0, 0, 1, 0x68, 0xCE.toByte(), 0x06
        )

        // surfaceDestroyed stops the old decoder; surfaceCreated creates a new
        // decoder/gate and seeds it from the renderer's session CSD cache.
        val recreatedGate = VideoDecodeGate()
        assertFalse(recreatedGate.canQueueFrame(isKeyframe = false))
        assertNotNull(recreatedGate.extractInlineParameterSets(
            "video/avc", cachedSpsPps, 0, cachedSpsPps.size
        ))
        recreatedGate.markCodecConfigAvailable()
        assertFalse(recreatedGate.canQueueFrame(isKeyframe = false))
        assertTrue(recreatedGate.canQueueFrame(isKeyframe = true))
        recreatedGate.markKeyframeQueued()
        assertTrue(recreatedGate.canQueueFrame(isKeyframe = false))
    }

    @Test fun disconnectClosesGateAndReconnectRequiresFreshConfigAndIdr() {
        val gate = VideoDecodeGate()
        gate.markCodecConfigAvailable()
        assertTrue(gate.canQueueFrame(isKeyframe = true))
        gate.markKeyframeQueued()
        assertTrue(gate.canQueueFrame(isKeyframe = false))

        gate.resetForSession()
        assertFalse(gate.hasCodecConfig())
        assertFalse(gate.canQueueFrame(isKeyframe = true))
        assertFalse(gate.canQueueFrame(isKeyframe = false))

        gate.markCodecConfigAvailable()
        assertFalse(gate.canQueueFrame(isKeyframe = false))
        assertTrue(gate.canQueueFrame(isKeyframe = true))
        gate.markKeyframeQueued()
        assertTrue(gate.canQueueFrame(isKeyframe = false))
    }

}
