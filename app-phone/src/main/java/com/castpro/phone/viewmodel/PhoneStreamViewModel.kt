package com.castpro.phone.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.castpro.core.common.model.DiscoveredReceiver
import com.castpro.core.common.model.LatencyMetrics
import com.castpro.core.common.model.StreamConfig
import com.castpro.core.common.protocol.Constants
import com.castpro.core.streaming.discovery.NsdHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class StreamStatus {
    IDLE,
    CONNECTING,
    STREAMING,
    ERROR
}

data class PhoneUiState(
    val status: StreamStatus = StreamStatus.IDLE,
    val isGamingMode: Boolean = true,
    val selectedReceiver: DiscoveredReceiver? = null,
    val discoveredReceivers: List<DiscoveredReceiver> = emptyList(),
    val manualIp: String = "",
    val errorMessage: String? = null,
    val metrics: LatencyMetrics = LatencyMetrics()
)

class PhoneStreamViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(PhoneUiState())
    val uiState: StateFlow<PhoneUiState> = _uiState.asStateFlow()

    private val nsdHelper = NsdHelper(application)

    init {
        startDiscovery()
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

    fun toggleGamingMode(enabled: Boolean) {
        _uiState.update { it.copy(isGamingMode = enabled) }
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

    fun getStreamConfig(): StreamConfig {
        val gaming = _uiState.value.isGamingMode
        return StreamConfig(
            width = 1920,
            height = 1080,
            fps = if (gaming) Constants.GAMING_FPS else 30,
            bitrate = if (gaming) Constants.GAMING_BITRATE_BPS else Constants.NORMAL_BITRATE_BPS,
            isGamingMode = gaming
        )
    }

    override fun onCleared() {
        super.onCleared()
        nsdHelper.stop()
    }
}
