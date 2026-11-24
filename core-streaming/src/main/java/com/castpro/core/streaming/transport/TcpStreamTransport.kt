package com.castpro.core.streaming.transport

import android.util.Log
import com.castpro.core.common.protocol.Constants
import com.castpro.core.common.protocol.PacketHeader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A TCP packet sink that keeps trying to reach the TV.
 *
 * A failed write used to take the whole capture pipeline down. Now the encoder
 * simply drops frames while the TV is away, a background thread reconnects, and
 * the service asks for a fresh keyframe as soon as the link is back.
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

    @Volatile private var socket: Socket? = null
    @Volatile private var output: OutputStream? = null
    @Volatile private var lastReportedConnected: Boolean? = null
    @Volatile private var lastReportedError: String? = null
    private var reconnectThread: Thread? = null

    override val isConnected: Boolean
        get() = output != null

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
        try {
            val candidate = Socket()
            candidate.tcpNoDelay = true
            candidate.sendBufferSize = 256 * 1024
            candidate.connect(InetSocketAddress(host, port), 2_500)
            synchronized(writeLock) {
                socket = candidate
                output = candidate.getOutputStream()
            }
            reportConnected(true)
            Log.i(tag, "Connected to receiver at $host:$port")
        } catch (e: Exception) {
            Log.d(tag, "Waiting for receiver $host:$port: ${e.message}")
            reportConnected(false, e.message)
        } finally {
            connecting.set(false)
        }
    }

    override fun send(header: PacketHeader, payload: ByteArray): Boolean {
        val stream = output ?: return false
        val headerBuffer = ByteBuffer.allocate(PacketHeader.HEADER_SIZE)
        header.writeTo(headerBuffer)
        return synchronized(writeLock) {
            try {
                stream.write(headerBuffer.array(), 0, PacketHeader.HEADER_SIZE)
                stream.write(payload)
                stream.flush()
                true
            } catch (e: Exception) {
                Log.w(tag, "Send failed, dropping connection", e)
                closeSocket(e.message ?: "Connection lost")
                false
            }
        }
    }

    private fun closeSocket(reason: String?) {
        synchronized(writeLock) {
            runCatching { output?.close() }
            runCatching { socket?.close() }
            output = null
            socket = null
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
    }
}
