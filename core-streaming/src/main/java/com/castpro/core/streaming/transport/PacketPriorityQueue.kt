package com.castpro.core.streaming.transport

import com.castpro.core.common.protocol.Constants
import com.castpro.core.common.protocol.PacketHeader
import java.util.ArrayDeque
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** How urgently a packet must reach the receiver. */
enum class PacketPriority {
    /** Stream config and parameter sets: without these the decoder cannot start. */
    CRITICAL,
    /** Heartbeats and geometry: cheap, and needed for calibration/aspect. */
    CONTROL,
    /** Video and audio: late frames are worthless in a live cast. */
    MEDIA
}

fun priorityOf(header: PacketHeader): PacketPriority = when {
    header.type == Constants.TYPE_STREAM_CONFIG || header.type == Constants.TYPE_HANDSHAKE -> PacketPriority.CRITICAL
    header.type == Constants.TYPE_VIDEO_NAL &&
        (header.flags.toInt() and Constants.FLAG_CODEC_CONFIG.toInt()) != 0 -> PacketPriority.CRITICAL
    header.type == Constants.TYPE_GEOMETRY || header.type == Constants.TYPE_HEARTBEAT -> PacketPriority.CONTROL
    else -> PacketPriority.MEDIA
}

data class PacketQueueSizes(val critical: Int, val control: Int, val media: Int)

/**
 * Bounded, priority-ordered packet queue for the TCP sender.
 *
 * Media overflow drops the oldest media packet. It can never evict a control or
 * config packet: those live in separate, smaller queues and are always drained
 * first. Control/config queues are bounded too, so a misbehaving producer cannot
 * grow the process without limit.
 */
class PacketPriorityQueue(
    private val criticalCapacity: Int = DEFAULT_CRITICAL_CAPACITY,
    private val controlCapacity: Int = DEFAULT_CONTROL_CAPACITY,
    private val mediaCapacity: Int = DEFAULT_MEDIA_CAPACITY
) {
    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()
    private val critical = ArrayDeque<ByteArray>()
    private val control = ArrayDeque<ByteArray>()
    private val media = ArrayDeque<ByteArray>()
    private var dropped = 0L

    fun offer(frame: ByteArray, priority: PacketPriority): Boolean = lock.withLock {
        val queue = when (priority) {
            PacketPriority.CRITICAL -> critical
            PacketPriority.CONTROL -> control
            PacketPriority.MEDIA -> media
        }
        val capacity = when (priority) {
            PacketPriority.CRITICAL -> criticalCapacity
            PacketPriority.CONTROL -> controlCapacity
            PacketPriority.MEDIA -> mediaCapacity
        }
        if (capacity <= 0) {
            dropped++
            return false
        }
        while (queue.size >= capacity) {
            queue.pollFirst()
            dropped++
        }
        queue.addLast(frame)
        notEmpty.signalAll()
        true
    }

    /** Blocks up to [waitMs] for a packet, always taking the highest priority one. */
    fun poll(waitMs: Long): ByteArray? = lock.withLock {
        var remaining = maxOf(0L, waitMs) * 1_000_000L
        while (true) {
            val frame = pollNowLocked()
            if (frame != null) return@withLock frame
            if (remaining <= 0) return@withLock null
            try {
                remaining = notEmpty.awaitNanos(remaining)
            } catch (_: InterruptedException) {
                return@withLock null
            }
        }
        null
    }

    fun pollNow(): ByteArray? = lock.withLock { pollNowLocked() }

    private fun pollNowLocked(): ByteArray? =
        critical.pollFirst() ?: control.pollFirst() ?: media.pollFirst()

    /** Drops queued media packets (used on live config change); returns the count dropped. */
    fun clearMedia(): Int = lock.withLock {
        val count = media.size
        dropped += count.toLong()
        media.clear()
        notEmpty.signalAll()
        count
    }

    /** Drops every queued packet (used when the socket dies); returns the count dropped. */
    fun clear(): Int = lock.withLock {
        val count = critical.size + control.size + media.size
        dropped += count.toLong()
        critical.clear()
        control.clear()
        media.clear()
        notEmpty.signalAll()
        count
    }

    fun sizes(): PacketQueueSizes = lock.withLock {
        PacketQueueSizes(critical.size, control.size, media.size)
    }

    val pending: Int
        get() = lock.withLock { critical.size + control.size + media.size }

    val droppedPackets: Long
        get() = lock.withLock { dropped }

    companion object {
        const val DEFAULT_CRITICAL_CAPACITY = 16
        const val DEFAULT_CONTROL_CAPACITY = 16
        const val DEFAULT_MEDIA_CAPACITY = 120
    }
}
