package com.castpro.core.streaming.server

import java.util.ArrayDeque
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Per-browser send queue with protected control frames.
 *
 * WebSocket text frames ("connected", geometry) must never be evicted by a
 * burst of JPEG frames. They live in a separate bounded queue and are always
 * drained first. A byte budget bounds the total memory held for one slow
 * browser, so a viewer that stops reading cannot exhaust the phone.
 */
class WsFrameQueue(
    private val frameCapacity: Int = DEFAULT_FRAME_CAPACITY,
    private val maxBytes: Int = DEFAULT_MAX_BYTES
) {
    private val lock = ReentrantLock()
    private val control = ArrayDeque<ByteArray>()
    private val media = ArrayDeque<ByteArray>()
    private var bytes = 0
    private var dropped = 0L

    fun offer(frame: ByteArray, droppable: Boolean): Boolean = lock.withLock {
        if (frame.size > maxBytes) {
            dropped++
            return false
        }
        if (droppable) {
            while (media.size >= frameCapacity || (bytes + frame.size > maxBytes && media.isNotEmpty())) {
                dropFirst(media)
            }
            if (media.size >= frameCapacity || bytes + frame.size > maxBytes) {
                dropped++
                return false
            }
            media.addLast(frame)
        } else {
            // Make room by discarding stale video before stale control frames.
            while (bytes + frame.size > maxBytes && media.isNotEmpty()) dropFirst(media)
            while (control.size >= CONTROL_CAPACITY ||
                (bytes + frame.size > maxBytes && control.isNotEmpty())
            ) {
                dropFirst(control)
            }
            if (control.size >= CONTROL_CAPACITY || bytes + frame.size > maxBytes) {
                dropped++
                return false
            }
            control.addLast(frame)
        }
        bytes += frame.size
        true
    }

    fun pollNow(): ByteArray? = lock.withLock {
        val frame = control.pollFirst() ?: media.pollFirst()
        if (frame != null) bytes -= frame.size
        frame
    }

    fun clear(): Int = lock.withLock {
        val count = control.size + media.size
        dropped += count.toLong()
        control.clear()
        media.clear()
        bytes = 0
        count
    }

    private fun dropFirst(queue: ArrayDeque<ByteArray>) {
        val victim = queue.pollFirst() ?: return
        bytes -= victim.size
        dropped++
    }

    val pending: Int
        get() = lock.withLock { control.size + media.size }

    val queuedBytes: Int
        get() = lock.withLock { bytes }

    val droppedFrames: Long
        get() = lock.withLock { dropped }

    companion object {
        /** ~1 s of 60 fps JPEG frames. */
        const val DEFAULT_FRAME_CAPACITY = 90
        /** 8 MB per browser: several seconds of 720p JPEG at typical bitrates. */
        const val DEFAULT_MAX_BYTES = 8 * 1024 * 1024
        private const val CONTROL_CAPACITY = 16
    }
}
