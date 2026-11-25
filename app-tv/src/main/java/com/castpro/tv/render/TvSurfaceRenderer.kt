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
    initialScalingMode: Int = android.media.MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING,
    private val onMetricsUpdated: (LatencyMetrics) -> Unit,
    private val onError: (String) -> Unit = {},
    private val clockOffsetProvider: () -> Long = { 0L }
) : SurfaceHolder.Callback {

    private var config: StreamConfig = initialConfig
    private var scalingMode: Int = initialScalingMode
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
            initialScalingMode = scalingMode,
            onMetricsUpdated = onMetricsUpdated,
            onError = onError,
            clockOffsetProvider = clockOffsetProvider
        ).apply { start() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // A Compose/FIT size change recreates the surface buffer. Reapply the
        // active crop mode so the decoder does not keep a stale crop rect.
        if (width > 0 && height > 0) {
            decoder?.setVideoScalingMode(scalingMode)
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        isSurfaceReady = false
        decoder?.stop()
        decoder = null
    }

    fun setVideoScalingMode(mode: Int) {
        scalingMode = mode
        decoder?.setVideoScalingMode(mode)
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
