package com.castpro.core.streaming.encoder

import android.os.Build
import android.util.Log
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
 * initialization or library loading fails, the established Kotlin MediaCodec
 * path is prepared instead.
 */
object ScreenEncoderFactory {
    private const val TAG = "ScreenEncoderFactory"

    /**
     * The stable Kotlin MediaCodec encoder over TCP is the production default.
     * NativeScreenEncoder remains experimental and is deliberately not selected
     * implicitly by gaming mode or Android version.
     */
    fun prepare(
        config: StreamConfig,
        transport: StreamTransport,
        onStats: ((EncoderStats) -> Unit)? = null,
        onError: (String) -> Unit = {}
    ): Pair<ScreenEncoderBackend, Surface> {
        Log.i(TAG, "Preparing stable hardware encoder: gaming=${config.isGamingMode} " +
            "sdk=${Build.VERSION.SDK_INT} codec=${config.codecMime} " +
            "${config.width}x${config.height}@${config.fps}")
        val hardware = HardwareScreenEncoder(
            config = config,
            transport = transport,
            onStats = onStats,
            onError = onError
        )
        val surface = hardware.prepare()
        hardware.start()
        Log.i(TAG, "Encoder selected: HardwareScreenEncoder over TCP")
        return hardware to surface
    }
}
