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
 * initialization or library loading fails, the established Kotlin MediaCodec
 * path is prepared instead.
 */
object ScreenEncoderFactory {
    fun prepare(
        config: StreamConfig,
        transport: StreamTransport,
        onStats: ((EncoderStats) -> Unit)? = null,
        onError: (String) -> Unit = {}
    ): Pair<ScreenEncoderBackend, Surface> {
        if (android.os.Build.VERSION.SDK_INT >= 29 && config.isGamingMode) {
            var native: NativeScreenEncoder? = null
            try {
                // Keep construction inside the guarded block: the class initializer
                // loads the JNI library and can throw UnsatisfiedLinkError before
                // prepare() is reached on devices/ABIs without the native binary.
                val candidate = NativeScreenEncoder(config, transport, onStats, onError)
                native = candidate
                val surface = candidate.prepare()
                candidate.start()
                return candidate to surface
            } catch (error: LinkageError) {
                native?.stop()
                android.util.Log.w(
                    "ScreenEncoderFactory",
                    "Native library unavailable; using Kotlin compatibility encoder",
                    error
                )
                onError("Native encoder unavailable; using compatibility encoder")
            } catch (error: Exception) {
                native?.stop()
                android.util.Log.w(
                    "ScreenEncoderFactory",
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
        return compatibility to surface
    }
}
