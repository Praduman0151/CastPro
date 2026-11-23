package com.castpro.tv.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.castpro.core.common.model.LatencyMetrics
import com.castpro.core.common.protocol.Constants
import com.castpro.core.streaming.discovery.NsdHelper
import com.castpro.core.streaming.transport.LowLatencySocketServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.net.Inet4Address
import java.net.NetworkInterface

data class TvUiState(
    val isStreaming: Boolean = false,
    val clientIp: String? = null,
    val localIpAddress: String = "Detecting...",
    val serviceName: String = "",
    val metrics: LatencyMetrics = LatencyMetrics(),
    val showHudOverlay: Boolean = true
)

class TvReceiverViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(TvUiState())
    val uiState: StateFlow<TvUiState> = _uiState.asStateFlow()

    private val nsdHelper = NsdHelper(application)
    private var socketServer: LowLatencySocketServer? = null

    init {
        detectLocalIp()
        registerNsd()
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
                        _uiState.update { it.copy(localIpAddress = addr.hostAddress ?: "Unknown") }
                        return
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun registerNsd() {
        nsdHelper.registerTvService(Constants.DEFAULT_STREAM_PORT) { name ->
            _uiState.update { it.copy(serviceName = name) }
        }
    }

    fun startServer(
        onFrameReceived: (ByteArray, Int, Int, Long, Boolean) -> Unit,
        onAudioReceived: (ByteArray, Int, Int) -> Unit = { _, _, _ -> }
    ) {
        val server = LowLatencySocketServer(
            port = Constants.DEFAULT_STREAM_PORT,
            onFrameReceived = onFrameReceived,
            onAudioReceived = onAudioReceived,
            onClientConnected = { ip ->
                _uiState.update { it.copy(isStreaming = true, clientIp = ip) }
            },
            onClientDisconnected = {
                _uiState.update { it.copy(isStreaming = false, clientIp = null) }
            }
        )
        server.start()
        socketServer = server
    }

    fun updateMetrics(metrics: LatencyMetrics) {
        _uiState.update { it.copy(metrics = metrics) }
    }

    fun toggleHudOverlay() {
        _uiState.update { it.copy(showHudOverlay = !it.showHudOverlay) }
    }

    override fun onCleared() {
        super.onCleared()
        socketServer?.stop()
        nsdHelper.stop()
    }
}
