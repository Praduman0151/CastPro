package com.castpro.core.streaming.transport

import android.util.Log
import com.castpro.core.common.protocol.PacketHeader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Experimental UDP video receiver. It reassembles bounded frame fragments and
 * forwards complete packets using the same callback contract as the TCP server.
 *
 * Run on a dedicated worker thread; never decode on this socket thread.
 */
class UdpVideoReceiver(
    private val port: Int,
    private val onVideoPacket: (ByteArray, PacketHeader) -> Unit,
    private val onError: (String) -> Unit = {}
) {
    private data class Key(val address: String, val sequence: Int, val type: Byte)
    private data class Assembly(
        val header: PacketHeader,
        val bytes: ByteArray,
        val received: BooleanArray,
        val createdAtMs: Long,
        var receivedCount: Int = 0
    )

    private val running = AtomicBoolean(false)
    private val assemblies = LinkedHashMap<Key, Assembly>()
    @Volatile private var socket: DatagramSocket? = null
    private var worker: Thread? = null

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return false
        return try {
            val server = DatagramSocket(null).apply {
                reuseAddress = true
                receiveBufferSize = 1 shl 20
                soTimeout = 500
                bind(InetSocketAddress(port))
            }
            socket = server
            worker = Thread({ receiveLoop(server) }, "CastPro-UDP-Video").apply {
                isDaemon = true
                start()
            }
            true
        } catch (error: Exception) {
            running.set(false)
            onError(error.message ?: "Could not bind UDP video port $port")
            false
        }
    }

    private fun receiveLoop(server: DatagramSocket) {
        val storage = ByteArray(MAX_DATAGRAM_BYTES)
        while (running.get()) {
            try {
                val datagram = DatagramPacket(storage, storage.size)
                server.receive(datagram)
                if (datagram.length < MIN_PACKET_BYTES) continue
                val buffer = ByteBuffer.wrap(datagram.data, datagram.offset, datagram.length)
                if (buffer.int != MAGIC) continue
                val header = PacketHeader.readFrom(buffer) ?: continue
                val index = buffer.short.toInt() and 0xffff
                val count = buffer.short.toInt() and 0xffff
                val totalLength = buffer.int
                val fragmentLength = buffer.remaining()

                if (header.payloadLength != totalLength ||
                    totalLength !in 1..MAX_FRAME_BYTES ||
                    count !in 1..MAX_FRAGMENTS ||
                    index !in 0 until count ||
                    count != (totalLength + FRAGMENT_DATA_BYTES - 1) / FRAGMENT_DATA_BYTES ||
                    fragmentLength <= 0 ||
                    fragmentLength > FRAGMENT_DATA_BYTES
                ) continue

                val expectedLength = minOf(FRAGMENT_DATA_BYTES, totalLength - index * FRAGMENT_DATA_BYTES)
                if (fragmentLength != expectedLength) continue

                val key = Key(datagram.address.hostAddress ?: "unknown", header.sequenceNumber, header.type)
                val assembly = synchronized(assemblies) {
                    pruneExpired()
                    assemblies[key] ?: run {
                        if (assemblies.size >= MAX_IN_FLIGHT) {
                            val oldest = assemblies.entries.firstOrNull()?.key
                            if (oldest != null) assemblies.remove(oldest)
                        }
                        Assembly(header, ByteArray(totalLength), BooleanArray(count), System.currentTimeMillis())
                            .also { assemblies[key] = it }
                    }
                }

                if (assembly.bytes.size != totalLength ||
                    assembly.received.size != count ||
                    assembly.header.timestampNano != header.timestampNano
                ) {
                    synchronized(assemblies) { assemblies.remove(key) }
                    continue
                }

                if (!assembly.received[index]) {
                    val destinationOffset = index * FRAGMENT_DATA_BYTES
                    buffer.get(assembly.bytes, destinationOffset, fragmentLength)
                    assembly.received[index] = true
                    assembly.receivedCount++
                }

                if (assembly.receivedCount == count) {
                    synchronized(assemblies) { assemblies.remove(key) }
                    try {
                        onVideoPacket(assembly.bytes, assembly.header)
                    } catch (error: Exception) {
                        Log.w(TAG, "Video packet callback failed", error)
                    }
                }
            } catch (_: SocketTimeoutException) {
                synchronized(assemblies) { pruneExpired() }
            } catch (error: Exception) {
                if (running.get()) Log.w(TAG, "UDP receive error", error)
            }
        }
    }

    private fun pruneExpired() {
        val cutoff = System.currentTimeMillis() - ASSEMBLY_TTL_MS
        val iterator = assemblies.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value.createdAtMs < cutoff) iterator.remove()
        }
    }

    fun stop() {
        running.set(false)
        socket?.close()
        socket = null
        worker?.interrupt()
        worker = null
        synchronized(assemblies) { assemblies.clear() }
    }

    companion object {
        private const val TAG = "UdpVideoReceiver"
        private const val MAGIC = 0x43505531 // "CPU1"
        private const val MAX_DATAGRAM_BYTES = 1200
        private const val FRAGMENT_HEADER_BYTES = 4 + PacketHeader.HEADER_SIZE + 2 + 2 + 4
        private const val MIN_PACKET_BYTES = FRAGMENT_HEADER_BYTES + 1
        private const val FRAGMENT_DATA_BYTES = MAX_DATAGRAM_BYTES - FRAGMENT_HEADER_BYTES
        private const val MAX_FRAME_BYTES = 8 * 1024 * 1024
        private const val MAX_FRAGMENTS = 8192
        private const val MAX_IN_FLIGHT = 8
        private const val ASSEMBLY_TTL_MS = 250L
    }
}
