package com.castpro.core.streaming.transport

import com.castpro.core.common.protocol.PacketHeader

/** What actually happened to packets handed to a transport. */
data class TransportCounters(
    val queuedPackets: Long = 0,
    val writtenPackets: Long = 0,
    val writtenBytes: Long = 0,
    val droppedPackets: Long = 0,
    val pendingPackets: Int = 0,
    /** Video frames (excluding SPS/PPS) actually handed to the socket. */
    val writtenVideoFrames: Long = 0,
    val writtenVideoBytes: Long = 0
)

/** A packet sink used by the encoder, audio capture and geometry monitor. */
interface StreamTransport {
    val isConnected: Boolean

    /** Diagnostics: queued is not the same as written; dropped includes queue evictions. */
    val counters: TransportCounters
        get() = TransportCounters()

    fun start()
    fun send(header: PacketHeader, payload: ByteArray): Boolean
    fun clearMediaQueue() {}
    fun stop()
}

/** Used when only the browser receiver is active. */
object NoopStreamTransport : StreamTransport {
    override val isConnected: Boolean = false
    override fun start() = Unit
    override fun send(header: PacketHeader, payload: ByteArray): Boolean = false
    override fun stop() = Unit
}
