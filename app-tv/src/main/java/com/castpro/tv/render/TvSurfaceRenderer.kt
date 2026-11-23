package com.castpro.tv.render

import android.view.SurfaceHolder
import android.view.SurfaceView
import com.castpro.core.common.model.LatencyMetrics
import com.castpro.core.common.model.StreamConfig
import com.castpro.core.streaming.decoder.HardwareVideoDecoder

class TvSurfaceRenderer(
    private val surfaceView: SurfaceView,
    private val config: StreamConfig,
    private val onMetricsUpdated: (LatencyMetrics) -> Unit
) : SurfaceHolder.Callback {

    private var decoder: HardwareVideoDecoder? = null
    var isSurfaceReady = false
        private set

    init {
        surfaceView.holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        isSurfaceReady = true
        decoder = HardwareVideoDecoder(config, holder.surface, onMetricsUpdated).apply {
            start()
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        isSurfaceReady = false
        decoder?.stop()
        decoder = null
    }

    fun onFrame(data: ByteArray, offset: Int, length: Int, timestampNano: Long, isGamingMode: Boolean) {
        decoder?.decodeFrame(data, offset, length, timestampNano, isGamingMode)
    }

    fun release() {
        decoder?.stop()
        decoder = null
    }
}
