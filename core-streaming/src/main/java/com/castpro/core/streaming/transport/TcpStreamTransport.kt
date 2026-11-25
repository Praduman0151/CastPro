package com.castpro.core.streaming.transport

import android.util.Log
import com.castpro.core.common.protocol.Constants
import com.castpro.core.common.protocol.PacketHeader
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A TCP packet sink that keeps trying to reach the TV.
 *
 * A failed write used to take the whole capture pipeline down. Now the encoder
 * simply drops frames while the TV is away, a background thread reconnects, and
 * the service asks for a fresh keyframe as soon as the link is back. Writes are
 * bounded: a stalled receiver must never block the encoder's output drain
 * forever, which previously froze the native pipeline after a few frames.
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

    @Volatile private var channel: SocketChannel? = null
    @Volatile private var lastReportedConnected: Boolean? = null
    @Volatile private var lastReportedError: String? = null
    private var reconnectThread: Thread? = null

    override val isConnected: Boolean
        get() = channel?.let { it.isConnected && it.isOpen } ?: false

    override fun start() {
        if (!running.compareAndSet(false, true)) return
        // The service owns the user-facing initial status. Reporting an
        // immediate false here would briefly show "Disconnected" while the
        // first connect() call is in flight.
        reconnectThread = Thread({
            while (running.get()) {
                if (!isConnected) connectOnce()
                if (!isConnected) {
                    try {
                        Thread.sleep(Constants.RECONNECT_DELAY_MS)
                    } catch (_: InterruptedException) {
                        break
                    }
                } else {
                    // Wake up regularly so a silently dead socket is noticed by
                    // the next write, and so stop() is responsive.
                    try {
                        Thread.sleep(250)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        }, "CastPro-Reconnect").apply {
            isDaemon = true
            start()
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
                channel = candidate
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

    override fun send(header: PacketHeader, payload: ByteArray): Boolean {
        val active = channel ?: return false
        val headerBytes = ByteBuffer.allocate(PacketHeader.HEADER_SIZE).also { header.writeTo(it) }.array()
        return synchronized(writeLock) {
            try {
                writeFully(active, ByteBuffer.wrap(headerBytes))
                writeFully(active, ByteBuffer.wrap(payload))
                true
            } catch (e: Exception) {
                Log.w(tag, "Send failed, dropping connection", e)
                closeSocket(e.message ?: "Connection lost")
                false
            }
        }
    }

    /** Returns false only after the write deadline expires; never blocks forever. */
    private fun writeFully(active: SocketChannel, buffer: ByteBuffer) {
        val key = active.register(selector, SelectionKey.OP_WRITE)
        val deadlineMs = System.currentTimeMillis() + WRITE_TIMEOUT_MS
        try {
            while (buffer.hasRemaining()) {
                if (active.write(buffer) > 0) continue
                val remaining = deadlineMs - System.currentTimeMillis()
                if (remaining <= 0L) throw java.io.IOException("TCP write timed out")
                selector.select(remaining)
                selector.selectedKeys().clear()
            }
        } finally {
            key.cancel()
            selector.selectNow()
        }
    }

    private fun closeSocket(reason: String?) {
        synchronized(writeLock) {
            runCatching { channel?.close() }
            channel = null
        }
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
        closeSocket("Stream stopped")
        reconnectThread?.interrupt()
        reconnectThread = null
        runCatching { selector.close() }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 2_500L
        const val WRITE_TIMEOUT_MS = 500L
    }
}
