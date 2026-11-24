package com.castpro.tv.viewmodel

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.castpro.core.common.model.GeometryPacket
import com.castpro.core.common.model.LatencyMetrics
import com.castpro.core.common.model.PairingInfo
import com.castpro.core.common.model.StreamConfig
import com.castpro.core.common.protocol.Constants
import com.castpro.core.common.protocol.StreamConfigPacket
import com.castpro.core.streaming.discovery.NsdHelper
import com.castpro.core.streaming.discovery.UdpDiscoveryResponder
import com.castpro.core.streaming.network.LocalNetwork
import com.castpro.core.streaming.qr.QrCodeGenerator
import com.castpro.core.streaming.transport.LowLatencySocketServer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TvUiState(
    val isStreaming: Boolean = false,
    val clientIp: String? = null,
    val localIpAddress: String = "Detecting…",
    val serviceName: String = "",
    val pairingUri: String = "",
    val qrCodeBitmap: Bitmap? = null,
    val streamConfig: StreamConfig = StreamConfig(),
    val aspectRatio: Float = 16f / 9f,
    val metrics: LatencyMetrics = LatencyMetrics(),
    val showHudOverlay: Boolean = true,
    val displayMode: TvDisplayMode = TvDisplayMode.FULL,
    val colorFilter: TvColorFilter = TvColorFilter.STANDARD,
    val connectionError: String? = null,
    val isServerRunning: Boolean = false,
    val lastCrash: String? = null
)

class TvReceiverViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val prefs = app.getSharedPreferences("castpro_tv_settings", Context.MODE_PRIVATE)
    private val _uiState = MutableStateFlow(TvUiState())
    val uiState: StateFlow<TvUiState> = _uiState.asStateFlow()

    private val nsdHelper = NsdHelper(application)
    private val udpResponder = UdpDiscoveryResponder(
        serviceName = defaultServiceName(),
        streamPort = Constants.DEFAULT_STREAM_PORT,
        webPort = Constants.DEFAULT_WEB_PORT,
        onError = { message -> _uiState.update { it.copy(connectionError = message) } }
    )
    private val connectivityManager = application.getSystemService(ConnectivityManager::class.java)

    private var socketServer: LowLatencySocketServer? = null
    private var retryJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** Clock difference between TV and phone, learned from heartbeat packets. */
    @Volatile
    private var clockOffsetNs: Long = 0L

    fun currentClockOffsetNs(): Long = clockOffsetNs

    /**
     * The phone stamps heartbeats with its wall clock. Their arrival time lets
     * the TV subtract the devices' clock skew from the video timestamps, which
     * is required for a meaningful one-way latency figure.
     */
    fun noteHeartbeat(senderTimestampNs: Long) {
        val receiveNs = System.currentTimeMillis() * 1_000_000L
        clockOffsetNs = receiveNs - senderTimestampNs - ASSUMED_ONE_WAY_NS
    }

    private var onStreamConfig: ((StreamConfigPacket) -> Unit)? = null
    private var onFrame: ((ByteArray, Int, Int, Long, Boolean, Boolean, Boolean) -> Unit)? = null
    private var onAudio: ((ByteArray, Int, Int) -> Unit)? = null
    private var onGeometry: ((GeometryPacket) -> Unit)? = null

    init {
        _uiState.update {
            it.copy(
                serviceName = defaultServiceName(),
                displayMode = runCatching {
                    TvDisplayMode.valueOf(
                        prefs.getString(KEY_DISPLAY_MODE, TvDisplayMode.FULL.name) ?: TvDisplayMode.FULL.name
                    )
                }.getOrDefault(TvDisplayMode.FULL),
                colorFilter = runCatching {
                    TvColorFilter.valueOf(
                        prefs.getString(KEY_COLOR_FILTER, TvColorFilter.STANDARD.name) ?: TvColorFilter.STANDARD.name
                    )
                }.getOrDefault(TvColorFilter.STANDARD),
                lastCrash = com.castpro.tv.tvCrashLogFile(app)
                    .takeIf { file -> file.exists() }
                    ?.readText()
                    ?.take(1_500)
            )
        }
        refreshNetwork()
        registerNsd()
        udpResponder.start()
        registerNetworkCallback()
    }

    private fun defaultServiceName(): String {
        val model = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android TV"
        return "${Constants.NSD_SERVICE_NAME_PREFIX}-$model".take(60)
    }

    fun refreshNetwork() {
        val ip = LocalNetwork.activeIpv4(app)
        val name = _uiState.value.serviceName.ifBlank { defaultServiceName() }
        if (ip == null) {
            _uiState.update {
                it.copy(
                    localIpAddress = "No LAN address",
                    qrCodeBitmap = null,
                    pairingUri = ""
                )
            }
            return
        }
        val pairing = PairingInfo(
            host = ip,
            port = Constants.DEFAULT_STREAM_PORT,
            name = name
        )
        _uiState.update {
            it.copy(
                localIpAddress = ip,
                pairingUri = pairing.toUri(),
                qrCodeBitmap = QrCodeGenerator.generateQrBitmap(pairing.toUri(), size = 520)
            )
        }
    }

    private fun registerNsd() {
        nsdHelper.registerTvService(
            port = Constants.DEFAULT_STREAM_PORT,
            onRegistered = { name ->
                _uiState.update { it.copy(serviceName = name) }
                refreshNetwork()
            },
            onError = { message ->
                _uiState.update { it.copy(connectionError = message) }
            }
        )
    }

    private fun registerNetworkCallback() {
        val manager = connectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                refreshNetwork()
            }

            override fun onLost(network: Network) {
                refreshNetwork()
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                refreshNetwork()
            }
        }
        networkCallback = callback
        runCatching { manager.registerDefaultNetworkCallback(callback) }
    }

    fun startServer(
        onStreamConfig: (StreamConfigPacket) -> Unit,
        onFrameReceived: (ByteArray, Int, Int, Long, Boolean, Boolean, Boolean) -> Unit,
        onAudioReceived: (ByteArray, Int, Int) -> Unit = { _, _, _ -> },
        onGeometryReceived: (GeometryPacket) -> Unit = {}
    ) {
        this.onStreamConfig = onStreamConfig
        this.onFrame = onFrameReceived
        this.onAudio = onAudioReceived
        this.onGeometry = onGeometryReceived
        if (socketServer != null) return
        createServer()
    }

    private fun createServer() {
        // The old listener still owns the port after an accept/bind error;
        // stop it before retrying or every retry fails with "address in use".
        socketServer?.stop()
        socketServer = null
        val server = LowLatencySocketServer(
            port = Constants.DEFAULT_STREAM_PORT,
            onFrameReceived = { data, offset, length, timestamp, gaming, codecConfig, isKeyframe ->
                onFrame?.invoke(data, offset, length, timestamp, gaming, codecConfig, isKeyframe)
            },
            onAudioReceived = { data, offset, length ->
                onAudio?.invoke(data, offset, length)
            },
            onGeometryReceived = { geometry ->
                onGeometry?.invoke(geometry)
            },
            onStreamConfigReceived = { config ->
                applyStreamConfig(config)
            },
            onHeartbeatReceived = { senderTimestampNs ->
                noteHeartbeat(senderTimestampNs)
            },
            onClientConnected = { ip ->
                retryJob?.cancel()
                _uiState.update {
                    it.copy(
                        isStreaming = true,
                        clientIp = ip,
                        connectionError = null
                    )
                }
            },
            onClientDisconnected = {
                _uiState.update { it.copy(isStreaming = false, clientIp = null) }
            },
            onError = { message ->
                _uiState.update { it.copy(connectionError = message, isServerRunning = false) }
                // Always close the old listener before retrying, otherwise its
                // bound port survives and every retry fails with EADDRINUSE.
                socketServer?.stop()
                socketServer = null
                retryJob?.cancel()
                retryJob = viewModelScope.launch {
                    delay(2_000)
                    createServer()
                }
            }
        )
        server.start()
        socketServer = server
        _uiState.update { it.copy(isServerRunning = true) }
    }

    private fun applyStreamConfig(packet: StreamConfigPacket) {
        val config = StreamConfig(
            width = packet.width,
            height = packet.height,
            fps = packet.fps,
            bitrate = packet.bitrate,
            isGamingMode = packet.isGamingMode,
            codecMime = packet.codec.mimeType
        )
        _uiState.update {
            it.copy(
                streamConfig = config,
                aspectRatio = if (packet.height > 0) packet.width.toFloat() / packet.height else 16f / 9f,
                connectionError = null
            )
        }
        onStreamConfig?.invoke(packet)
    }

    fun updateMetrics(metrics: LatencyMetrics) {
        _uiState.update { it.copy(metrics = metrics) }
    }

    fun dismissCrashReport() {
        com.castpro.tv.tvCrashLogFile(app).delete()
        _uiState.update { it.copy(lastCrash = null) }
    }

    /** Keeps decoder failures visible on the TV instead of only in logcat. */
    fun reportError(message: String) {
        _uiState.update { it.copy(connectionError = message) }
    }

    fun updateAspectRatio(ratio: Float) {
        if (ratio > 0.1f && ratio < 10f) {
            _uiState.update { it.copy(aspectRatio = ratio) }
        }
    }

    fun toggleHudOverlay() {
        _uiState.update { it.copy(showHudOverlay = !it.showHudOverlay) }
    }

    fun setDisplayMode(mode: TvDisplayMode) {
        prefs.edit().putString(KEY_DISPLAY_MODE, mode.name).apply()
        _uiState.update { it.copy(displayMode = mode) }
    }

    fun cycleDisplayMode() {
        val modes = TvDisplayMode.values()
        val next = modes[(_uiState.value.displayMode.ordinal + 1) % modes.size]
        setDisplayMode(next)
    }

    fun setColorFilter(filter: TvColorFilter) {
        prefs.edit().putString(KEY_COLOR_FILTER, filter.name).apply()
        _uiState.update { it.copy(colorFilter = filter) }
    }

    fun cycleColorFilter() {
        val filters = TvColorFilter.values()
        val next = filters[(_uiState.value.colorFilter.ordinal + 1) % filters.size]
        setColorFilter(next)
    }

    override fun onCleared() {
        super.onCleared()
        retryJob?.cancel()
        socketServer?.stop()
        socketServer = null
        nsdHelper.stop()
        udpResponder.stop()
        connectivityManager?.let { manager ->
            networkCallback?.let { runCatching { manager.unregisterNetworkCallback(it) } }
        }
        networkCallback = null
    }

    companion object {
        /** Typical LAN delivery time used to separate clock skew from latency. */
        private const val ASSUMED_ONE_WAY_NS = 2_000_000L
        private const val KEY_DISPLAY_MODE = "display_mode"
        private const val KEY_COLOR_FILTER = "color_filter"
    }
}
