package com.castpro.core.streaming.transport

import com.castpro.core.common.protocol.Constants
import com.castpro.core.common.protocol.PacketHeader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketPriorityQueueTest {

    @Test
    fun mediaOverflowNeverEvictsCriticalPackets() {
        val queue = PacketPriorityQueue(criticalCapacity = 2, controlCapacity = 2, mediaCapacity = 2)
        val config = byteArrayOf(9, 9, 9)
        assertTrue(queue.offer(config, PacketPriority.CRITICAL))

        repeat(50) { queue.offer(byteArrayOf(it.toByte()), PacketPriority.MEDIA) }

        assertEquals(1, queue.sizes().critical)
        assertEquals(2, queue.sizes().media)
        assertArrayEquals(config, queue.pollNow())
    }

    @Test
    fun mediaOverflowDropsOldestMediaFirst() {
        val queue = PacketPriorityQueue(mediaCapacity = 2)
        queue.offer(byteArrayOf(1), PacketPriority.MEDIA)
        queue.offer(byteArrayOf(2), PacketPriority.MEDIA)
        queue.offer(byteArrayOf(3), PacketPriority.MEDIA)

        assertArrayEquals(byteArrayOf(2), queue.pollNow())
        assertArrayEquals(byteArrayOf(3), queue.pollNow())
        assertEquals(null, queue.pollNow())
    }

    @Test
    fun pollOrderIsCriticalThenControlThenMedia() {
        val queue = PacketPriorityQueue()
        queue.offer(byteArrayOf(3), PacketPriority.MEDIA)
        queue.offer(byteArrayOf(2), PacketPriority.CONTROL)
        queue.offer(byteArrayOf(1), PacketPriority.CRITICAL)

        assertArrayEquals(byteArrayOf(1), queue.pollNow())
        assertArrayEquals(byteArrayOf(2), queue.pollNow())
        assertArrayEquals(byteArrayOf(3), queue.pollNow())
    }

    @Test
    fun evictionsAndClearAreCountedAsDropped() {
        val queue = PacketPriorityQueue(mediaCapacity = 1)
        queue.offer(byteArrayOf(1), PacketPriority.MEDIA)
        queue.offer(byteArrayOf(2), PacketPriority.MEDIA)
        assertEquals(1L, queue.droppedPackets)

        assertEquals(1, queue.clear())
        assertEquals(2L, queue.droppedPackets)
        assertEquals(0, queue.pending)
    }

    @Test
    fun priorityOfClassifiesControlAndMediaPackets() {
        val codecConfig = PacketHeader(
            type = Constants.TYPE_VIDEO_NAL,
            flags = Constants.FLAG_CODEC_CONFIG,
            sequenceNumber = 1,
            timestampNano = 1L,
            payloadLength = 4
        )
        assertEquals(PacketPriority.CRITICAL, priorityOf(codecConfig.copy(type = Constants.TYPE_STREAM_CONFIG)))
        assertEquals(PacketPriority.CRITICAL, priorityOf(codecConfig))
        assertEquals(PacketPriority.CONTROL, priorityOf(codecConfig.copy(type = Constants.TYPE_HEARTBEAT, flags = 0)))
        assertEquals(PacketPriority.CONTROL, priorityOf(codecConfig.copy(type = Constants.TYPE_GEOMETRY, flags = 0)))
        assertEquals(PacketPriority.MEDIA, priorityOf(codecConfig.copy(flags = 0)))
        assertEquals(PacketPriority.MEDIA, priorityOf(codecConfig.copy(type = Constants.TYPE_AUDIO_DATA, flags = 0)))
    }
}
