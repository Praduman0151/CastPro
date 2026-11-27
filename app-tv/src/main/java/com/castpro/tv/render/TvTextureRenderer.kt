package com.castpro.tv.render

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.TextureView
import com.castpro.core.common.model.GeometryPacket
import com.castpro.core.common.model.LatencyMetrics
import com.castpro.core.common.model.StreamConfig
import com.castpro.core.common.protocol.AnnexBCodecConfig
import com.castpro.core.streaming.decoder.DecoderCommand
import com.castpro.core.streaming.decoder.DecoderPipeline
import com.castpro.core.streaming.decoder.HardwareVideoDecoder
import com.castpro.tv.viewmodel.TvDisplayMode
import java.util.ArrayDeque
import java.util.Arrays
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
    private val stateLock = Any()
    @Volatile private var config: StreamConfig = initialConfig
    private var displayMode: TvDisplayMode = initialDisplayMode
    @Volatile private var decoder: HardwareVideoDecoder? = null
    @Volatile private var pipeline: DecoderPipeline? = null
    private var outputSurface: Surface? = null
    // Kept across SurfaceTexture destruction/recreation for this TCP session.
    @Volatile private var sessionCodecConfig: ByteArray? = null
    @Volatile private var renderedFrameUpdates = 0
    // Early packets can arrive before onSurfaceTextureAvailable fires.
    private var pendingConfig: StreamConfig? = null
    private val pendingFrames = ArrayDeque<DecoderCommand.Frame>()
    var isSurfaceReady = false
        private set

    init {
        textureView.surfaceTextureListener = this
        val existingTexture = textureView.surfaceTexture
        if (textureView.isAvailable && existingTexture != null) {
            onSurfaceTextureAvailable(existingTexture, textureView.width, textureView.height)
        }
    }

    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        isSurfaceReady = true
        sizeDecoderBuffer(surfaceTexture)
        val surface = Surface(surfaceTexture)
        outputSurface = surface
        val cachedCsd = sessionCodecConfig?.copyOf()
        val createdDecoder = HardwareVideoDecoder(
            initialConfig = config,
            surface = surface,
            initialScalingMode = displayMode.scalingMode,
            onMetricsUpdated = onMetricsUpdated,
            onError = onError,
            clockOffsetProvider = clockOffsetProvider,
            initialCodecConfig = cachedCsd
        )
        if (cachedCsd != null) {
            Log.i(tag, "Recreated decoder seeded with cached session CSD (${cachedCsd.size} bytes)")
        } else {
            Log.w(tag, "Surface recreated without cached CSD; waiting for inline parameter sets/IDR")
        }
        // All MediaCodec work (including reconfigure and keyframe buffer
        // waits) runs on this thread, never on the socket reader.
        val createdPipeline = DecoderPipeline(createdDecoder).apply { start() }
        synchronized(stateLock) {
            decoder = createdDecoder
            pipeline = createdPipeline
            pendingConfig?.let {
                createdPipeline.submitConfig(it)
                pendingConfig = null
            }
            while (pendingFrames.isNotEmpty()) {
                createdPipeline.submitFrame(pendingFrames.removeFirst())
            }
        }
        applyTransform(width, height)
    }

    override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        applyTransform(width, height)
    }

    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {
        renderedFrameUpdates++
        if (renderedFrameUpdates == 1) {
            Log.i(tag, "First rendered frame reached TextureView at wallTimeMs=${System.currentTimeMillis()} surface=${surfaceTexture.hashCode()}")
            onFrameRendered()
        }
    }

    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
        isSurfaceReady = false
        val activePipeline = synchronized(stateLock) {
            val current = pipeline
            pipeline = null
            decoder = null
            current
        }
        activePipeline?.stop()
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

    private fun updateSurfaceBufferAndTransformSync() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            textureView.surfaceTexture?.let(::sizeDecoderBuffer)
            applyTransform()
            return
        }
        val latch = CountDownLatch(1)
        val posted = textureView.post {
            try {
                textureView.surfaceTexture?.let(::sizeDecoderBuffer)
                applyTransform()
            } finally {
                latch.countDown()
            }
        }
        if (posted) {
            runCatching { latch.await(SURFACE_SYNC_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
        }
    }

    fun setDisplayMode(mode: TvDisplayMode) {
        displayMode = mode
        decoder?.setVideoScalingMode(mode.scalingMode)
        applyTransform()
    }

    fun onConfig(newConfig: StreamConfig) {
        // A new StreamConfig starts a new codec session; never reuse stale CSD.
        sessionCodecConfig = null
        config = newConfig
        // Apply SurfaceTexture.setDefaultBufferSize synchronously before
        // MediaCodec.reconfigure() starts on the Surface.
        updateSurfaceBufferAndTransformSync()
        synchronized(stateLock) {
            val activePipeline = pipeline
            if (activePipeline != null) {
                activePipeline.submitConfig(newConfig)
            } else {
                pendingConfig = newConfig
                pendingFrames.clear()
            }
        }
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
        if (isCodecConfig) {
            val validated = AnnexBCodecConfig.extractParameterSets(config.codecMime, data, offset, length)
            if (validated != null) {
                sessionCodecConfig = validated
                Log.i(tag, "Cached validated session CSD: ${validated.size} bytes")
            } else {
                Log.w(tag, "Ignoring invalid session codec config")
            }
        }
        synchronized(stateLock) {
            val activePipeline = pipeline
            if (activePipeline != null) {
                // Socket threads only copy the packet into a bounded queue. Decoding,
                // codec flushes and reconfigures never block TCP reads.
                activePipeline.submitFrame(data, offset, length, timestampNano, isGamingMode, isCodecConfig, isKeyframe)
            } else if (length > 0 && offset >= 0 && offset <= data.size - length) {
                val copy = Arrays.copyOfRange(data, offset, offset + length)
                val frame = DecoderCommand.Frame(
                    data = copy,
                    offset = 0,
                    length = copy.size,
                    timestampNano = timestampNano,
                    isGamingMode = isGamingMode,
                    isCodecConfig = isCodecConfig,
                    isKeyframe = isKeyframe
                )
                while (pendingFrames.size >= MAX_PENDING_EARLY_FRAMES) {
                    val evictable = pendingFrames.firstOrNull { !it.isCodecConfig && !it.isKeyframe }
                    if (evictable != null) {
                        pendingFrames.remove(evictable)
                    } else if (!frame.isCodecConfig && !frame.isKeyframe) {
                        return
                    } else {
                        pendingFrames.removeFirst()
                    }
                }
                pendingFrames.addLast(frame)
            }
        }
    }

    /** Must be called by the receiver owner when the TCP client disconnects. */
    fun onSessionDisconnected() {
        synchronized(stateLock) {
            sessionCodecConfig = null
            pendingConfig = null
            pendingFrames.clear()
            renderedFrameUpdates = 0
            decoder?.clearCodecConfig()
            pipeline?.dropPendingFrames()
            pipeline?.submitReset()
        }
        Log.i(tag, "Cleared cached codec config, pending frames, and render state after TCP session disconnect")
    }

    fun onGeometry(geometry: GeometryPacket) {
        // Rotation is capture metadata. The encoded frame dimensions remain the
        // source of truth for the transform, so quality or app changes cannot
        // move the picture.
        Log.d(tag, "Geometry ${geometry.width}x${geometry.height} rotation=${geometry.rotationDegrees}")
    }

    fun reset() {
        synchronized(stateLock) {
            pendingFrames.clear()
            pipeline?.submitReset()
        }
    }

    fun release() {
        val activePipeline = synchronized(stateLock) {
            pendingConfig = null
            pendingFrames.clear()
            val current = pipeline
            pipeline = null
            decoder = null
            current
        }
        activePipeline?.stop()
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

    companion object {
        private const val SURFACE_SYNC_TIMEOUT_MS = 250L
        private const val MAX_PENDING_EARLY_FRAMES = 30
    }
}
