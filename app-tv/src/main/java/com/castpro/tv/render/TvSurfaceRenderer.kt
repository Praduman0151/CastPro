package com.castpro.tv.render

import android.util.Log
import android.media.MediaCodec
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.castpro.core.common.model.GeometryPacket
import com.castpro.core.common.model.LatencyMetrics
import com.castpro.core.common.model.StreamConfig
import com.castpro.core.common.protocol.AnnexBCodecConfig
import com.castpro.core.streaming.decoder.HardwareVideoDecoder
import com.castpro.tv.viewmodel.TvDisplayMode

/**
 * Diagnostic SurfaceView renderer. It deliberately leaves TvTextureRenderer
 * untouched so the two Android output targets can be compared on one APK.
 */
class TvSurfaceRenderer(
    private val surfaceView: SurfaceView,
    initialConfig: StreamConfig,
    initialDisplayMode: TvDisplayMode,
    private val forceSoftwareAvc: Boolean = false,
    private val onMetricsUpdated: (LatencyMetrics) -> Unit,
    private val onVideoSizeChanged: (Int, Int) -> Unit = { _, _ -> },
    private val onError: (String) -> Unit = {},
    private val clockOffsetProvider: () -> Long? = { null },
    private val onFrameRendered: () -> Unit = {}
) : SurfaceHolder.Callback {
    private val tag = "TvSurfaceRenderer"
    private var config = initialConfig
    private var displayMode = initialDisplayMode
    private var decoder: HardwareVideoDecoder? = null
    private var sessionCodecConfig: ByteArray? = null
    private var reportedFirstFrame = false
    private var released = false

    init {
        surfaceView.holder.addCallback(this)
        Log.d(tag, "SURFACE_VIEW attached view=${surfaceView.hashCode()} forceSoftware=$forceSoftwareAvc")
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (released) return
        decoder?.stop()
        decoder = null
        reportedFirstFrame = false
        val surface = holder.surface
        Log.d(tag, "SURFACE_VIEW created valid=${surface.isValid} frame=${holder.surfaceFrame}")
        if (!surface.isValid) {
            onError("SurfaceView supplied an invalid decoder Surface.")
            return
        }
        decoder = createDecoder(surface).also { it.start() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.d(tag, "SURFACE_VIEW changed format=$format width=$width height=$height valid=${holder.surface.isValid}")
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.d(tag, "SURFACE_VIEW destroyed")
        decoder?.stop()
        decoder = null
        reportedFirstFrame = false
    }

    private fun createDecoder(surface: Surface): HardwareVideoDecoder =
        HardwareVideoDecoder(
            initialConfig = config,
            surface = surface,
            initialScalingMode = displayMode.scalingMode,
            onMetricsUpdated = { metrics ->
                onMetricsUpdated(metrics)
                if (!reportedFirstFrame) {
                    reportedFirstFrame = true
                    Log.i(tag, "SURFACE_VIEW first decoder output metrics received")
                    onFrameRendered()
                }
            },
            onVideoSizeChanged = { width, height ->
                surfaceView.post {
                    runCatching { surfaceView.holder.setFixedSize(width, height) }
                    onVideoSizeChanged(width, height)
                }
            },
            onError = onError,
            clockOffsetProvider = clockOffsetProvider,
            initialCodecConfig = sessionCodecConfig?.copyOf(),
            diagnosticDecoderName = if (forceSoftwareAvc) "c2.android.avc.decoder" else null
        )

    fun setDisplayMode(mode: TvDisplayMode) {
        displayMode = mode
        decoder?.setVideoScalingMode(mode.scalingMode)
        Log.d(tag, "SURFACE_VIEW display mode=${mode.label}")
    }

    fun onConfig(newConfig: StreamConfig) {
        sessionCodecConfig = null
        reportedFirstFrame = false
        config = newConfig
        decoder?.resetSession()
        decoder?.reconfigure(newConfig)
        Log.d(tag, "SURFACE_VIEW stream config=${newConfig.codecMime} ${newConfig.width}x${newConfig.height}")
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
        if (released) return
        if (length > 0) surfaceView.visibility = android.view.View.VISIBLE
        if (isCodecConfig) {
            val validated = AnnexBCodecConfig.extractParameterSets(config.codecMime, data, offset, length)
            if (validated != null) {
                sessionCodecConfig = validated
                Log.d(tag, "SURFACE_VIEW CSD cached bytes=${validated.size}")
            } else {
                Log.w(tag, "SURFACE_VIEW rejected invalid codec config")
            }
        }
        if (length > 0 && (isCodecConfig || isKeyframe)) {
            Log.d(tag, "SURFACE_VIEW packet bytes=$length codecConfig=$isCodecConfig keyframe=$isKeyframe")
        }
        decoder?.decodeFrame(data, offset, length, timestampNano, isGamingMode, isCodecConfig, isKeyframe)
    }

    fun onSessionDisconnected() {
        sessionCodecConfig = null
        reportedFirstFrame = false
        decoder?.resetSession()
        decoder?.stop()
        surfaceView.visibility = android.view.View.INVISIBLE
        runCatching {
            val canvas = surfaceView.holder.lockCanvas()
            if (canvas != null) {
                canvas.drawColor(android.graphics.Color.BLACK)
                surfaceView.holder.unlockCanvasAndPost(canvas)
            }
        }.onFailure { Log.w(tag, "Could not clear disconnected SurfaceView", it) }
        Log.i(tag, "SESSION_DISCONNECTED: cleared CSD, closed decode gate, released decoder and hid stale frame")
    }

    fun onGeometry(geometry: GeometryPacket) {
        Log.d(tag, "SURFACE_VIEW geometry=${geometry.width}x${geometry.height} rotation=${geometry.rotationDegrees}")
    }

    fun release() {
        if (released) return
        released = true
        decoder?.stop()
        decoder = null
        surfaceView.holder.removeCallback(this)
        Log.d(tag, "SURFACE_VIEW renderer released")
    }
}
