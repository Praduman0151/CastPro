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
}
