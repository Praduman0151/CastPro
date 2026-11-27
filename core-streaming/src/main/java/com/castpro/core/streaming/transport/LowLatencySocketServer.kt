package com.castpro.core.streaming.transport

import android.util.Log
import com.castpro.core.common.model.GeometryPacket
import com.castpro.core.common.protocol.Constants
import com.castpro.core.common.protocol.PacketHeader
import com.castpro.core.common.protocol.StreamConfigPacket
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Receives Cast Pro packets on the TV. Each client is handled independently so
 * a phone that walks out of Wi-Fi range cannot kill the listener for good.
 */
class LowLatencySocketServer(
    private val port: Int = Constants.DEFAULT_STREAM_PORT,
    private val onFrameReceived: (ByteArray, Int, Int, Long, Boolean, Boolean, Boolean) -> Unit,
    private val onAudioReceived: (ByteArray, Int, Int) -> Unit = { _, _, _ -> },
    private val onGeometryReceived: (GeometryPacket) -> Unit = {},
    private val onStreamConfigReceived: (StreamConfigPacket) -> Unit = {},
    private val onHeartbeatReceived: (Long) -> Unit = {},
    private val onClientConnected: (String) -> Unit,
    private val onClientDisconnected: () -> Unit,
    private val onError: (String) -> Unit = {}
) {
    private val tag = "SocketServer"
    private var serverSocket: ServerSocket? = null
    private val registry = ConnectionRegistry<Socket>()
    private val isRunning = AtomicBoolean(false)
    private var serverThread: Thread? = null

    fun start(): Boolean {
        if (!isRunning.compareAndSet(false, true)) return false
        var socket: ServerSocket? = null
        try {
            socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(port))
            serverSocket = socket
            Log.i(tag, "Low-latency receiver listening on port $port")
        } catch (e: Exception) {
            // Never leave an unbound socket behind: it keeps the fd alive and
            // makes the next retry look like the port is still occupied.
            runCatching { socket?.close() }
            isRunning.set(false)
            Log.e(tag, "Could not bind receiver port $port", e)
            runCatching {
                onError("Receiver port $port is busy. Retrying automatically…")
            }
            return false
        }

        serverThread = Thread({
            while (isRunning.get()) {
                try {
                    val client = serverSocket?.accept() ?: break
                    client.tcpNoDelay = true
                    client.receiveBufferSize = 256 * 1024
                    // Accepting a socket is not proof of a real sender. Keep
                    // the active phone until a candidate sends its first valid
                    // Cast Pro packet; only then is it promoted. A stray port
                    // probe can no longer kick the active session offline.
                    val staleCandidate = registry.register(client)
                    if (staleCandidate != null && staleCandidate !== client) {
                        runCatching { staleCandidate.close() }
                    }
                    Log.i(tag, "Client socket accepted: ${client.inetAddress.hostAddress ?: "Unknown"}")
                    Thread({ handleClient(client) }, "CastPro-Client").apply {
                        isDaemon = true
                        start()
                    }
                } catch (e: Exception) {
                    if (isRunning.get()) {
                        Log.e(tag, "Server socket error", e)
                        runCatching { onError(e.message ?: "Receiver socket error") }
                    }
                    // A transient accept failure must not stop the listener.
                    if (isRunning.get()) {
                        try { Thread.sleep(250) } catch (_: InterruptedException) { break }
                    }
                }
            }
        }, "CastPro-Server-Thread").apply { isDaemon = true; start() }
        return true
    }

    private fun handleClient(client: Socket) {
        try {
            // The phone sends video and a heartbeat every second. If nothing
            // arrives for this long the link is dead even if TCP never saw a
            // FIN (for example after a Wi-Fi handover).
            runCatching { client.soTimeout = CLIENT_READ_TIMEOUT_MS }
            handleClientStream(client, client.getInputStream())
        } catch (e: Exception) {
            if (isRunning.get()) Log.w(tag, "Client stream ended: ${e.message}")
        } finally {
            val wasCurrent = registry.remove(client)
            runCatching { client.close() }
            // Only the socket that owned the session may flip the UI to
            // "disconnected"; a stray or replaced socket must not do it.
            if (wasCurrent) runCatching { onClientDisconnected() }
        }
    }

    private fun handleClientStream(client: Socket, inputStream: InputStream) {
        val headerBytes = ByteArray(PacketHeader.HEADER_SIZE)
        val headerBuffer = ByteBuffer.wrap(headerBytes)

        while (isRunning.get()) {
            if (!readFully(inputStream, headerBytes, PacketHeader.HEADER_SIZE)) return
            headerBuffer.clear()
            val header = PacketHeader.readFrom(headerBuffer) ?: return
            if (header.payloadLength < 0 || header.payloadLength > Constants.MAX_PAYLOAD_SIZE) {
                Log.w(tag, "Rejecting packet with invalid length ${header.payloadLength}")
                return
            }

            if (!registry.isCurrent(client)) {
                // The first well-formed Cast Pro packet is what proves this is
                // the sender. Until then this socket is only a candidate and
                // cannot replace the active phone or change receiver state.
                if (header.type !in VALID_PACKET_TYPES) {
                    Log.d(tag, "Ignoring non-Cast-Pro packet from candidate ${client.inetAddress.hostAddress}")
                    return
                }
                val evicted = registry.promote(client)
                if (evicted != null && evicted !== client) runCatching { evicted.close() }
                if (!registry.isCurrent(client)) return
                val clientIp = client.inetAddress.hostAddress ?: "Unknown"
                Log.i(tag, "Client connected: $clientIp")
                runCatching { onClientConnected(clientIp) }
            }
            // A concurrently promoted candidate may have taken over while we
            // were reading; never dispatch packets from a replaced socket.
            if (!registry.isCurrent(client)) return

            val payload = ByteArray(header.payloadLength)
            if (!readFully(inputStream, payload, header.payloadLength)) return

            when (header.type) {
                Constants.TYPE_VIDEO_NAL -> {
                    val flags = header.flags.toInt()
                    val isGamingMode = (flags and Constants.FLAG_GAMING_MODE.toInt()) != 0
                    val isCodecConfig = (flags and Constants.FLAG_CODEC_CONFIG.toInt()) != 0
                    // Keep the sender's IDR marker. MediaCodec emits
                    // length-prefixed (AVCC) H.264, so the receiver cannot
                    // reliably recognise a keyframe by scanning for Annex-B
                    // start codes in the payload.
                    val isKeyframe = (flags and Constants.FLAG_KEYFRAME.toInt()) != 0
                    try {
                        onFrameReceived(
                            payload,
                            0,
                            header.payloadLength,
                            header.timestampNano,
                            isGamingMode,
                            isCodecConfig,
                            isKeyframe
                        )
                    } catch (e: Exception) {
                        // A decoder hiccup must not tear down the socket or
                        // take down the receiver process.
                        Log.w(tag, "Dropping video packet ${header.sequenceNumber}", e)
                    }
                }
                Constants.TYPE_AUDIO_DATA -> runCatching {
                    onAudioReceived(payload, 0, header.payloadLength)
                }
                Constants.TYPE_GEOMETRY -> runCatching {
                    GeometryPacket.fromByteArray(payload)?.let(onGeometryReceived)
                }
                Constants.TYPE_STREAM_CONFIG -> runCatching {
                    StreamConfigPacket.fromByteArray(payload)?.let(onStreamConfigReceived)
                }
                Constants.TYPE_HEARTBEAT -> runCatching { onHeartbeatReceived(header.timestampNano) }
                else -> Log.d(tag, "Ignoring packet type ${header.type}")
            }
        }
    }

    private fun readFully(stream: InputStream, buffer: ByteArray, length: Int): Boolean {
        var totalRead = 0
        while (totalRead < length) {
            val read = try {
                stream.read(buffer, totalRead, length - totalRead)
            } catch (e: Exception) {
                return false
            }
            if (read < 0) return false
            totalRead += read
        }
        return true
    }

    fun stop() {
        isRunning.set(false)
        runCatching { registry.current?.close() }
        registry.clear()
        runCatching { serverSocket?.close() }
        serverThread?.interrupt()
        serverSocket = null
    }

    private companion object {
        /** Longer than the phone's 1 s heartbeat, short enough to detect a dead link. */
        const val CLIENT_READ_TIMEOUT_MS = 15_000
        private val VALID_PACKET_TYPES = setOf(
            Constants.TYPE_VIDEO_NAL,
            Constants.TYPE_AUDIO_DATA,
            Constants.TYPE_HEARTBEAT,
            Constants.TYPE_HANDSHAKE,
            Constants.TYPE_GEOMETRY,
            Constants.TYPE_STREAM_CONFIG
        )
    }
}
