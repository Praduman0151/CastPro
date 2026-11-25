package com.castpro.phone.viewmodel

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.castpro.core.common.model.*
import com.castpro.core.common.protocol.Constants
import com.castpro.core.streaming.discovery.NsdHelper
import com.castpro.core.streaming.discovery.UdpDiscoveryClient
import com.castpro.core.streaming.network.LocalNetwork
import com.castpro.core.streaming.qr.QrCodeGenerator
import com.castpro.phone.SettingsStore
import com.castpro.phone.service.ScreenCaptureService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class StreamStatus {
    IDLE,
    CONNECTING,
    STREAMING,
    ERROR
}

data class PhoneUiState(
    val status: StreamStatus = StreamStatus.IDLE,
    val settings: StreamingSettings = StreamingSettings(),
    val selectedReceiver: DiscoveredReceiver? = null,
    val discoveredReceivers: List<DiscoveredReceiver> = emptyList(),
    val manualIp: String = "",
    val receiverPort: Int = Constants.DEFAULT_STREAM_PORT,
    val localIp: String = "Detecting…",
    val webServerUrl: String = "",
    val webQrBitmap: Bitmap? = null,
    val errorMessage: String? = null,
    val metrics: LatencyMetrics = LatencyMetrics(),
    /** Quality actually handed to the encoder, not just the saved setting. */
    val activeQualityLabel: String = "",
    val sessionStats: CastingSessionStats = CastingSessionStats(),
    val activeNavTab: Int = 0,
    val isReceiverConnected: Boolean = false,
    val connectionStatus: String = "Searching for TV…",
    val isScanning: Boolean = true
)

class PhoneStreamViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val settingsStore = SettingsStore(application)
    private var qualityApplyJob: Job? = null
    private val nsdHelper = NsdHelper(application)
    private val udpDiscovery = UdpDiscoveryClient(
        onReceiverFound = ::mergeReceiver,
        onError = { message -> updateConnectionError(message) }
    )

    private val _uiState = MutableStateFlow(PhoneUiState())
    val uiState: StateFlow<PhoneUiState> = _uiState.asStateFlow()

    init {
        val settings = settingsStore.load()
        val lastReceiver = settingsStore.loadLastReceiver()
        val initialReceiver = lastReceiver?.let {
            DiscoveredReceiver(
                deviceName = it.name ?: "Last TV",
                hostAddress = it.host,
                port = it.port,
                source = DiscoverySource.MANUAL
            )
        }
        _uiState.value = PhoneUiState(
            settings = settings,
            selectedReceiver = initialReceiver,
            manualIp = lastReceiver?.host.orEmpty(),
            receiverPort = lastReceiver?.port ?: Constants.DEFAULT_STREAM_PORT,
            connectionStatus = if (lastReceiver != null) "Ready to reconnect" else "Searching for TV…"
        )
        refreshLocalNetwork()
        startDiscovery()

        viewModelScope.launch {
            CastSessionBus.state.collect { telemetry -> applyTelemetry(telemetry) }
        }
        viewModelScope.launch {
            while (isActive) {
                delay(1_000)
                val telemetry = CastSessionBus.state.value
                if (telemetry.isStreaming) {
                    _uiState.update {
                        it.copy(
                            sessionStats = it.sessionStats.copy(
                                sessionDurationMs = telemetry.durationMs
                            )
                        )
                    }
                }
            }
        }
    }

    fun refreshLocalNetwork() {
        val ip = LocalNetwork.activeIpv4(app)
        _uiState.update { state ->
            val webPort = state.settings.webPort.coerceIn(1024, 65000)
            val webUrl = if (ip == null) "" else "http://$ip:$webPort"
            state.copy(
                localIp = ip ?: "No Wi-Fi / LAN",
                webServerUrl = webUrl,
                webQrBitmap = if (webUrl.isBlank()) null else {
                    QrCodeGenerator.generateQrBitmap(webUrl, size = 360)
                }
            )
        }
    }

    fun startDiscovery() {
        _uiState.update {
            it.copy(
                isScanning = true,
                connectionStatus = if (it.discoveredReceivers.isEmpty()) "Searching for TV…" else it.connectionStatus
            )
        }
        nsdHelper.startDiscovery(
            onReceiverFound = ::mergeReceiver,
            onReceiverLost = ::removeReceiverByName
        )
        udpDiscovery.start()
        udpDiscovery.refresh()
    }

    private fun mergeReceiver(receiver: DiscoveredReceiver) {
        var selectedToSave: DiscoveredReceiver? = null
        _uiState.update { state ->
            val now = System.currentTimeMillis()
            val existing = state.discoveredReceivers.firstOrNull { it.hostAddress == receiver.hostAddress }
            val merged = receiver.copy(
                deviceName = receiver.deviceName.ifBlank {
                    existing?.deviceName ?: "Cast Pro TV"
                },
                lastSeenMs = now
            )
            val receivers = (state.discoveredReceivers
                .filterNot { it.hostAddress == receiver.hostAddress } + merged)
                .sortedBy { it.deviceName }

            val current = state.selectedReceiver
            val stillPresent = current != null && receivers.any {
                it.hostAddress == current.hostAddress
            }
            val nextSelected = when {
                current?.hostAddress == receiver.hostAddress -> merged
                state.settings.autoConnect && (current == null || !stillPresent) -> merged
                else -> current
            }

            if (nextSelected != null && nextSelected.hostAddress != current?.hostAddress) {
                selectedToSave = nextSelected
            }

            state.copy(
                discoveredReceivers = receivers,
                selectedReceiver = nextSelected,
                manualIp = nextSelected?.hostAddress ?: state.manualIp,
                receiverPort = nextSelected?.port ?: state.receiverPort,
                isScanning = false,
                connectionStatus = when {
                    state.status == StreamStatus.STREAMING -> state.connectionStatus
                    nextSelected != null -> "TV found: ${nextSelected.deviceName}"
                    else -> "Searching for TV…"
                }
            )
        }
        selectedToSave?.let { settingsStore.saveLastReceiver(it.toPairingInfo()) }
    }

    private fun removeReceiverByName(serviceName: String) {
        _uiState.update { state ->
            val removed = state.discoveredReceivers.filterNot { it.deviceName == serviceName }
            val current = state.selectedReceiver
            val stillPresent = current != null && removed.any { it.hostAddress == current.hostAddress }
            state.copy(
                discoveredReceivers = removed,
                selectedReceiver = if (stillPresent) current else null,
                connectionStatus = if (removed.isEmpty()) "Searching for TV…" else state.connectionStatus
            )
        }
    }

    fun setNavTab(tabIndex: Int) {
        _uiState.update { it.copy(activeNavTab = tabIndex) }
    }

    fun updateSettings(newSettings: StreamingSettings) {
        val previous = _uiState.value.settings
        settingsStore.save(newSettings)
        _uiState.update { state ->
            state.copy(
                settings = newSettings,
                selectedReceiver = if (newSettings.autoConnect && state.selectedReceiver == null) {
                    state.discoveredReceivers.firstOrNull()
                } else {
                    state.selectedReceiver
                }
            )
        }
        if (previous.webPort != newSettings.webPort) refreshLocalNetwork()
        if (newSettings.autoConnect) {
            _uiState.value.discoveredReceivers.firstOrNull()?.let { selectReceiver(it) }
        }
        if (_uiState.value.status == StreamStatus.STREAMING && streamQualityChanged(previous, newSettings)) {
            scheduleLiveQualityApply()
        }
    }

    private fun streamQualityChanged(a: StreamingSettings, b: StreamingSettings): Boolean =
        a.resolution != b.resolution || a.framerate != b.framerate ||
            a.bitrate != b.bitrate || a.codec != b.codec

    /** Debounced so tapping through presets does not restart the encoder repeatedly. */
    private fun scheduleLiveQualityApply() {
        qualityApplyJob?.cancel()
        qualityApplyJob = viewModelScope.launch {
            delay(400)
            if (_uiState.value.status != StreamStatus.STREAMING) return@launch
            val settings = _uiState.value.settings
            val config = settings.toStreamConfig(
                targetPort = resolvedTarget()?.port ?: _uiState.value.receiverPort
            )
            val intent = Intent(app, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_UPDATE_CONFIG
                putExtra(ScreenCaptureService.EXTRA_WIDTH, config.width)
                putExtra(ScreenCaptureService.EXTRA_HEIGHT, config.height)
                putExtra(ScreenCaptureService.EXTRA_DPI, config.dpi)
                putExtra(ScreenCaptureService.EXTRA_BITRATE, config.bitrate)
                putExtra(ScreenCaptureService.EXTRA_FPS, config.fps)
                putExtra(ScreenCaptureService.EXTRA_CODEC, config.codecMime)
                putExtra(ScreenCaptureService.EXTRA_GAMING_MODE, config.isGamingMode)
            }
            app.startForegroundService(intent)
            _uiState.update {
                it.copy(
                    activeQualityLabel = qualityLabel(settings),
                    connectionStatus = "Applying ${qualityLabel(settings)}…"
                )
            }
        }
    }

    fun setActiveQuality(settings: StreamingSettings) {
        _uiState.update { it.copy(activeQualityLabel = qualityLabel(settings)) }
    }

    fun qualityLabel(settings: StreamingSettings): String =
        "${settings.resolution.width}x${settings.resolution.height} · " +
            "${settings.framerate.fps} fps · ${settings.codec.name} · " +
            "${String.format("%.1f", settings.bitrate.bps / 1_000_000f)} Mb/s"

    fun applyOptimizationProfile(profile: OptimizationProfile) {
        val current = _uiState.value.settings
        val tuned = when (profile) {
            OptimizationProfile.CUSTOM -> current.copy(profile = OptimizationProfile.CUSTOM)
            OptimizationProfile.BATTERY -> current.copy(
                profile = profile,
                resolution = Resolution.HD_720P,
                framerate = Framerate.FPS_30,
                bitrate = BitratePreset.BALANCED_8M,
                isGamingMode = false
            )
            OptimizationProfile.BALANCED -> current.copy(
                profile = profile,
                resolution = Resolution.FHD_1080P,
                framerate = Framerate.FPS_60,
                bitrate = BitratePreset.GAMING_15M,
                isGamingMode = true
            )
            OptimizationProfile.LOW_LATENCY -> current.copy(
                profile = profile,
                resolution = Resolution.HD_720P,
                framerate = Framerate.FPS_60,
                bitrate = BitratePreset.BALANCED_8M,
                isGamingMode = true
            )
            OptimizationProfile.QUALITY -> current.copy(
                profile = profile,
                resolution = Resolution.QHD_1440P,
                framerate = Framerate.FPS_60,
                bitrate = BitratePreset.ULTRA_25M,
                isGamingMode = true
            )
        }
        updateSettings(tuned)
    }

    fun toggleGamingMode(enabled: Boolean) {
        updateSettings(_uiState.value.settings.copy(profile = OptimizationProfile.CUSTOM, isGamingMode = enabled))
    }

    fun toggleWebServer(enabled: Boolean) {
        updateSettings(_uiState.value.settings.copy(profile = OptimizationProfile.CUSTOM, enableWebServer = enabled))
    }

    fun toggleAutoConnect(enabled: Boolean) {
        updateSettings(_uiState.value.settings.copy(profile = OptimizationProfile.CUSTOM, autoConnect = enabled))
    }

    fun selectReceiver(receiver: DiscoveredReceiver) {
        _uiState.update {
            it.copy(
                selectedReceiver = receiver,
                manualIp = receiver.hostAddress,
                receiverPort = receiver.port,
                connectionStatus = "Ready: ${receiver.deviceName}"
            )
        }
        settingsStore.saveLastReceiver(receiver.toPairingInfo())
    }

    fun updateManualIp(ip: String) {
        val parsed = PairingInfo.parse(ip)
        _uiState.update { state ->
            val matchesSelected = state.selectedReceiver != null &&
                parsed?.host == state.selectedReceiver.hostAddress
            state.copy(
                manualIp = ip,
                receiverPort = parsed?.port ?: state.receiverPort,
                selectedReceiver = if (matchesSelected) state.selectedReceiver else null,
                connectionStatus = when {
                    state.status == StreamStatus.STREAMING -> state.connectionStatus
                    ip.isBlank() -> "Searching for TV…"
                    parsed != null -> "Ready: ${parsed.host}"
                    else -> "Enter a valid IP or host"
                }
            )
        }
    }

    fun setStatus(status: StreamStatus, error: String? = null) {
        _uiState.update { it.copy(status = status, errorMessage = error) }
    }

    fun resolvedTarget(): PairingInfo? {
        val state = _uiState.value
        val selected = state.selectedReceiver
        val parsed = PairingInfo.parse(state.manualIp)
        // Keep the discovered name. Parsing the manual IP alone drops it, and
        // the TV name is part of the connected status the user sees.
        if (selected != null && (parsed == null || parsed.host == selected.hostAddress)) {
            return selected.toPairingInfo()
        }
        return parsed ?: selected?.toPairingInfo()
    }

    fun handleScannedQrCode(qrData: String): Boolean {
        val info = PairingInfo.parse(qrData) ?: return false
        val receiver = DiscoveredReceiver(
            deviceName = info.name ?: "Paired TV",
            hostAddress = info.host,
            port = info.port,
            source = DiscoverySource.QR
        )
        _uiState.update { state ->
            val receivers = (state.discoveredReceivers
                .filterNot { it.hostAddress == receiver.hostAddress } + receiver)
            state.copy(
                discoveredReceivers = receivers,
                selectedReceiver = receiver,
                manualIp = receiver.hostAddress,
                receiverPort = receiver.port,
                activeNavTab = 0,
                errorMessage = null,
                connectionStatus = "Paired: ${receiver.deviceName}",
                isScanning = false
            )
        }
        settingsStore.saveLastReceiver(info)
        return true
    }

    fun getStreamConfig(): StreamConfig {
        val state = _uiState.value
        val target = resolvedTarget()
        return state.settings.toStreamConfig(targetPort = target?.port ?: state.receiverPort)
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun onResume() {
        refreshLocalNetwork()
        if (_uiState.value.discoveredReceivers.isEmpty()) startDiscovery()
        else udpDiscovery.refresh()
    }

    private fun applyTelemetry(telemetry: SessionTelemetry) {
        _uiState.update { state ->
            val connected = telemetry.isReceiverConnected
            val statusText = when (telemetry.connectionState) {
                SessionConnectionState.IDLE -> "Searching for TV…"
                SessionConnectionState.SEARCHING -> if (telemetry.webClients > 0) {
                    "Browser viewer connected"
                } else {
                    "Searching for TV…"
                }
                SessionConnectionState.CONNECTING -> "Connecting to TV…"
                SessionConnectionState.CONNECTED -> telemetry.receiverName
                    ?.let { "Connected • $it" }
                    ?: "Connected"
                SessionConnectionState.DISCONNECTED -> "Disconnected • reconnecting…"
                SessionConnectionState.FAILED -> telemetry.connectionError
                    ?.let { "Connection Failed • $it" }
                    ?: "Connection Failed"
            }
            val metrics = state.metrics.copy(
                currentFps = telemetry.currentFps,
                bitrateMbps = telemetry.bitrateMbps,
                latencyMs = if (connected) state.metrics.latencyMs else -1f,
                isGamingModeActive = state.settings.isGamingMode
            )
            state.copy(
                status = if (!telemetry.isStreaming) {
                    if (telemetry.connectionState == SessionConnectionState.IDLE) {
                        StreamStatus.IDLE
                    } else {
                        // Discovery is not a cast session; keep the dashboard's
                        // own "searching / ready" status.
                        state.status
                    }
                } else {
                    when (telemetry.connectionState) {
                        SessionConnectionState.CONNECTED -> StreamStatus.STREAMING
                        SessionConnectionState.CONNECTING -> StreamStatus.CONNECTING
                        SessionConnectionState.SEARCHING -> StreamStatus.CONNECTING
                        SessionConnectionState.DISCONNECTED -> StreamStatus.CONNECTING
                        SessionConnectionState.FAILED -> StreamStatus.ERROR
                        SessionConnectionState.IDLE -> StreamStatus.IDLE
                    }
                },
                metrics = metrics,
                isReceiverConnected = connected,
                connectionStatus = statusText,
                sessionStats = state.sessionStats.copy(
                    sessionDurationMs = telemetry.durationMs,
                    totalFramesEncoded = telemetry.framesSent,
                    totalBytesTransferred = telemetry.bytesSent,
                    totalFramesDropped = telemetry.droppedFrames,
                    currentFps = telemetry.currentFps,
                    averageFps = if (telemetry.durationMs > 0) {
                        (telemetry.framesSent * 1_000f / telemetry.durationMs)
                    } else 0f,
                    currentLatencyMs = state.metrics.latencyMs,
                    currentBitrateMbps = telemetry.bitrateMbps,
                    activeReceiversCount = if (connected) 1 else 0,
                    isAudioMuted = state.settings.audioMode == AudioMode.MUTED
                ),
                errorMessage = telemetry.error ?: state.errorMessage
            )
        }
    }

    private fun updateConnectionError(message: String) {
        _uiState.update {
            it.copy(
                isScanning = false,
                connectionStatus = "Discovery unavailable — use Direct IP",
                errorMessage = message
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        nsdHelper.stop()
        udpDiscovery.stop()
    }
}
