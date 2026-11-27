package com.castpro.core.streaming.server

import android.content.Context
import android.util.Base64
import android.util.Log
import com.castpro.core.common.model.GeometryPacket
import com.castpro.core.common.protocol.Constants
import com.castpro.core.common.protocol.PacketHeader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Zero-install browser receiver.
 *
 * The phone mirrors the display into a 720p JPEG stream (see WebMirrorCapture)
 * and sends the same 20-byte packet header the TV uses. Any browser can draw
 * those JPEGs on a canvas and play the PCM audio with Web Audio, with no HTTPS
 * requirement, no WebCodecs support and no plugin.
 */
class WebStreamingServer(
    private val context: Context,
    private val httpPort: Int = Constants.DEFAULT_WEB_PORT,
    private val wsPort: Int = Constants.DEFAULT_WS_PORT,
    private val onClientCountChanged: (Int) -> Unit = {},
    private val onError: (String) -> Unit = {}
) {
    private val tag = "WebStreamingServer"
    private val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isRunning = AtomicBoolean(false)

    private var httpServerSocket: ServerSocket? = null
    private var wsServerSocket: ServerSocket? = null
    private val activeWsClients = CopyOnWriteArrayList<WsClient>()
    @Volatile private var lastGeometryJson: String? = null

    /**
     * One browser connection with its own bounded send queue and writer thread.
     *
     * The encoder and JPEG threads call [sendFrame]; a browser on a slow link
     * must never block them (that used to freeze the cast for everybody).
     * Frames are dropped oldest-first when the queue is full, so the browser
     * stays close to live instead of playing an ever-growing backlog.
     */
    private inner class WsClient(private val socket: Socket) {
        private val output = BufferedOutputStream(socket.getOutputStream())
        private val queue = WsFrameQueue()
        private val lock = Object()
        private var closed = false
        private var writer: Thread? = null

        fun start() {
            writer = Thread({ writeLoop() }, "CastPro-WsClient").apply {
                isDaemon = true
                start()
            }
        }

        fun send(frame: ByteArray, droppable: Boolean) {
            synchronized(lock) {
                if (closed) return
                queue.offer(frame, droppable)
                lock.notifyAll()
            }
        }

        private fun writeLoop() {
            try {
                while (true) {
                    synchronized(lock) {
                        while (queue.pending == 0 && !closed) {
                            try {
                                lock.wait(500)
                            } catch (_: InterruptedException) {
                                return
                            }
                        }
                        if (closed) return
                    }
                    val frame = queue.pollNow() ?: continue
                    output.write(frame)
                    output.flush()
                }
            } catch (_: Exception) {
                // The browser went away or the socket timed out.
                close()
            }
        }

        fun close() {
            synchronized(lock) {
                if (closed) return
                closed = true
                queue.clear()
                lock.notifyAll()
            }
            runCatching { socket.close() }
            activeWsClients.remove(this)
            onClientCountChanged(activeWsClients.size)
        }
    }

    fun start() {
        if (isRunning.getAndSet(true)) return

        serverScope.launch {
            try {
                val server = ServerSocket()
                server.reuseAddress = true
                server.bind(java.net.InetSocketAddress(httpPort))
                httpServerSocket = server
                Log.i(tag, "Web HTTP Server listening on port $httpPort")
                while (isRunning.get()) {
                    val client = server.accept()
                    launch { handleHttpClient(client) }
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(tag, "HTTP Server error", e)
                    onError("Web page port $httpPort is busy")
                }
            }
        }

        serverScope.launch {
            try {
                val server = ServerSocket()
                server.reuseAddress = true
                server.bind(java.net.InetSocketAddress(wsPort))
                wsServerSocket = server
                Log.i(tag, "WebSocket Server listening on port $wsPort")
                while (isRunning.get()) {
                    val client = server.accept()
                    client.tcpNoDelay = true
                    launch { handleWsClient(client) }
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(tag, "WebSocket Server error", e)
                    onError("WebSocket port $wsPort is busy")
                }
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
                val path = rawPath.substringBefore('?')
                val assetPath = if (path == "/" || path.isBlank()) "/index.html" else path
                val assetName = "web" + assetPath

                val asset = runCatching {
                    context.assets.open(assetName).use { it.readBytes() }
                }.getOrNull()

                val contentType = when {
                    assetPath.endsWith(".html") -> "text/html; charset=utf-8"
                    assetPath.endsWith(".js") -> "application/javascript; charset=utf-8"
                    assetPath.endsWith(".css") -> "text/css; charset=utf-8"
                    assetPath.endsWith(".png") -> "image/png"
                    assetPath.endsWith(".svg") -> "image/svg+xml"
                    else -> "application/octet-stream"
                }

                val status = if (asset != null) "200 OK" else "404 Not Found"
                val body = asset ?: "404 Not Found".toByteArray()
                val header = "HTTP/1.1 $status\r\n" +
                    "Content-Type: $contentType\r\n" +
                    "Content-Length: ${body.size}\r\n" +
                    "Cache-Control: no-store\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Connection: close\r\n\r\n"

                out.write(header.toByteArray())
                out.write(body)
                out.flush()
            }
        } catch (_: Exception) {
            // Browser closed the connection; nothing to do.
        }
    }

    private fun handleWsClient(socket: Socket) {
        var client: WsClient? = null
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

            val acceptKey = Base64.encodeToString(
                MessageDigest.getInstance("SHA-1")
                    .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()),
                Base64.NO_WRAP
            )
            val handshakeResponse = "HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: $acceptKey\r\n\r\n"
            output.write(handshakeResponse.toByteArray())
            output.flush()

            val connected = WsClient(socket)
            client = connected
            activeWsClients.add(connected)
            connected.start()
            onClientCountChanged(activeWsClients.size)
            Log.i(tag, "New WebSocket client connected. Total clients: ${activeWsClients.size}")

            connected.send(wsTextFrame("{\"type\":\"connected\",\"msg\":\"Cast Pro Web Receiver Ready\",\"images\":true}"), droppable = false)
            lastGeometryJson?.let { connected.send(wsTextFrame(it), droppable = false) }

            val buffer = ByteArray(1024)
            while (isRunning.get() && socket.isConnected && !socket.isClosed) {
                val read = input.read(buffer)
                if (read == -1) break
            }
        } catch (e: Exception) {
            Log.d(tag, "Client disconnected: ${e.message}")
        } finally {
            client?.close()
            Log.i(tag, "Client removed. Remaining clients: ${activeWsClients.size}")
        }
    }

    fun hasClients(): Boolean = activeWsClients.isNotEmpty()

    fun broadcastGeometry(geometry: GeometryPacket) {
        val json = "{\"type\":\"geometry\",\"aspectRatio\":${geometry.aspectRatio}," +
            "\"width\":${geometry.width},\"height\":${geometry.height}," +
            "\"rotation\":${geometry.rotationDegrees}}"
        lastGeometryJson = json
        val frame = wsTextFrame(json)
        for (client in activeWsClients) client.send(frame, droppable = false)
    }

    /** Sends one complete Cast Pro packet (20-byte header + payload) as a binary frame. */
    fun broadcastPacket(header: PacketHeader, payload: ByteArray) {
        if (activeWsClients.isEmpty()) return
        val headerBytes = java.nio.ByteBuffer.allocate(PacketHeader.HEADER_SIZE).also {
            header.writeTo(it)
        }.array()
        val total = headerBytes.size + payload.size
        val frameHeader = makeWsFrameHeader(0x82.toByte(), total)
        val frame = ByteArray(frameHeader.size + total)
        System.arraycopy(frameHeader, 0, frame, 0, frameHeader.size)
        System.arraycopy(headerBytes, 0, frame, frameHeader.size, headerBytes.size)
        System.arraycopy(payload, 0, frame, frameHeader.size + headerBytes.size, payload.size)

        for (client in activeWsClients) client.send(frame, droppable = true)
    }

    private fun wsTextFrame(text: String): ByteArray {
        val payload = text.toByteArray()
        val header = makeWsFrameHeader(0x81.toByte(), payload.size)
        val frame = ByteArray(header.size + payload.size)
        System.arraycopy(header, 0, frame, 0, header.size)
        System.arraycopy(payload, 0, frame, header.size, payload.size)
        return frame
    }

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
        for (client in activeWsClients) client.close()
        activeWsClients.clear()
        onClientCountChanged(0)
        runCatching { httpServerSocket?.close() }
        runCatching { wsServerSocket?.close() }
        httpServerSocket = null
        wsServerSocket = null
        serverScope.cancel()
    }


}
