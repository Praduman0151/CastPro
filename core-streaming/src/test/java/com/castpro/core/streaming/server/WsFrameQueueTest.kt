package com.castpro.core.streaming.server

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WsFrameQueueTest {

    @Test
    fun mediaOverflowNeverEvictsControlFrames() {
        val queue = WsFrameQueue(frameCapacity = 3)
        val control = byteArrayOf(9, 9, 9)
        assertTrue(queue.offer(control, droppable = false))

        repeat(100) { queue.offer(byteArrayOf(it.toByte(), 1, 2, 3), droppable = true) }

        assertArrayEquals(control, queue.pollNow())
        assertEquals(3, queue.pending)
        assertEquals(97L, queue.droppedFrames)
    }

    @Test
    fun mediaFramesAreBoundedAndOldestIsDroppedFirst() {
        val queue = WsFrameQueue(frameCapacity = 2)
        queue.offer(byteArrayOf(1), droppable = true)
        queue.offer(byteArrayOf(2), droppable = true)
        queue.offer(byteArrayOf(3), droppable = true)

        assertArrayEquals(byteArrayOf(2), queue.pollNow())
        assertArrayEquals(byteArrayOf(3), queue.pollNow())
        assertNull(queue.pollNow())
        assertEquals(1L, queue.droppedFrames)
    }

    @Test
    fun byteBudgetBoundsTotalQueueFootprint() {
        val queue = WsFrameQueue(frameCapacity = 100, maxBytes = 10)
        repeat(50) { queue.offer(ByteArray(4) { it.toByte() }, droppable = true) }

        assertTrue(queue.queuedBytes <= 10)
        assertTrue(queue.pending <= 3)
    }

    @Test
    fun clearCountsEverythingAndLeavesQueueEmpty() {
        val queue = WsFrameQueue()
        queue.offer(byteArrayOf(1), droppable = false)
        queue.offer(byteArrayOf(2), droppable = true)

        assertEquals(2, queue.clear())
        assertEquals(0, queue.pending)
        assertEquals(0, queue.queuedBytes)
        assertEquals(2L, queue.droppedFrames)
    }
}
