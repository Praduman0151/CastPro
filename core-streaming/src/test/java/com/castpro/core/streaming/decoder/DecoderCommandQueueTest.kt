package com.castpro.core.streaming.decoder

import com.castpro.core.common.model.StreamConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DecoderCommandQueueTest {

    @Test
    fun boundedFrameQueueDropsOldestFrame() {
        val queue = DecoderCommandQueue(frameCapacity = 2)
        queue.offer(frame(1))
        queue.offer(frame(2))
        queue.offer(frame(3))

        assertEquals(2, queue.size)
        assertEquals(2L, (queue.pollNow() as DecoderCommand.Frame).timestampNano)
        assertEquals(3L, (queue.pollNow() as DecoderCommand.Frame).timestampNano)
        assertEquals(null, queue.pollNow())
    }

    @Test
    fun configClearsStaleFramesAndKeepsNewestConfig() {
        val queue = DecoderCommandQueue(frameCapacity = 4)
        queue.offer(frame(1))
        queue.offer(DecoderCommand.Config(StreamConfig(width = 1280, height = 720)))
        queue.offer(frame(2))
        queue.offer(DecoderCommand.Config(StreamConfig(width = 1920, height = 1080)))
        queue.offer(frame(3))

        val config = queue.pollNow() as DecoderCommand.Config
        assertEquals(1920, config.config.width)
        assertEquals(1080, config.config.height)
        // Only frames enqueued after the newest config survive.
        assertEquals(3L, (queue.pollNow() as DecoderCommand.Frame).timestampNano)
        assertTrue(queue.pollNow() == null)
    }

    @Test
    fun resetIsDeliveredBeforeQueuedFrames() {
        val queue = DecoderCommandQueue()
        queue.offer(frame(1))
        queue.offer(DecoderCommand.Reset)

        assertTrue(queue.pollNow() is DecoderCommand.Reset)
        assertEquals(1L, (queue.pollNow() as DecoderCommand.Frame).timestampNano)
    }

    private fun frame(timestamp: Long) = DecoderCommand.Frame(
        data = byteArrayOf(1, 2, 3),
        offset = 0,
        length = 3,
        timestampNano = timestamp,
        isGamingMode = true
    )
}
