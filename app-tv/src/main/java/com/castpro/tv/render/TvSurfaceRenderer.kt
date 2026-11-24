package com.castpro.tv.render

import android.view.SurfaceHolder
import android.view.SurfaceView
import com.castpro.core.common.model.GeometryPacket
import com.castpro.core.common.model.LatencyMetrics
import com.castpro.core.common.model.StreamConfig
import com.castpro.core.streaming.decoder.HardwareVideoDecoder

class TvSurfaceRenderer(
    private val surfaceView: SurfaceView,
    initialConfig: StreamConfig,
    private val onMetricsUpdated: (LatencyMetrics) -> Unit,
    private val onError: (String) -> Unit = {}
) : SurfaceHolder.Callback {

    private var config: StreamConfig = initialConfig
    private var decoder: HardwareVideoDecoder? = null
    var isSurfaceReady = false
        private set

    init {
        surfaceView.holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        isSurfaceReady = true
        decoder = HardwareVideoDecoder(
            initialConfig = config,
            surface = holder.surface,
            onMetricsUpdated = onMetricsUpdated,
            onError = onError
        ).apply { start() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        isSurfaceReady = false
        decoder?.stop()
        decoder = null
    }

    fun onConfig(newConfig: StreamConfig) {
        config = newConfig
        decoder?.reconfigure(newConfig)
    }

    fun onFrame(
        data: ByteArray,
        offset: Int,
        length: Int,
        timestampNano: Long,
        isGamingMode: Boolean,
        isCodecConfig: Boolean,
        isKeyframe: Boolean
    ) {
        decoder?.decodeFrame(data, offset, length, timestampNano, isGamingMode, isCodecConfig, isKeyframe)
    }

    fun onGeometry(geometry: GeometryPacket) {
        // The Compose layout uses the ViewModel's aspect ratio; the decoder
        // itself does not need to resize for a rotation inside the same stream.
    }

    fun reset() {
        decoder?.reset()
    }

    fun release() {
        decoder?.stop()
        decoder = null
    }
}
