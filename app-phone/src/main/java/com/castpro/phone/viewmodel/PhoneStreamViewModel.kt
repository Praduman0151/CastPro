package com.castpro.phone.viewmodel

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import com.castpro.core.common.model.*
import com.castpro.core.common.protocol.Constants
import com.castpro.core.streaming.discovery.NsdHelper
import com.castpro.core.streaming.qr.QrCodeGenerator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.net.Inet4Address
import java.net.NetworkInterface

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
    val localIp: String = "Detecting...",
    val webServerUrl: String = "",
    val webQrBitmap: Bitmap? = null,
    val errorMessage: String? = null,
    val metrics: LatencyMetrics = LatencyMetrics(),
    val sessionStats: CastingSessionStats = CastingSessionStats(),
    val activeNavTab: Int = 0 // 0 = Dashboard, 1 = QR Scanner, 2 = Settings, 3 = Telemetry
)

class PhoneStreamViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(PhoneUiState())
    val uiState: StateFlow<PhoneUiState> = _uiState.asStateFlow()

    private val nsdHelper = NsdHelper(application)

    init {
        detectLocalIp()
        startDiscovery()
    }

    private fun detectLocalIp() {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement() ?: continue
                val addresses = iface.inetAddresses ?: continue
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement() ?: continue
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val ip = addr.hostAddress ?: "127.0.0.1"
                        val webUrl = "http://$ip:${Constants.DEFAULT_WEB_PORT}"
                        val qr = QrCodeGenerator.generateQrBitmap(webUrl, size = 300)
                        _uiState.update {
                            it.copy(
                                localIp = ip,
                                webServerUrl = webUrl,
                                webQrBitmap = qr
                            )
                        }
                        return
                    }
                }
            }
        } catch (_: Exception) {}
    }

    fun startDiscovery() {
        nsdHelper.startDiscovery { receiver ->
            _uiState.update { state ->
                val current = state.discoveredReceivers.toMutableList()
                if (current.none { it.hostAddress == receiver.hostAddress }) {
                    current.add(receiver)
                }
                state.copy(discoveredReceivers = current)
            }
        }
    }

    fun setNavTab(tabIndex: Int) {
        _uiState.update { it.copy(activeNavTab = tabIndex) }
    }

    fun updateSettings(newSettings: StreamingSettings) {
        _uiState.update { it.copy(settings = newSettings) }
    }

    fun toggleGamingMode(enabled: Boolean) {
        _uiState.update { it.copy(settings = it.settings.copy(isGamingMode = enabled)) }
    }

    fun toggleWebServer(enabled: Boolean) {
        _uiState.update { it.copy(settings = it.settings.copy(enableWebServer = enabled)) }
    }

    fun selectReceiver(receiver: DiscoveredReceiver) {
        _uiState.update { it.copy(selectedReceiver = receiver, manualIp = receiver.hostAddress) }
    }

    fun updateManualIp(ip: String) {
        _uiState.update { it.copy(manualIp = ip) }
    }

    fun setStatus(status: StreamStatus, error: String? = null) {
        _uiState.update { it.copy(status = status, errorMessage = error) }
    }

    fun handleScannedQrCode(qrData: String): Boolean {
        // Formats: "castpro://connect?host=192.168.1.50&port=8998" or "192.168.1.50"
        return try {
            val ip = if (qrData.contains("host=")) {
                qrData.substringAfter("host=").substringBefore("&")
            } else if (qrData.startsWith("http://") || qrData.startsWith("https://")) {
                qrData.substringAfter("://").substringBefore(":").substringBefore("/")
            } else {
                qrData.trim()
            }
            if (ip.isNotBlank()) {
                _uiState.update { it.copy(manualIp = ip, activeNavTab = 0) }
                true
            } else false
        } catch (e: Exception) {
            false
        }
    }

    fun getStreamConfig(): StreamConfig {
        return _uiState.value.settings.toStreamConfig()
    }

    override fun onCleared() {
        super.onCleared()
        nsdHelper.stop()
    }
}
