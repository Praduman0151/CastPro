package com.castpro.core.streaming.transport

import android.util.Log
import com.castpro.core.common.protocol.Constants
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

class LowLatencySocketClient(
    private val host: String,
    private val port: Int = Constants.DEFAULT_STREAM_PORT
) {
    private val tag = "SocketClient"
    private var socket: Socket? = null
    var outputStream: OutputStream? = null
        private set

    fun connect(): OutputStream {
        val s = Socket()
        s.tcpNoDelay = true
        s.sendBufferSize = 128 * 1024
        s.connect(InetSocketAddress(host, port), 3000)
        socket = s
        val stream = s.getOutputStream()
        outputStream = stream
        Log.i(tag, "Connected to receiver at $host:$port with TCP_NODELAY enabled")
        return stream
    }

    fun disconnect() {
        try {
            outputStream?.close()
            socket?.close()
        } catch (e: Exception) {
            Log.e(tag, "Error closing client socket", e)
        } finally {
            socket = null
            outputStream = null
        }
    }
}
