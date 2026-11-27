package com.castpro.core.streaming.discovery

import android.content.Context
import android.net.Network
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
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * UDP broadcast discovery is the fallback for routers, TVs and access points
 * where mDNS is blocked. The TV answers a small probe with its stream port and
 * name, so a phone can connect without typing an IP address.
 */
class UdpDiscoveryClient(
    context: Context,
    private val onReceiverFound: (DiscoveredReceiver) -> Unit,
    private val onError: (String) -> Unit = {}
) {
    private val tag = "UdpDiscoveryClient"
    private val appContext = context.applicationContext
    private val running = AtomicBoolean(false)
    private val generation = AtomicInteger(0)
    private val sendLock = Any()
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var boundNetwork: Network? = null
    private var thread: Thread? = null
    private var reportedError = false

    @Synchronized
    fun start() {
        // A dead discovery thread must be restartable: runLoop() clears
        // `running` in its finally block, so a later refresh can recover.
        if (!running.compareAndSet(false, true)) return
        reportedError = false
        val session = generation.incrementAndGet()
        thread = Thread({ runLoop(session) }, "CastPro-UdpDiscovery").apply {
            isDaemon = true
            start()
        }
    }

    fun refresh() {
        val current = socket
        if (current == null) {
            // The loop may have died after a Wi-Fi change while `running` was
            // still true. Stop the dead session first so start() is not
            // swallowed by its guard and discovery can come back.
            if (running.get() && thread?.isAlive != true) stop()
            start()
            return
        }
        runCatching { sendProbe(current) }
    }

    private fun runLoop(session: Int) {
        var udp: DatagramSocket? = null
        try {
            val selectedNetwork = LocalNetwork.activeLanNetwork(appContext)
            val socket = DatagramSocket(null)
            udp = socket
            // Network.bindSocket must happen before bind/connect. The socket
            // then uses the selected Wi-Fi/Ethernet route even if a VPN or
            // auxiliary interface is the process default.
            if (selectedNetwork != null) {
                runCatching { selectedNetwork.bindSocket(socket) }
                    .onFailure { Log.w(tag, "Could not bind discovery to the LAN network", it) }
            }
            socket.bind(InetSocketAddress(0))
            // Set SO_BROADCAST after binding: Network.bindSocket() can reset
            // socket options, and OnePlus/vgate0 rejects broadcast probes with
            // EPERM when the flag is applied only before the bind.
            socket.broadcast = true
            socket.soTimeout = 500
            boundNetwork = selectedNetwork
            this.socket = socket
            while (running.get() && generation.get() == session) {
                sendProbe(socket)
                val deadline = SystemClock.elapsedRealtime() + Constants.DISCOVERY_INTERVAL_MS
                while (running.get() && generation.get() == session &&
                    SystemClock.elapsedRealtime() < deadline
                ) {
                    val buffer = ByteArray(2048)
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
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
            // Close this loop's own socket; a newer session may already have
            // replaced it, and must not be torn down by this old loop.
            runCatching { udp?.close() }
            if (this.socket === udp) {
                this.socket = null
                boundNetwork = null
            }
            if (generation.get() == session) {
                // Allow refresh()/start() to revive discovery after a
                // transient network error closed the socket.
                running.set(false)
            }
        }
    }

    private fun sendProbe(udp: DatagramSocket) {
        val request = Constants.DISCOVERY_REQUEST.toByteArray()
        val targets = LocalNetwork.broadcastAddresses(appContext, boundNetwork)
        var delivered = false
        synchronized(sendLock) {
            for (target in targets) {
                try {
                    udp.send(DatagramPacket(request, request.size, target, Constants.DEFAULT_DISCOVERY_PORT))
                    delivered = true
                } catch (e: Exception) {
                    Log.d(tag, "Probe to $target failed: ${e.message}")
                }
            }
        }
        if (delivered) reportedError = false
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

    @Synchronized
    fun stop() {
        // Invalidate the running loop before closing its socket so a quick
        // start() after stop() cannot be torn down by the old thread.
        generation.incrementAndGet()
        running.set(false)
        runCatching { socket?.close() }
        socket = null
        boundNetwork = null
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
    private val generation = AtomicInteger(0)
    private var socket: DatagramSocket? = null
    private var thread: Thread? = null

    @Synchronized
    fun start() {
        // A responder whose socket/thread died (Wi-Fi change, transient error)
        // must be startable again instead of being stuck "running" forever.
        if (running.get() && socket != null) return
        running.set(true)
        val session = generation.incrementAndGet()
        val udp = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(Constants.DEFAULT_DISCOVERY_PORT))
                soTimeout = 1000
            }
        } catch (e: Exception) {
            running.set(false)
            Log.e(tag, "Could not listen for discovery probes", e)
            onError("Discovery port ${Constants.DEFAULT_DISCOVERY_PORT} is busy")
            return
        }
        socket = udp

        val active = udp
        thread = Thread({
            val buffer = ByteArray(1024)
            try {
                while (running.get()) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        active.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    } catch (e: Exception) {
                        if (running.get()) Log.w(tag, "Discovery receive failed", e)
                        // A closed socket or a network change ends this loop;
                        // the finally block makes start() able to rebind.
                        break
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
                        active.send(DatagramPacket(payload, payload.size, packet.address, packet.port))
                    }
                }
            } finally {
                // Always close this loop's socket. Only clear shared state if
                // this exact session still owns it, so a stop()/start() pair
                // cannot let the old loop kill the new responder.
                runCatching { active.close() }
                if (socket === active && generation.get() == session) {
                    socket = null
                    running.set(false)
                }
            }
        }, "CastPro-UdpResponder").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun stop() {
        generation.incrementAndGet()
        running.set(false)
        runCatching { socket?.close() }
        socket = null
        thread?.interrupt()
        thread = null
    }
}
