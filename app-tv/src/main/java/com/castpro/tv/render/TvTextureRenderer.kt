package com.castpro.tv.render

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.util.Log
import android.view.Surface
import android.view.TextureView
import com.castpro.core.common.model.GeometryPacket
import com.castpro.core.common.model.LatencyMetrics
import com.castpro.core.common.model.StreamConfig
import com.castpro.core.streaming.decoder.HardwareVideoDecoder
import com.castpro.tv.viewmodel.TvDisplayMode
import kotlin.math.max
import kotlin.math.min

/**
 * TV video renderer backed by a TextureView.
 *
 * MediaCodec has no true non-uniform stretch mode: SCALE_TO_FIT letterboxes
 * and SCALE_TO_FIT_WITH_CROPPING crops. For STRETCH we therefore give the
 * decoder a SurfaceTexture sized to the encoded frame (no decoder scaling),
 * then map that whole texture to the whole view with independent X/Y scales.
 * FIT and FILL use a uniform scale inside the same full-window view.
 */
class TvTextureRenderer(
    private val textureView: TextureView,
    initialConfig: StreamConfig,
    initialDisplayMode: TvDisplayMode,
    private val onMetricsUpdated: (LatencyMetrics) -> Unit,
    private val onError: (String) -> Unit = {},
    private val clockOffsetProvider: () -> Long? = { null },
    private val onFrameRendered: () -> Unit = {}
) : TextureView.SurfaceTextureListener {

    private val tag = "TvTextureRenderer"
    private var config: StreamConfig = initialConfig
    private var displayMode: TvDisplayMode = initialDisplayMode
    private var decoder: HardwareVideoDecoder? = null
    private var outputSurface: Surface? = null
    private var renderedFrameUpdates = 0
    var isSurfaceReady = false
        private set

    init {
        textureView.surfaceTextureListener = this
    }

    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        isSurfaceReady = true
        sizeDecoderBuffer(surfaceTexture)
        val surface = Surface(surfaceTexture)
        outputSurface = surface
        decoder = HardwareVideoDecoder(
            initialConfig = config,
            surface = surface,
            initialScalingMode = displayMode.scalingMode,
            onMetricsUpdated = onMetricsUpdated,
            onError = onError,
            clockOffsetProvider = clockOffsetProvider
        ).apply { start() }
        applyTransform(width, height)
    }

    override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        applyTransform(width, height)
    }

    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {
        renderedFrameUpdates++
        if (renderedFrameUpdates == 1) {
            Log.i(tag, "First decoded frame reached the TextureView surface")
            onFrameRendered()
        }
    }

    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
        isSurfaceReady = false
        decoder?.stop()
        decoder = null
        outputSurface?.release()
        outputSurface = null
        return true
    }

    /** The decoder writes a full, uncropped frame into the SurfaceTexture. */
    private fun sizeDecoderBuffer(surfaceTexture: SurfaceTexture) {
        val width = config.width.coerceAtLeast(16)
        val height = config.height.coerceAtLeast(16)
        runCatching { surfaceTexture.setDefaultBufferSize(width, height) }
            .onFailure { Log.w(tag, "Could not size decoder buffer to ${width}x${height}", it) }
    }

    fun setDisplayMode(mode: TvDisplayMode) {
        displayMode = mode
        decoder?.setVideoScalingMode(mode.scalingMode)
        applyTransform()
    }

    fun onConfig(newConfig: StreamConfig) {
        config = newConfig
        textureView.surfaceTexture?.let(::sizeDecoderBuffer)
        decoder?.reconfigure(newConfig)
        applyTransform()
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
        // Rotation is capture metadata. The encoded frame dimensions remain the
        // source of truth for the transform, so quality or app changes cannot
        // move the picture.
        Log.d(tag, "Geometry ${geometry.width}x${geometry.height} rotation=${geometry.rotationDegrees}")
    }

    fun reset() {
        decoder?.reset()
    }

    fun release() {
        decoder?.stop()
        decoder = null
        outputSurface?.release()
        outputSurface = null
        textureView.surfaceTextureListener = null
    }

    private fun applyTransform(viewWidth: Int = textureView.width, viewHeight: Int = textureView.height) {
        if (viewWidth <= 0 || viewHeight <= 0) return
        val videoWidth = config.width.coerceAtLeast(1).toFloat()
        val videoHeight = config.height.coerceAtLeast(1).toFloat()

        val scaleX: Float
        val scaleY: Float
        val translateX: Float
        val translateY: Float

        when (displayMode) {
            TvDisplayMode.STRETCH -> {
                // Independent axes: every decoded pixel maps to the full panel.
                // No crop, no letterbox, centred by construction.
                scaleX = viewWidth / videoWidth
                scaleY = viewHeight / videoHeight
                translateX = (viewWidth - videoWidth * scaleX) / 2f
                translateY = (viewHeight - videoHeight * scaleY) / 2f
            }
            TvDisplayMode.FIT -> {
                val scale = min(viewWidth / videoWidth, viewHeight / videoHeight)
                scaleX = scale
                scaleY = scale
                translateX = (viewWidth - videoWidth * scale) / 2f
                translateY = (viewHeight - videoHeight * scale) / 2f
            }
            TvDisplayMode.FILL -> {
                val scale = max(viewWidth / videoWidth, viewHeight / videoHeight)
                scaleX = scale
                scaleY = scale
                translateX = (viewWidth - videoWidth * scale) / 2f
                translateY = (viewHeight - videoHeight * scale) / 2f
            }
        }

        val transform = Matrix().apply {
            setScale(scaleX, scaleY)
            postTranslate(translateX, translateY)
        }
        textureView.setTransform(transform)
        Log.i(
            tag,
            "mode=${displayMode.label} panel=${viewWidth}x${viewHeight} " +
                "frame=${config.width}x${config.height} scale=${String.format("%.4f", scaleX)}x" +
                String.format("%.4f", scaleY) + " offset=$translateX,$translateY"
        )
    }
}
