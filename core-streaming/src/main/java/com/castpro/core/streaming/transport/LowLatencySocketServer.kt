package com.castpro.core.streaming.transport

import android.util.Log
import com.castpro.core.common.model.GeometryPacket
import com.castpro.core.common.protocol.AnnexBCodecConfig
import com.castpro.core.common.protocol.Constants
import com.castpro.core.common.protocol.PacketHeader
import com.castpro.core.common.protocol.PacketStreamReader
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
    @Volatile private var clientSocket: Socket? = null
    private val isRunning = AtomicBoolean(false)
    private var serverThread: Thread? = null
    private var loggedVideoPackets = 0
    @Volatile private var diagnosticCodecMime = "video/avc"

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
                    client.receiveBufferSize = 64 * 1024
                    clientSocket = client
                    val clientIp = client.inetAddress.hostAddress ?: "Unknown"
                    Log.i(tag, "Client connected: $clientIp")
                    runCatching { onClientConnected(clientIp) }

                    try {
                        handleClientStream(client.getInputStream())
                    } catch (e: Exception) {
                        if (isRunning.get()) Log.w(tag, "Client stream ended: ${e.message}")
                    } finally {
                        runCatching { client.close() }
                        clientSocket = null
                        runCatching { onClientDisconnected() }
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

    private fun handleClientStream(inputStream: InputStream) {
        while (isRunning.get()) {
            val packet = try {
                PacketStreamReader.readPacket(inputStream)
            } catch (e: IllegalArgumentException) {
                Log.w(tag, "Rejecting malformed packet: ${e.message}")
                return
            } catch (e: Exception) {
                Log.w(tag, "Packet stream ended: ${e.message}")
                return
            } ?: return
            val header = packet.header
            val payload = packet.payload
            when (header.type) {
                Constants.TYPE_VIDEO_NAL -> {
                    val flags = header.flags.toInt()
                    val isGamingMode = (flags and Constants.FLAG_GAMING_MODE.toInt()) != 0
                    val isCodecConfig = (flags and Constants.FLAG_CODEC_CONFIG.toInt()) != 0
                    val isKeyframe = (flags and Constants.FLAG_KEYFRAME.toInt()) != 0
                    if (loggedVideoPackets < 10) {
                        loggedVideoPackets++
                        Log.i(
                            tag,
                            "Video packet #$loggedVideoPackets type=${header.type} flags=0x${flags.toString(16)} " +
                                "bytes=${payload.size} first16=${AnnexBCodecConfig.hexPrefix(payload)} " +
                                "annexBNalTypes=${AnnexBCodecConfig.nalUnitTypes(diagnosticCodecMime, payload)} " +
                                "codecConfig=$isCodecConfig keyframe=$isKeyframe"
                        )
                    }
                    try {
                        onFrameReceived(
                            payload, 0, payload.size, header.timestampNano,
                            isGamingMode, isCodecConfig, isKeyframe
                        )
                    } catch (e: Exception) {
                        Log.w(tag, "Dropping video packet ${header.sequenceNumber}", e)
                    }
                }
                Constants.TYPE_AUDIO_DATA -> runCatching {
                    onAudioReceived(payload, 0, payload.size)
                }
                Constants.TYPE_GEOMETRY -> runCatching {
                    GeometryPacket.fromByteArray(payload)?.let(onGeometryReceived)
                }
                Constants.TYPE_STREAM_CONFIG -> runCatching {
                    StreamConfigPacket.fromByteArray(payload)?.let { config ->
                        diagnosticCodecMime = config.codec.mimeType
                        onStreamConfigReceived(config)
                    }
                }
                Constants.TYPE_HEARTBEAT -> runCatching { onHeartbeatReceived(header.timestampNano) }
                else -> Log.d(tag, "Ignoring packet type ${header.type}")
            }
        }
    }

    fun stop() {
        isRunning.set(false)
        runCatching { clientSocket?.close() }
        runCatching { serverSocket?.close() }
        serverThread?.interrupt()
        clientSocket = null
        serverSocket = null
    }
}
