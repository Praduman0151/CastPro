package com.castpro.core.streaming.transport

import com.castpro.core.common.protocol.PacketHeader

/** A packet sink used by the encoder, audio capture and geometry monitor. */
interface StreamTransport {
    val isConnected: Boolean
    fun start()
    fun send(header: PacketHeader, payload: ByteArray): Boolean
    fun stop()
}

/** Used when only the browser receiver is active. */
object NoopStreamTransport : StreamTransport {
    override val isConnected: Boolean = false
    override fun start() = Unit
    override fun send(header: PacketHeader, payload: ByteArray): Boolean = false
    override fun stop() = Unit
}
