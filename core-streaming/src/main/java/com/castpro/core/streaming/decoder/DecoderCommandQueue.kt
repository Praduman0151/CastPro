package com.castpro.core.streaming.decoder

import com.castpro.core.common.model.StreamConfig
import java.util.ArrayDeque
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Work handed from the TCP reader thread to the dedicated decoder thread. */
sealed interface DecoderCommand {
    data class Frame(
        val data: ByteArray,
        val offset: Int,
        val length: Int,
        val timestampNano: Long,
        val isGamingMode: Boolean,
        val isCodecConfig: Boolean = false,
        val isKeyframe: Boolean = false
    ) : DecoderCommand

    data class Config(val config: StreamConfig) : DecoderCommand
    data object Reset : DecoderCommand
    data object Stop : DecoderCommand
}

/**
 * Bounded hand-off queue between the network thread and the decoder thread.
 *
 * Frames are lossy: when the decoder falls behind, only the newest frames are
 * worth keeping. A stream config replaces any older config and invalidates
 * frames from the previous session; it is never evicted by media.
 */
class DecoderCommandQueue(
    private val frameCapacity: Int = DEFAULT_FRAME_CAPACITY
) {
    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()
    private val frames = ArrayDeque<DecoderCommand.Frame>()
    private var pendingConfig: StreamConfig? = null
    private var pendingReset = false
    private var stopPending = false
    private var droppedFrames = 0L

    fun offer(command: DecoderCommand): Boolean = lock.withLock {
        when (command) {
            is DecoderCommand.Frame -> {
                if (frameCapacity <= 0) {
                    droppedFrames++
                    return@withLock false
                }
                while (frames.size >= frameCapacity) {
                    val evictable = frames.firstOrNull { !it.isCodecConfig && !it.isKeyframe }
                    if (evictable != null) {
                        frames.remove(evictable)
                        droppedFrames++
                    } else if (!command.isCodecConfig && !command.isKeyframe) {
                        // Never evict queued CSD or keyframes for an overflowing P-frame.
                        droppedFrames++
                        notEmpty.signalAll()
                        return@withLock false
                    } else {
                        frames.pollFirst()
                        droppedFrames++
                    }
                }
                frames.addLast(command)
            }
            is DecoderCommand.Config -> {
                // Frames decoded with the previous configuration are stale and
                // may be corrupt; the sender will follow the config with a
                // fresh IDR.
                droppedFrames += frames.size
                frames.clear()
                pendingConfig = command.config
            }
            DecoderCommand.Stop -> stopPending = true
            DecoderCommand.Reset -> pendingReset = true
        }
        notEmpty.signalAll()
        true
    }

    /** Blocks up to [waitMs] for work; allows the decoder thread to stop. */
    fun poll(waitMs: Long): DecoderCommand? = lock.withLock {
        var remaining = maxOf(0L, waitMs) * 1_000_000L
        while (true) {
            val command = pollNowLocked()
            if (command != null) return@withLock command
            if (remaining <= 0) return@withLock null
            try {
                remaining = notEmpty.awaitNanos(remaining)
            } catch (_: InterruptedException) {
                return@withLock null
            }
        }
        null
    }

    fun pollNow(): DecoderCommand? = lock.withLock { pollNowLocked() }

    private fun pollNowLocked(): DecoderCommand? = when {
        stopPending -> {
            stopPending = false
            DecoderCommand.Stop
        }
        pendingConfig != null -> {
            val config = pendingConfig!!
            pendingConfig = null
            DecoderCommand.Config(config)
        }
        pendingReset -> {
            pendingReset = false
            DecoderCommand.Reset
        }
        else -> frames.pollFirst()
    }

    /** Discards frames from a dead connection without touching pending config. */
    fun clearFrames(): Int = lock.withLock {
        val count = frames.size
        droppedFrames += count.toLong()
        frames.clear()
        count
    }

    val size: Int
        get() = lock.withLock {
            frames.size + (if (pendingConfig != null) 1 else 0) + (if (pendingReset) 1 else 0)
        }

    val droppedPackets: Long
        get() = lock.withLock { droppedFrames }

    companion object {
        /** 2 seconds at 60 fps: enough for jitter, short enough to stay live. */
        const val DEFAULT_FRAME_CAPACITY = 120
    }
}
