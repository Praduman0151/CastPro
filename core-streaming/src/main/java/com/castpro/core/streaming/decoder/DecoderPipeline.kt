package com.castpro.core.streaming.decoder

import com.castpro.core.common.model.StreamConfig
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Serialises all MediaCodec work on one dedicated thread.
 *
 * The TCP reader thread only copies packets into [queue]. A keyframe's input
 * buffer wait, a codec flush or a full reconfigure therefore can never stall
 * socket reads (and the sender's queue) during a game. P-frames that arrive
 * while the decoder is not reproducible are discarded; decoding resumes at the
 * next IDR rather than feeding corrupt reference frames to the codec.
 */
class DecoderPipeline(
    private val sink: VideoDecoderSink,
    internal val queue: DecoderCommandQueue = DecoderCommandQueue()
) {
    private val running = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    private val sinkStopped = AtomicBoolean(false)
    private var thread: Thread? = null

    /** Exposed for tests and diagnostics; false once the loop has exited. */
    @Volatile var isRunning: Boolean = false
        private set

    /** True while a keyframe is required before any P-frame may be decoded. */
    @Volatile var awaitingKeyframe: Boolean = true
        private set

    fun start() {
        if (!running.compareAndSet(false, true)) return
        isRunning = true
        thread = Thread({ runLoop() }, "CastPro-Decoder").apply {
            isDaemon = true
            start()
        }
    }

    private fun runLoop() {
        try {
            while (running.get()) {
                val command = queue.poll(POLL_WAIT_MS) ?: continue
                if (command === DecoderCommand.Stop) break
                handle(command)
            }
        } finally {
            isRunning = false
            stopSinkOnce()
        }
    }

    /** Drains one command; package-visible so tests can drive it deterministically. */
    internal fun drainOne(): Boolean {
        val command = queue.pollNow() ?: return false
        if (command !== DecoderCommand.Stop) handle(command)
        return true
    }

    private fun handle(command: DecoderCommand) {
        when (command) {
            is DecoderCommand.Config -> {
                awaitingKeyframe = true
                sink.reconfigure(command.config)
            }
            DecoderCommand.Reset -> {
                awaitingKeyframe = true
                sink.reset()
            }
            is DecoderCommand.Frame -> {
                if (awaitingKeyframe && !command.isCodecConfig && !command.isKeyframe) return
                if (!sink.decodeFrame(command)) {
                    // The codec could not accept the frame (no input buffer,
                    // oversized NAL, decode error). Its reference state is now
                    // unknown: only a fresh IDR can make P-frames safe again.
                    awaitingKeyframe = true
                    return
                }
                if (!command.isCodecConfig && command.isKeyframe) awaitingKeyframe = false
            }
            DecoderCommand.Stop -> Unit
        }
    }

    fun submitFrame(
        data: ByteArray,
        offset: Int,
        length: Int,
        timestampNano: Long,
        isGamingMode: Boolean,
        isCodecConfig: Boolean = false,
        isKeyframe: Boolean = false
    ) {
        submitFrame(
            DecoderCommand.Frame(
                data = data,
                offset = offset,
                length = length,
                timestampNano = timestampNano,
                isGamingMode = isGamingMode,
                isCodecConfig = isCodecConfig,
                isKeyframe = isKeyframe
            )
        )
    }

    fun submitFrame(frame: DecoderCommand.Frame) {
        // The socket buffer may be reused by the reader; the decoder thread can
        // consume this command much later.
        val copy = java.util.Arrays.copyOfRange(frame.data, frame.offset, frame.offset + frame.length)
        queue.offer(frame.copy(data = copy, offset = 0, length = copy.size))
    }

    fun submitConfig(config: StreamConfig) {
        queue.offer(DecoderCommand.Config(config))
    }

    fun submitReset() {
        queue.offer(DecoderCommand.Reset)
    }

    fun dropPendingFrames() {
        queue.clearFrames()
    }

    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        running.set(false)
        queue.offer(DecoderCommand.Stop)
        val current = thread
        if (current != null && current != Thread.currentThread()) {
            try {
                current.join(STOP_JOIN_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        } else {
            stopSinkOnce()
        }
        thread = null
        isRunning = false
    }

    private fun stopSinkOnce() {
        if (sinkStopped.compareAndSet(false, true)) sink.stop()
    }

    companion object {
        private const val POLL_WAIT_MS = 50L
        private const val STOP_JOIN_MS = 2_000L
    }
}
