package com.castpro.core.streaming.decoder

import com.castpro.core.common.model.StreamConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue

class DecoderPipelineTest {

    private class RecordingSink : VideoDecoderSink {
        val events = ConcurrentLinkedQueue<String>()
        @Volatile var acceptFrames = true

        override fun decodeFrame(frame: DecoderCommand.Frame): Boolean {
            events.add(
                when {
                    frame.isCodecConfig -> "SPS"
                    frame.isKeyframe -> "IDR"
                    else -> "P"
                }
            )
            return acceptFrames
        }

        override fun reconfigure(config: StreamConfig) { events.add("RECONFIG") }
        override fun reset() { events.add("RESET") }
        override fun stop() { events.add("STOP") }
    }

    @Test
    fun pFramesAreDroppedUntilKeyframeArrives() {
        val sink = RecordingSink()
        val pipeline = DecoderPipeline(sink)
        pipeline.submitReset()
        pipeline.submitFrame(sps(0))
        pipeline.submitFrame(pFrame(1))
        pipeline.submitFrame(idr(2))
        pipeline.submitFrame(pFrame(3))

        pipeline.drainAll()

        assertEquals(listOf("RESET", "SPS", "IDR", "P"), sink.events.toList())
    }

    @Test
    fun codecConfigIsAcceptedWhileWaitingForKeyframe() {
        val sink = RecordingSink()
        val pipeline = DecoderPipeline(sink)
        pipeline.submitFrame(sps(1))
        pipeline.submitFrame(idr(2))

        while (pipeline.drainOne()) { /* drain synchronously */ }

        assertEquals(listOf("SPS", "IDR"), sink.events.toList())
    }

    @Test
    fun idrAndPFramesAreDroppedUntilCodecConfigIsProcessedIncludingAfterResetAndReconfigure() {
        val sink = RecordingSink()
        val pipeline = DecoderPipeline(sink)

        // Before any CSD: IDR and P-frame must be dropped.
        pipeline.submitFrame(idr(1))
        pipeline.submitFrame(pFrame(2))
        pipeline.drainAll()
        assertEquals(emptyList<String>(), sink.events.toList())
        assertFalse(pipeline.hasCodecConfig)
        assertTrue(pipeline.awaitingKeyframe)

        // Rejected CSD must keep the gate closed.
        sink.acceptFrames = false
        pipeline.submitFrame(sps(3))
        pipeline.submitFrame(idr(4))
        pipeline.drainAll()
        assertEquals(listOf("SPS"), sink.events.toList())
        assertFalse(pipeline.hasCodecConfig)
        assertTrue(pipeline.awaitingKeyframe)

        // Valid CSD + IDR + P-frame opens the gate.
        sink.acceptFrames = true
        pipeline.submitFrame(sps(5))
        pipeline.submitFrame(idr(6))
        pipeline.submitFrame(pFrame(7))
        pipeline.drainAll()
        assertTrue(pipeline.hasCodecConfig)
        assertFalse(pipeline.awaitingKeyframe)

        // Reset closes both hasCodecConfig and awaitingKeyframe until fresh CSD + IDR.
        pipeline.submitReset()
        pipeline.submitFrame(idr(8))
        pipeline.submitFrame(pFrame(9))
        pipeline.submitFrame(sps(10))
        pipeline.submitFrame(idr(11))
        pipeline.drainAll()

        // Reconfigure closes both hasCodecConfig and awaitingKeyframe until fresh CSD + IDR.
        pipeline.submitConfig(StreamConfig(width = 1280, height = 720))
        pipeline.submitFrame(idr(12))
        pipeline.submitFrame(pFrame(13))
        pipeline.submitFrame(sps(14))
        pipeline.submitFrame(idr(15))
        pipeline.submitFrame(pFrame(16))
        pipeline.drainAll()

        assertEquals(
            listOf(
                "SPS",
                "SPS", "IDR", "P",
                "RESET", "SPS", "IDR",
                "RECONFIG", "SPS", "IDR", "P"
            ),
            sink.events.toList()
        )
    }

