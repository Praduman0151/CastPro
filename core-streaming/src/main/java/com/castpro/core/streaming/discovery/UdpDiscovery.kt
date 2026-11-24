package com.castpro.core.streaming.discovery

import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.castpro.core.common.model.DiscoveredReceiver
import com.castpro.core.common.model.DiscoverySource
import com.castpro.core.common.protocol.Constants
import com.castpro.core.streaming.network.LocalNetwork
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * UDP broadcast discovery is the fallback for routers, TVs and access points
 * where mDNS is blocked. The TV answers a small probe with its stream port and
 * name, so a phone can connect without typing an IP address.
 */
class UdpDiscoveryClient(
    private val onReceiverFound: (DiscoveredReceiver) -> Unit,
    private val onError: (String) -> Unit = {}
) {
    private val tag = "UdpDiscoveryClient"
    private val running = AtomicBoolean(false)
    private val sendLock = Any()
    @Volatile private var socket: DatagramSocket? = null
    private var thread: Thread? = null
    private var reportedError = false

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread({ runLoop() }, "CastPro-UdpDiscovery").apply { isDaemon = true; start() }
    }

    fun refresh() {
        val current = socket ?: return
        runCatching { sendProbe(current) }
    }

    private fun runLoop() {
        try {
            val udp = DatagramSocket().apply {
                broadcast = true
                soTimeout = 500
            }
            socket = udp
            while (running.get()) {
                sendProbe(udp)
                val deadline = SystemClock.elapsedRealtime() + Constants.DISCOVERY_INTERVAL_MS
                while (running.get() && SystemClock.elapsedRealtime() < deadline) {
                    val buffer = ByteArray(2048)
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        udp.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    handlePacket(packet)
                }
            }
        } catch (e: Exception) {
            if (running.get() && !reportedError) {
                reportedError = true
                Log.e(tag, "UDP discovery failed", e)
                onError(e.message ?: "UDP discovery failed")
            }
        } finally {
            socket = null
        }
    }

    private fun sendProbe(udp: DatagramSocket) {
        val request = Constants.DISCOVERY_REQUEST.toByteArray()
        val targets = LocalNetwork.broadcastAddresses()
        synchronized(sendLock) {
            for (target in targets) {
                try {
                    udp.send(DatagramPacket(request, request.size, target, Constants.DEFAULT_DISCOVERY_PORT))
                } catch (e: Exception) {
                    Log.d(tag, "Probe to $target failed: ${e.message}")
                }
            }
        }
    }

    private fun handlePacket(packet: DatagramPacket) {
        val text = runCatching { String(packet.data, 0, packet.length).trim() }.getOrNull() ?: return
        if (!text.startsWith(Constants.DISCOVERY_REPLY_PREFIX)) return
        val json = runCatching { JSONObject(text.removePrefix(Constants.DISCOVERY_REPLY_PREFIX).trim()) }
            .getOrNull() ?: return
        if (json.optString("service") != "castpro") return
        val port = json.optInt("port", Constants.DEFAULT_STREAM_PORT)
        if (port !in 1..65535) return
        val host = packet.address?.hostAddress ?: return
        val receiver = DiscoveredReceiver(
            deviceName = json.optString("name").ifBlank { "Cast Pro TV" },
            hostAddress = host,
            port = port,
            source = DiscoverySource.UDP
        )
        onReceiverFound(receiver)
    }

    fun stop() {
        running.set(false)
        runCatching { socket?.close() }
        socket = null
        thread?.interrupt()
        thread = null
    }
}

/** Runs on the TV and answers [UdpDiscoveryClient] probes. */
class UdpDiscoveryResponder(
    private val serviceName: String = "CastPro-TV-${Build.MODEL}",
    private val streamPort: Int = Constants.DEFAULT_STREAM_PORT,
    private val webPort: Int = Constants.DEFAULT_WEB_PORT,
    private val onError: (String) -> Unit = {}
) {
    private val tag = "UdpDiscoveryResponder"
    private val running = AtomicBoolean(false)
    private var socket: DatagramSocket? = null
    private var thread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        try {
            val udp = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(Constants.DEFAULT_DISCOVERY_PORT))
                soTimeout = 1000
            }
            socket = udp
        } catch (e: Exception) {
            running.set(false)
            Log.e(tag, "Could not listen for discovery probes", e)
            onError("Discovery port ${Constants.DEFAULT_DISCOVERY_PORT} is busy")
            return
        }

        thread = Thread({
            val buffer = ByteArray(1024)
            while (running.get()) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket?.receive(packet) ?: break
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (e: Exception) {
                    if (running.get()) Log.w(tag, "Discovery receive failed", e)
                    continue
                }
                val request = runCatching { String(packet.data, 0, packet.length).trim() }.getOrNull() ?: continue
                if (!request.startsWith(Constants.DISCOVERY_REQUEST)) continue
                val reply = JSONObject()
                    .put("service", "castpro")
                    .put("name", serviceName)
                    .put("port", streamPort)
                    .put("web", webPort)
                    .put("version", Constants.PROTOCOL_VERSION)
                    .toString()
                val payload = (Constants.DISCOVERY_REPLY_PREFIX + reply).toByteArray()
                runCatching {
                    socket?.send(DatagramPacket(payload, payload.size, packet.address, packet.port))
                }
            }
        }, "CastPro-UdpResponder").apply { isDaemon = true; start() }
    }

    fun stop() {
        running.set(false)
        runCatching { socket?.close() }
        socket = null
        thread?.interrupt()
        thread = null
    }
}
