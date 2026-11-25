package com.castpro.core.streaming.transport

import android.util.Log
import com.castpro.core.common.protocol.PacketHeader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * Experimental fragmented UDP transport for LAN video packets.
 *
 * Keep TCP for audio, control and stream configuration until the hybrid
 * sender/receiver path has been benchmarked. This class is intentionally
 * opt-in and is not wired into the production session yet.
 */
class UdpVideoStreamTransport(
    private val host: String,
    private val port: Int,
    private val onConnectionChanged: (Boolean, String?) -> Unit = { _, _ -> }
) : StreamTransport {
    private val running = AtomicBoolean(false)
    private val sendLock = Any()
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var address: InetAddress? = null

    override val isConnected: Boolean
        get() = running.get() && socket?.isClosed == false && address != null

    override fun start() {
        if (!running.compareAndSet(false, true)) return
        try {
            val resolved = InetAddress.getByName(host)
            val datagramSocket = DatagramSocket(null).apply {
                reuseAddress = true
                sendBufferSize = 1 shl 20
                connect(InetSocketAddress(resolved, port))
            }
            address = resolved
            socket = datagramSocket
            onConnectionChanged(true, null)
            Log.i(TAG, "Experimental UDP video transport ready for $host:$port")
        } catch (error: Exception) {
            running.set(false)
            onConnectionChanged(false, error.message)
            Log.w(TAG, "Could not start UDP transport", error)
        }
    }

    override fun send(header: PacketHeader, payload: ByteArray): Boolean {
        if (!running.get() || payload.size > MAX_FRAME_BYTES) return false
        val datagramSocket = socket ?: return false
        val destination = address ?: return false
        val count = (payload.size + FRAGMENT_DATA_BYTES - 1) / FRAGMENT_DATA_BYTES
        if (count == 0 || count > MAX_FRAGMENTS) return false

        return synchronized(sendLock) {
            try {
                var offset = 0
                for (index in 0 until count) {
                    val length = min(FRAGMENT_DATA_BYTES, payload.size - offset)
                    val packetBytes = ByteArray(FRAGMENT_HEADER_BYTES + length)
                    val buffer = java.nio.ByteBuffer.wrap(packetBytes)
                    buffer.putInt(MAGIC)
                    val headerBuffer = java.nio.ByteBuffer.allocate(PacketHeader.HEADER_SIZE)
                    header.writeTo(headerBuffer)
                    buffer.put(headerBuffer.array())
                    buffer.putShort(index.toShort())
                    buffer.putShort(count.toShort())
                    buffer.putInt(payload.size)
                    buffer.put(payload, offset, length)
                    datagramSocket.send(DatagramPacket(packetBytes, packetBytes.size, destination, port))
                    offset += length
                }
                true
            } catch (error: Exception) {
                Log.w(TAG, "UDP send failed", error)
                onConnectionChanged(false, error.message)
                false
            }
        }
    }

    override fun stop() {
        running.set(false)
        socket?.close()
        socket = null
        address = null
        onConnectionChanged(false, "Stopped")
    }

    companion object {
        private const val TAG = "UdpVideoTransport"
        private const val MAGIC = 0x43505531 // "CPU1"
        private const val MAX_DATAGRAM_BYTES = 1200
        private const val FRAGMENT_HEADER_BYTES = 4 + PacketHeader.HEADER_SIZE + 2 + 2 + 4
        private const val FRAGMENT_DATA_BYTES = MAX_DATAGRAM_BYTES - FRAGMENT_HEADER_BYTES
        private const val MAX_FRAME_BYTES = 8 * 1024 * 1024
        private const val MAX_FRAGMENTS = 8192
    }
}
