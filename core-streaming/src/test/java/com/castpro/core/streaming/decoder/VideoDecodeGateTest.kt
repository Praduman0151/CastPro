package com.castpro.core.streaming.decoder

import org.junit.Assert.*
import org.junit.Test

class VideoDecodeGateTest {
    @Test fun lateJoinDropsFramesUntilConfigThenIdr() {
        val gate = VideoDecodeGate()
        assertFalse(gate.canQueueFrame(isKeyframe = false))
        assertFalse(gate.canQueueFrame(isKeyframe = true))
        gate.markCodecConfigAvailable()
        assertFalse(gate.shouldQueueFrame(isKeyframe = false))
        assertTrue(gate.shouldQueueFrame(isKeyframe = true))
        assertTrue(gate.shouldQueueFrame(isKeyframe = false))
    }

    @Test fun flushRequiresFreshIdrButRetainsKnownConfig() {
        val gate = VideoDecodeGate()
        gate.markCodecConfigAvailable()
        assertTrue(gate.shouldQueueFrame(isKeyframe = true))
        gate.resetForFlush()
        assertFalse(gate.shouldQueueFrame(isKeyframe = false))
        assertTrue(gate.shouldQueueFrame(isKeyframe = true))
    }

    @Test fun reconfigureRequiresNewConfigAndIdr() {
        val gate = VideoDecodeGate()
        gate.markCodecConfigAvailable()
        gate.resetForReconfigure()
        assertFalse(gate.hasCodecConfig())
        assertFalse(gate.shouldQueueFrame(isKeyframe = true))
    }
}
