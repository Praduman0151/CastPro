package com.castpro.core.streaming.transport

import android.os.SystemClock
import android.util.Log
import com.castpro.core.common.protocol.Constants
import com.castpro.core.common.protocol.PacketHeader
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * A TCP packet sink that keeps trying to reach the TV.
 *
 * Writes happen on a dedicated thread. The encoder and audio threads only
 * enqueue packets: a socket that vanished without a FIN used to freeze the
 * encoder thread until TCP gave up. Stream config, SPS/PPS, geometry and
 * heartbeats have their own bounded queues and are always written before
 * media, so media backpressure cannot evict them. [send] reports queue
 * acceptance; [counters] reports what was actually written and dropped.
 *
 * The v2.1.1 bounded-write guarantee is kept: every write runs through the
 * non-blocking SocketChannel/Selector path with a hard deadline, so even the
 * writer thread can never be held hostage by a stalled receiver.
 */
class TcpStreamTransport(
    val host: String,
    val port: Int,
    private val onConnectionChanged: (connected: Boolean, host: String?, error: String?) -> Unit = { _, _, _ -> }
) : StreamTransport {
    private val tag = "TcpStreamTransport"
    private val running = AtomicBoolean(false)
    private val connecting = AtomicBoolean(false)
    private val writeLock = Any()
    private val stateLock = Any()
    private val selector: Selector = Selector.open()
    private val packets = PacketPriorityQueue()

    private val queuedPackets = AtomicLong(0)
    private val writtenPackets = AtomicLong(0)
    private val writtenBytes = AtomicLong(0)
    private val writtenVideoFrames = AtomicLong(0)
    private val writtenVideoBytes = AtomicLong(0)
    private val failedPackets = AtomicLong(0)

    @Volatile private var channel: SocketChannel? = null
    @Volatile private var lastWriteSuccessMs = SystemClock.elapsedRealtime()
    @Volatile private var lastReportedConnected: Boolean? = null
    @Volatile private var lastReportedError: String? = null
    private var reconnectThread: Thread? = null
    private var writerThread: Thread? = null

    override val isConnected: Boolean
        get() = channel?.let { it.isConnected && it.isOpen } ?: false

    override val counters: TransportCounters
        get() = TransportCounters(
            queuedPackets = queuedPackets.get(),
            writtenPackets = writtenPackets.get(),
            writtenBytes = writtenBytes.get(),
            droppedPackets = failedPackets.get() + packets.droppedPackets,
            pendingPackets = packets.pending,
            writtenVideoFrames = writtenVideoFrames.get(),
            writtenVideoBytes = writtenVideoBytes.get()
        )

    override fun start() {
        if (!running.compareAndSet(false, true)) return
        writerThread = Thread({ writeLoop() }, "CastPro-Sender").apply {
            isDaemon = true
            start()
        }
        // The service owns the user-facing initial status. Reporting an
        // immediate false here would briefly show "Disconnected" while the
        // first connect() call is in flight.
        reconnectThread = Thread({ reconnectLoop() }, "CastPro-Reconnect").apply {
            isDaemon = true
            start()
        }
    }

    private fun reconnectLoop() {
        while (running.get()) {
            if (!isConnected) connectOnce()

            // A peer can disappear without a FIN (Wi-Fi handover). Writes then
            // fail only after the bounded deadline; a full queue with no
            // successful write for a few seconds means the link is dead, so
            // close the socket and let the next iteration reconnect.
            val stalled = isConnected && packets.pending > 0 &&
                SystemClock.elapsedRealtime() - lastWriteSuccessMs > WRITE_STALL_TIMEOUT_MS
            if (stalled) {
                Log.w(tag, "No successful write for ${WRITE_STALL_TIMEOUT_MS}ms; reconnecting")
                dropCurrentSocket("Connection timed out")
            }

            try {
                Thread.sleep(if (isConnected) 250L else Constants.RECONNECT_DELAY_MS)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun connectOnce() {
        if (!running.get() || !connecting.compareAndSet(false, true)) return
        var candidate: SocketChannel? = null
        try {
            candidate = SocketChannel.open().apply {
                configureBlocking(false)
                socket().tcpNoDelay = true
                socket().sendBufferSize = 256 * 1024
            }
            if (!candidate.connect(InetSocketAddress(host, port))) {
                synchronized(writeLock) {
                    val key = candidate.register(selector, SelectionKey.OP_CONNECT)
                    if (selector.select(CONNECT_TIMEOUT_MS) == 0 || !key.isConnectable ||
                        !candidate.finishConnect()
                    ) {
                        key.cancel()
                        selector.selectNow()
                        throw java.net.ConnectException("Connect timed out")
                    }
                    key.cancel()
                    selector.selectNow()
                }
            }
            synchronized(writeLock) {
                // stop() may have run while connect() was blocked; never
                // publish a socket into a stopped transport.
                if (!running.get()) {
                    runCatching { candidate.close() }
                    return
                }
                channel = candidate
                lastWriteSuccessMs = SystemClock.elapsedRealtime()
            }
            reportConnected(true)
            Log.i(tag, "Connected to receiver at $host:$port")
        } catch (e: Exception) {
            runCatching { candidate?.close() }
            Log.d(tag, "Waiting for receiver $host:$port: ${e.message}")
            reportConnected(false, e.message)
        } finally {
            connecting.set(false)
        }
    }

    /**
     * Returns true only when the packet was accepted into the send queue.
     * A false result means the caller must count the frame as dropped; a true
     * result means it is pending, not that the socket has written it yet.
     */
    override fun send(header: PacketHeader, payload: ByteArray): Boolean {
        if (!running.get() || !isConnected) return false
        val headerBuffer = ByteBuffer.allocate(PacketHeader.HEADER_SIZE)
        header.writeTo(headerBuffer)
        val frame = ByteArray(PacketHeader.HEADER_SIZE + payload.size)
        System.arraycopy(headerBuffer.array(), 0, frame, 0, PacketHeader.HEADER_SIZE)
        System.arraycopy(payload, 0, frame, PacketHeader.HEADER_SIZE, payload.size)

        val accepted = packets.offer(frame, priorityOf(header))
        if (accepted) queuedPackets.incrementAndGet() else failedPackets.incrementAndGet()
        return accepted
    }

    private fun writeLoop() {
        while (running.get()) {
            val frame = packets.poll(250) ?: continue
            val active = channel
            if (active == null) {
                // The socket died between enqueue and dequeue; this packet
                // belongs to the dead connection.
                failedPackets.incrementAndGet()
                continue
            }
            try {
                // Bounded write: a stalled receiver must never block the
                // sender thread (or the selector) forever.
                synchronized(writeLock) {
                    writeFully(active, ByteBuffer.wrap(frame))
                }
                writtenPackets.incrementAndGet()
                writtenBytes.addAndGet(frame.size.toLong())
                if (isVideoFrame(frame)) {
                    writtenVideoFrames.incrementAndGet()
                    writtenVideoBytes.addAndGet((frame.size - PacketHeader.HEADER_SIZE).toLong())
                }
                lastWriteSuccessMs = SystemClock.elapsedRealtime()
            } catch (e: Exception) {
                Log.w(tag, "Send failed, dropping connection", e)
                failedPackets.incrementAndGet()
                dropCurrentSocket(e.message ?: "Connection lost")
            }
        }
    }

    /** Returns false only after the write deadline expires; never blocks forever. */
    private fun writeFully(active: SocketChannel, buffer: ByteBuffer) {
        val key = active.register(selector, SelectionKey.OP_WRITE)
        val deadlineMs = SystemClock.elapsedRealtime() + WRITE_TIMEOUT_MS
        try {
            while (buffer.hasRemaining()) {
                if (active.write(buffer) > 0) continue
                val remaining = deadlineMs - SystemClock.elapsedRealtime()
                if (remaining <= 0L) throw java.io.IOException("TCP write timed out")
                selector.select(remaining)
                selector.selectedKeys().clear()
            }
        } finally {
            key.cancel()
            selector.selectNow()
        }
    }

    /** Header layout: magic(2), type(1), flags(1). Codec config is not a frame. */
    private fun isVideoFrame(frame: ByteArray): Boolean {
        if (frame.size <= PacketHeader.HEADER_SIZE) return false
        val flags = frame[3].toInt() and 0xFF
        return frame[2] == Constants.TYPE_VIDEO_NAL &&
            (flags and Constants.FLAG_CODEC_CONFIG.toInt()) == 0
    }

    private fun dropCurrentSocket(reason: String?) {
        synchronized(writeLock) {
            runCatching { channel?.close() }
            channel = null
        }
        // Anything still queued belongs to the dead connection. Sending it on
        // the new socket would burst stale frames ahead of the fresh config
        // and IDR the receiver needs to start.
        packets.clear()
        reportConnected(false, reason)
    }

    private fun reportConnected(connected: Boolean, error: String? = null) {
        synchronized(stateLock) {
            if (lastReportedConnected == connected && lastReportedError == error) return
            lastReportedConnected = connected
            lastReportedError = error
        }
        onConnectionChanged(connected, host, error)
    }

    override fun stop() {
        running.set(false)
        dropCurrentSocket("Stream stopped")
        packets.clear()
        reconnectThread?.interrupt()
        reconnectThread = null
        writerThread?.interrupt()
        writerThread = null
        runCatching {
            selector.wakeup()
            selector.close()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 2_500L
        const val WRITE_TIMEOUT_MS = 500L
        /** Long enough for a congested LAN burst, short enough to recover. */
        const val WRITE_STALL_TIMEOUT_MS = 5_000L
    }
}
