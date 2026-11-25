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

    fun prepare(
        config: StreamConfig,
        transport: StreamTransport,
        onStats: ((EncoderStats) -> Unit)? = null,
        onError: (String) -> Unit = {}
    ): Pair<ScreenEncoderBackend, Surface> {
        Log.i(TAG, "Preparing encoder: gaming=${config.isGamingMode} sdk=${Build.VERSION.SDK_INT} " +
            "codec=${config.codecMime} ${config.width}x${config.height}@${config.fps}")
        if (Build.VERSION.SDK_INT >= 29 && config.isGamingMode) {
            var native: NativeScreenEncoder? = null
            try {
                // Keep construction inside the guarded block: the class initializer
                // loads the JNI library and can throw UnsatisfiedLinkError before
                // prepare() is reached on devices/ABIs without the native binary.
                val candidate = NativeScreenEncoder(config, transport, onStats, onError)
                native = candidate
                val surface = candidate.prepare()
                candidate.start()
                Log.i(TAG, "Encoder selected: Native Engine 2.0 (NDK MediaCodec)")
                return candidate to surface
            } catch (error: LinkageError) {
                native?.stop()
                Log.w(
                    TAG,
                    "Native library unavailable; using Kotlin compatibility encoder",
                    error
                )
                onError("Native encoder unavailable; using compatibility encoder")
            } catch (error: Exception) {
                native?.stop()
                Log.w(
                    TAG,
                    "Native encoder initialization failed; using Kotlin compatibility encoder",
                    error
                )
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
        Log.i(TAG, "Encoder selected: Kotlin compatibility encoder")
        return compatibility to surface
    }
}
