package com.castpro.core.streaming.transport

import android.util.Log
import com.castpro.core.common.protocol.Constants
import com.castpro.core.common.protocol.PacketHeader
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class LowLatencySocketServer(
    private val port: Int = Constants.DEFAULT_STREAM_PORT,
    private val onFrameReceived: (ByteArray, Int, Int, Long, Boolean) -> Unit,
    private val onAudioReceived: (ByteArray, Int, Int) -> Unit = { _, _, _ -> },
    private val onClientConnected: (String) -> Unit,
    private val onClientDisconnected: () -> Unit
) {
    private val tag = "SocketServer"
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private val isRunning = AtomicBoolean(false)
    private var serverThread: Thread? = null

    fun start() {
        isRunning.set(true)
        serverThread = Thread({
            try {
                val ss = ServerSocket(port)
                serverSocket = ss
                Log.i(tag, "Low-latency receiver listening on port $port")

                while (isRunning.get()) {
                    val client = ss.accept()
                    client.tcpNoDelay = true
                    client.receiveBufferSize = 128 * 1024
                    clientSocket = client
                    val clientIp = client.inetAddress.hostAddress ?: "Unknown"
                    Log.i(tag, "Client connected: $clientIp")
                    onClientConnected(clientIp)

                    handleClientStream(client.getInputStream())
                    onClientDisconnected()
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(tag, "Server socket error", e)
                }
            }
        }, "CastPro-Server-Thread").apply { start() }
    }

    private fun handleClientStream(inputStream: InputStream) {
        val headerBytes = ByteArray(PacketHeader.HEADER_SIZE)
        val headerBuffer = ByteBuffer.wrap(headerBytes)

        while (isRunning.get()) {
            if (!readFully(inputStream, headerBytes, PacketHeader.HEADER_SIZE)) {
                break
            }
            headerBuffer.clear()
            val header = PacketHeader.readFrom(headerBuffer) ?: continue

            val payload = ByteArray(header.payloadLength)
            if (!readFully(inputStream, payload, header.payloadLength)) {
                break
            }

            if (header.type == Constants.TYPE_VIDEO_NAL) {
                val isGamingMode = (header.flags.toInt() and Constants.FLAG_GAMING_MODE.toInt()) != 0
                onFrameReceived(payload, 0, header.payloadLength, header.timestampNano, isGamingMode)
            } else if (header.type == Constants.TYPE_AUDIO_DATA) {
                onAudioReceived(payload, 0, header.payloadLength)
            }
        }
    }

    private fun readFully(stream: InputStream, buffer: ByteArray, length: Int): Boolean {
        var totalRead = 0
        while (totalRead < length) {
            val read = stream.read(buffer, totalRead, length - totalRead)
            if (read < 0) return false
            totalRead += read
        }
        return true
    }

    fun stop() {
        isRunning.set(false)
        try {
            clientSocket?.close()
            serverSocket?.close()
            serverThread?.interrupt()
        } catch (e: Exception) {
            Log.e(tag, "Error stopping server", e)
        } finally {
            clientSocket = null
            serverSocket = null
        }
    }
}
