package com.castpro.core.streaming.transport

import android.os.Process
import com.castpro.core.common.protocol.Constants
import com.castpro.core.common.protocol.PacketHeader
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Serializes TCP writes away from MediaCodec's output-drain thread.
 *
 * The queue is intentionally shallow: when overloaded, stale non-reference
 * frames may be discarded, but codec configuration and IDR packets are
 * protected from eviction.
 */
class AsyncFrameSender(
    private val transport: StreamTransport,
    private val onRequestKeyFrame: () -> Unit,
    private val onStats: (delivered: Boolean, bytes: Int) -> Unit,
    private val onCodecConfigStats: (delivered: Boolean, bytes: Int) -> Unit = { _, _ -> }
) {
    private data class QueuedPacket(
        val header: PacketHeader,
        val payload: ByteArray,
        val isProtected: Boolean,
        val isCodecConfig: Boolean
    )

    // Two queued frames cap latency growth to roughly 33 ms at 60 fps.
    private val queue = ArrayBlockingQueue<QueuedPacket>(2)
    private val running = AtomicBoolean(false)
    private var senderThread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        senderThread = Thread({
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY) }
            while (running.get()) {
                val packet = try {
                    queue.take()
                } catch (_: InterruptedException) {
                    break
                }
                val delivered = runCatching {
                    transport.send(packet.header, packet.payload)
                }.getOrDefault(false)
                reportStats(packet, delivered)
            }
        }, "CastPro-TcpSender").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Only one producer may apply the overflow policy at a time. The consumer
     * remains independent and can always remove packets while this is running.
     */
    @Synchronized
    fun enqueue(header: PacketHeader, payload: ByteArray) {
        val flags = header.flags.toInt()
        val isCodecConfig = (flags and Constants.FLAG_CODEC_CONFIG.toInt()) != 0
        val isProtected = isCodecConfig ||
            (flags and Constants.FLAG_KEYFRAME.toInt()) != 0
        val packet = QueuedPacket(header, payload, isProtected, isCodecConfig)

        if (!queue.offer(packet)) {
            // Remove the oldest unprotected packet in place. Polling and then
            // re-offering a protected head packet would move it behind a queued
            // IDR, violating the required CSD-before-IDR wire order.
            val stale = queue.firstOrNull { !it.isProtected }
            if (stale != null && queue.remove(stale)) {
                reportStats(stale, delivered = false)
                runCatching { onRequestKeyFrame() }
            }
            if (!queue.offer(packet)) {
                reportStats(packet, delivered = false)
            }
        }
    }

    private fun reportStats(packet: QueuedPacket, delivered: Boolean) {
        if (packet.isCodecConfig) {
            onCodecConfigStats(delivered, packet.payload.size)
        } else {
            onStats(delivered, packet.payload.size)
        }
    }

    fun stop() {
        running.set(false)
        senderThread?.interrupt()
        queue.clear()
        senderThread = null
    }
}
