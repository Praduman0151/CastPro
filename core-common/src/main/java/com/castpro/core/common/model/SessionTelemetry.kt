package com.castpro.core.common.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Live state of the sender pipeline. The capture service writes it and the
 * phone UI reads it, so the dashboard, HUD and telemetry page show real values
 * instead of placeholders. It is process-local and resets for every session.
 */
enum class SessionConnectionState {
    IDLE,
    SEARCHING,
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    FAILED
}

data class SessionTelemetry(
    val isStreaming: Boolean = false,
    val isReceiverConnected: Boolean = false,
    val host: String? = null,
    val receiverName: String? = null,
    val connectionState: SessionConnectionState = SessionConnectionState.SEARCHING,
    val connectionError: String? = null,
    val currentFps: Int = 0,
    val bitrateMbps: Float = 0f,
    val framesSent: Long = 0L,
    val bytesSent: Long = 0L,
    val droppedFrames: Long = 0L,
    val webClients: Int = 0,
    val error: String? = null,
    val startedAtMs: Long = 0L
) {
    val durationMs: Long
        get() = if (startedAtMs <= 0L) 0L else (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L)
}

object CastSessionBus {
    private val _state = MutableStateFlow(SessionTelemetry())
    val state: StateFlow<SessionTelemetry> = _state.asStateFlow()

    fun start(host: String?) {
        _state.value = SessionTelemetry(
            isStreaming = true,
            host = host,
            startedAtMs = System.currentTimeMillis()
        )
    }

    fun stop(error: String? = null) {
        _state.update {
            it.copy(
                isStreaming = false,
                isReceiverConnected = false,
                currentFps = 0,
                bitrateMbps = 0f,
                connectionState = SessionConnectionState.IDLE,
                connectionError = null,
                error = error
            )
        }
    }

    fun setConnectionState(
        state: SessionConnectionState,
        host: String? = null,
        receiverName: String? = null,
        error: String? = null
    ) {
        _state.update {
            it.copy(
                connectionState = state,
                host = host ?: it.host,
                receiverName = receiverName ?: it.receiverName,
                connectionError = when {
                    state == SessionConnectionState.FAILED -> error
                    state == SessionConnectionState.CONNECTED -> null
                    else -> it.connectionError
                }
            )
        }
    }

    fun setReceiverConnected(connected: Boolean, host: String? = null) {
        _state.update {
            it.copy(
                isReceiverConnected = connected,
                host = host ?: it.host,
                error = if (connected) null else it.error
            )
        }
    }

    fun setError(message: String?) {
        _state.update { it.copy(error = message) }
    }

    fun setWebClients(count: Int) {
        _state.update { it.copy(webClients = count) }
    }

    fun updateStats(
        fps: Int,
        bitrateMbps: Float,
        framesSent: Long,
        bytesSent: Long,
        droppedFrames: Long = 0L
    ) {
        _state.update {
            it.copy(
                currentFps = fps,
                bitrateMbps = bitrateMbps,
                framesSent = framesSent,
                bytesSent = bytesSent,
                droppedFrames = droppedFrames
            )
        }
    }
}
