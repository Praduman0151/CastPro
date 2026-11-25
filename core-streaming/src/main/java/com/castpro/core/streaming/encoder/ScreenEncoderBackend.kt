package com.castpro.core.streaming.encoder

import android.view.Surface
import com.castpro.core.common.model.StreamConfig
import com.castpro.core.streaming.transport.StreamTransport

interface ScreenEncoderBackend {
    fun prepare(): Surface
    fun start()
    fun requestKeyFrame()
    fun stop()
}

/**
 * Tries the NDK surface encoder on Android 10+ gaming sessions. If native
 * initialization fails, the established Kotlin MediaCodec path is prepared.
 */
object ScreenEncoderFactory {
    fun prepare(
        config: StreamConfig,
        transport: StreamTransport,
        onStats: ((EncoderStats) -> Unit)? = null,
        onError: (String) -> Unit = {}
    ): Pair<ScreenEncoderBackend, Surface> {
        if (android.os.Build.VERSION.SDK_INT >= 29 && config.isGamingMode) {
            val native = NativeScreenEncoder(config, transport, onStats, onError)
            try {
                val surface = native.prepare()
                native.start()
                return native to surface
            } catch (error: Throwable) {
                native.stop()
                android.util.Log.w("ScreenEncoderFactory", "Native backend unavailable; using Kotlin encoder", error)
                onError("Native encoder unavailable; using compatibility encoder")
            }
        }
        val compatibility = HardwareScreenEncoder(
            config = config,
            transport = transport,
            onStats = onStats,
            onError = onError
        )
        val surface = compatibility.prepare()
        compatibility.start()
        return compatibility to surface
    }
}