    @Test
    fun combinedCodecConfigAndKeyframeUnlocksGateAndAllowsFollowingPFrames() {
        val sink = RecordingSink()
        val pipeline = DecoderPipeline(sink)

        pipeline.submitFrame(
            DecoderCommand.Frame(
                data = byteArrayOf(0, 0, 0, 1, 0x67, 0, 0, 0, 1, 0x68, 0, 0, 0, 1, 0x65),
                offset = 0,
                length = 15,
                timestampNano = 1,
                isGamingMode = true,
                isCodecConfig = true,
                isKeyframe = true
            )
        )
        pipeline.submitFrame(pFrame(2))
        pipeline.drainAll()

        assertTrue(pipeline.hasCodecConfig)
        assertFalse(pipeline.awaitingKeyframe)
        assertEquals(listOf("SPS", "P"), sink.events.toList())
    }

    @Test
    fun failedDecodeReentersKeyframeWait() {
        val sink = RecordingSink()
        val pipeline = DecoderPipeline(sink)
        pipeline.submitFrame(sps(0)); pipeline.drainAll()
        pipeline.submitFrame(idr(1)); pipeline.drainAll()
        pipeline.submitFrame(pFrame(2)); pipeline.drainAll()

        sink.acceptFrames = false
        pipeline.submitFrame(idr(3)); pipeline.drainAll()
        pipeline.submitFrame(pFrame(4)); pipeline.drainAll()

        sink.acceptFrames = true
        pipeline.submitFrame(idr(5)); pipeline.drainAll()
        pipeline.submitFrame(pFrame(6)); pipeline.drainAll()

        assertEquals(listOf("SPS", "IDR", "P", "IDR", "IDR", "P"), sink.events.toList())
    }

    @Test
    fun configFlushesPipelineIntoKeyframeWait() {
        val sink = RecordingSink()
        val pipeline = DecoderPipeline(sink)
        pipeline.submitFrame(sps(0)); pipeline.drainAll()
        pipeline.submitFrame(idr(1)); pipeline.drainAll()
        pipeline.submitFrame(pFrame(2)); pipeline.drainAll()
        pipeline.submitConfig(StreamConfig(width = 1280, height = 720))
        pipeline.submitFrame(pFrame(3))
        pipeline.submitFrame(sps(4))
        pipeline.submitFrame(idr(5))
        pipeline.drainAll()

        assertEquals(listOf("SPS", "IDR", "P", "RECONFIG", "SPS", "IDR"), sink.events.toList())
    }

    @Test
    fun stopAlwaysReleasesSinkOnce() {
        val sink = RecordingSink()
        val pipeline = DecoderPipeline(sink)
        pipeline.stop()
        pipeline.stop()

        assertEquals(listOf("STOP"), sink.events.toList())
        assertFalse(pipeline.isRunning)
    }

    private fun DecoderPipeline.drainAll() {
        while (drainOne()) { /* drain synchronously */ }
    }

    private fun sps(timestamp: Long) = DecoderCommand.Frame(
        data = byteArrayOf(0, 0, 0, 1, 0x67),
        offset = 0,
        length = 5,
        timestampNano = timestamp,
        isGamingMode = true,
        isCodecConfig = true
    )

    private fun idr(timestamp: Long) = DecoderCommand.Frame(
        data = byteArrayOf(0, 0, 0, 1, 0x65),
        offset = 0,
        length = 5,
        timestampNano = timestamp,
        isGamingMode = true,
        isKeyframe = true
    )

    private fun pFrame(timestamp: Long) = DecoderCommand.Frame(
        data = byteArrayOf(0, 0, 0, 1, 0x41),
        offset = 0,
        length = 5,
        timestampNano = timestamp,
        isGamingMode = true
    )
}
