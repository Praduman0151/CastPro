package com.castpro.core.streaming.transport

import com.castpro.core.common.protocol.Constants
import com.castpro.core.common.protocol.PacketHeader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AsyncFrameSenderTest {
    private class BlockingTransport : StreamTransport {
        override val isConnected: Boolean = true
        val firstSendEntered = CountDownLatch(1)
        val releaseFirstSend = CountDownLatch(1)
        val completedSends = CountDownLatch(3)
        val sentSequences = CopyOnWriteArrayList<Int>()

        override fun start() = Unit

        override fun send(header: PacketHeader, payload: ByteArray): Boolean {
            if (header.sequenceNumber == 1) {
                firstSendEntered.countDown()
                releaseFirstSend.await(3, TimeUnit.SECONDS)
            }
            sentSequences += header.sequenceNumber
            completedSends.countDown()
            return true
        }

        override fun stop() = Unit
    }

    private fun packet(sequence: Int, flags: Byte = 0) = PacketHeader(
        type = Constants.TYPE_VIDEO_NAL,
        flags = flags,
        sequenceNumber = sequence,
        timestampNano = sequence.toLong(),
        payloadLength = 1
    )

    @Test
    fun protectedCsdAndIdrAreNotEvictedWhenQueueIsFull() {
        val transport = BlockingTransport()
        val frameStats = CopyOnWriteArrayList<Pair<Boolean, Int>>()
        val frameStatsReady = CountDownLatch(3)
        val keyFrameRequests = AtomicInteger()
        val sender = AsyncFrameSender(
            transport = transport,
            onRequestKeyFrame = { keyFrameRequests.incrementAndGet() },
            onStats = { delivered, bytes ->
                frameStats += delivered to bytes
                frameStatsReady.countDown()
            }
        )
        sender.start()

        sender.enqueue(packet(1), byteArrayOf(1))
        assertTrue(transport.firstSendEntered.await(2, TimeUnit.SECONDS))

        sender.enqueue(packet(2, Constants.FLAG_CODEC_CONFIG), byteArrayOf(2, 2))
        sender.enqueue(packet(3, Constants.FLAG_KEYFRAME), byteArrayOf(3, 3, 3))
        // The queue now contains protected CSD + IDR; this P-frame must be rejected.
        sender.enqueue(packet(4), byteArrayOf(4, 4, 4, 4))

        transport.releaseFirstSend.countDown()
        assertTrue(transport.completedSends.await(2, TimeUnit.SECONDS))
        assertTrue(frameStatsReady.await(2, TimeUnit.SECONDS))
        sender.stop()

        assertEquals(listOf(1, 2, 3), transport.sentSequences.toList())
        assertEquals(listOf(false to 4), frameStats.filter { !it.first })
        assertEquals(0, keyFrameRequests.get())
    }

    @Test
    fun stalePFrameIsDroppedWhenQueueIsFull() {
        val transport = BlockingTransport()
        val frameStats = CopyOnWriteArrayList<Pair<Boolean, Int>>()
        val frameStatsReady = CountDownLatch(4)
        val keyFrameRequests = AtomicInteger()
        val sender = AsyncFrameSender(
            transport = transport,
            onRequestKeyFrame = { keyFrameRequests.incrementAndGet() },
            onStats = { delivered, bytes ->
                frameStats += delivered to bytes
                frameStatsReady.countDown()
            }
        )
        sender.start()

        sender.enqueue(packet(1), byteArrayOf(1))
        assertTrue(transport.firstSendEntered.await(2, TimeUnit.SECONDS))

        sender.enqueue(packet(2), byteArrayOf(2, 2))
        sender.enqueue(packet(3), byteArrayOf(3, 3, 3))
        sender.enqueue(packet(4), byteArrayOf(4, 4, 4, 4))

        transport.releaseFirstSend.countDown()
        assertTrue(transport.completedSends.await(2, TimeUnit.SECONDS))
        assertTrue(frameStatsReady.await(2, TimeUnit.SECONDS))
        sender.stop()

        assertEquals(listOf(1, 3, 4), transport.sentSequences.toList())
        assertEquals(listOf(false to 2), frameStats.filter { !it.first })
        assertEquals(1, keyFrameRequests.get())
    }
}
