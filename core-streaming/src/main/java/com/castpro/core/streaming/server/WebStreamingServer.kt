package com.castpro.core.streaming.server

import android.content.Context
import android.util.Base64
import android.util.Log
import com.castpro.core.common.model.GeometryPacket
import com.castpro.core.common.protocol.Constants
import com.castpro.core.common.protocol.PacketHeader
import kotlinx.coroutines.*
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class WebStreamingServer(
    private val context: Context,
    private val httpPort: Int = Constants.DEFAULT_WEB_PORT,
    private val wsPort: Int = Constants.DEFAULT_WS_PORT
) {
    private val tag = "WebStreamingServer"
    private val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isRunning = AtomicBoolean(false)

    private var httpServerSocket: ServerSocket? = null
    private var wsServerSocket: ServerSocket? = null
    private val activeWsClients = CopyOnWriteArrayList<Socket>()

    fun start() {
        if (isRunning.getAndSet(true)) return

        // 1. HTTP Server for web assets
        serverScope.launch {
            try {
                val server = ServerSocket(httpPort)
                httpServerSocket = server
                Log.i(tag, "Web HTTP Server listening on port $httpPort")
                while (isRunning.get()) {
                    val client = server.accept()
                    launch { handleHttpClient(client) }
                }
            } catch (e: Exception) {
                if (isRunning.get()) Log.e(tag, "HTTP Server error", e)
            }
        }

        // 2. WebSocket Server for live video/audio streaming & signaling
        serverScope.launch {
            try {
                val server = ServerSocket(wsPort)
                wsServerSocket = server
                Log.i(tag, "WebSocket Server listening on port $wsPort")
                while (isRunning.get()) {
                    val client = server.accept()
                    client.tcpNoDelay = true
                    launch { handleWsClient(client) }
                }
            } catch (e: Exception) {
                if (isRunning.get()) Log.e(tag, "WebSocket Server error", e)
            }
        }
    }

    private fun handleHttpClient(socket: Socket) {
        try {
            socket.use { s ->
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                val out = BufferedOutputStream(s.getOutputStream())

                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val rawPath = parts[1].trim()
                val path = if (rawPath == "/" || rawPath.isBlank()) "/index.html" else rawPath

                val assetName = "web" + path
                val (contentType, contentBytes) = try {
                    val stream = context.assets.open(assetName)
                    val bytes = stream.readBytes()
                    stream.close()
                    val mime = when {
                        path.endsWith(".html") -> "text/html; charset=utf-8"
                        path.endsWith(".js") -> "application/javascript; charset=utf-8"
                        path.endsWith(".css") -> "text/css; charset=utf-8"
                        else -> "application/octet-stream"
                    }
                    Pair(mime, bytes)
                } catch (e: Exception) {
                    Pair("text/plain", "404 Not Found".toByteArray())
                }

                val header = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: $contentType\r\n" +
                        "Content-Length: ${contentBytes.size}\r\n" +
                        "Access-Control-Allow-Origin: *\r\n" +
                        "Connection: close\r\n\r\n"

                out.write(header.toByteArray())
                out.write(contentBytes)
                out.flush()
            }
        } catch (e: Exception) {
            // Client closed connection
        }
    }

    private fun handleWsClient(socket: Socket) {
        try {
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            val reader = BufferedReader(InputStreamReader(input))

            var key = ""
            var line: String? = reader.readLine()
            while (!line.isNullOrBlank()) {
                if (line.startsWith("Sec-WebSocket-Key:", ignoreCase = true)) {
                    key = line.substringAfter(":").trim()
                }
                line = reader.readLine()
            }

            if (key.isBlank()) {
                socket.close()
                return
            }

            // Perform RFC 6455 Handshake
            val acceptKey = Base64.encodeToString(
                MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()),
                Base64.NO_WRAP
            )

            val handshakeResponse = "HTTP/1.1 101 Switching Protocols\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: $acceptKey\r\n\r\n"

            output.write(handshakeResponse.toByteArray())
            output.flush()

            activeWsClients.add(socket)
            Log.i(tag, "New WebSocket client connected. Total clients: ${activeWsClients.size}")

            // Send initial connection heartbeat
            sendWsTextMessage(socket, "{\"type\":\"connected\",\"msg\":\"Cast Pro Stream Ready\"}")

            // Keep reading ping/pong or wait for close
            val buffer = ByteArray(1024)
            while (isRunning.get() && socket.isConnected && !socket.isClosed) {
                val read = input.read(buffer)
                if (read == -1) break
            }
        } catch (e: Exception) {
            Log.d(tag, "Client disconnected: ${e.message}")
        } finally {
            activeWsClients.remove(socket)
            try { socket.close() } catch (ignored: Exception) {}
            Log.i(tag, "Client removed. Remaining clients: ${activeWsClients.size}")
        }
    }

    fun broadcastGeometry(geometry: GeometryPacket) {
        val json = "{\"type\":\"geometry\",\"aspectRatio\":${geometry.aspectRatio},\"width\":${geometry.width},\"height\":${geometry.height},\"rotation\":${geometry.rotationDegrees}}"
        for (client in activeWsClients) {
            sendWsTextMessage(client, json)
        }
    }

    fun broadcastPacket(header: PacketHeader, payload: ByteArray) {
        if (activeWsClients.isEmpty()) return
        // Send binary frame over WebSocket (Opcode 0x82)
        val frameHeader = makeWsBinaryFrameHeader(payload.size)
        val fullData = ByteArray(frameHeader.size + payload.size)
        System.arraycopy(frameHeader, 0, fullData, 0, frameHeader.size)
        System.arraycopy(payload, 0, fullData, frameHeader.size, payload.size)

        for (client in activeWsClients) {
            try {
                synchronized(client) {
                    client.getOutputStream().write(fullData)
                    client.getOutputStream().flush()
                }
            } catch (e: Exception) {
                activeWsClients.remove(client)
            }
        }
    }

    private fun sendWsTextMessage(socket: Socket, text: String) {
        try {
            val bytes = text.toByteArray()
            val header = makeWsTextFrameHeader(bytes.size)
            synchronized(socket) {
                val out = socket.getOutputStream()
                out.write(header)
                out.write(bytes)
                out.flush()
            }
        } catch (e: Exception) {
            activeWsClients.remove(socket)
        }
    }

    private fun makeWsTextFrameHeader(len: Int): ByteArray = makeWsFrameHeader(0x81.toByte(), len)
    private fun makeWsBinaryFrameHeader(len: Int): ByteArray = makeWsFrameHeader(0x82.toByte(), len)

    private fun makeWsFrameHeader(opcode: Byte, len: Int): ByteArray {
        return when {
            len <= 125 -> byteArrayOf(opcode, len.toByte())
            len <= 65535 -> byteArrayOf(
                opcode,
                126.toByte(),
                ((len shr 8) and 0xFF).toByte(),
                (len and 0xFF).toByte()
            )
            else -> byteArrayOf(
                opcode,
                127.toByte(),
                0, 0, 0, 0,
                ((len shr 24) and 0xFF).toByte(),
                ((len shr 16) and 0xFF).toByte(),
                ((len shr 8) and 0xFF).toByte(),
                (len and 0xFF).toByte()
            )
        }
    }

    fun stop() {
        isRunning.set(false)
        for (client in activeWsClients) {
            try { client.close() } catch (ignored: Exception) {}
        }
        activeWsClients.clear()
        try { httpServerSocket?.close() } catch (ignored: Exception) {}
        try { wsServerSocket?.close() } catch (ignored: Exception) {}
        serverScope.cancel()
    }
}
