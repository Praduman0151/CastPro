package com.castpro.core.streaming.decoder

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import com.castpro.core.common.model.LatencyMetrics
import com.castpro.core.common.model.StreamConfig
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class HardwareVideoDecoder(
    private val config: StreamConfig,
    private val surface: Surface,
    private val onMetricsUpdated: (LatencyMetrics) -> Unit
) {
    private val tag = "HardwareVideoDecoder"
    private var mediaCodec: MediaCodec? = null
    private val isRunning = AtomicBoolean(false)

    private var frameCount = 0
    private var lastFpsTimestamp = System.currentTimeMillis()
    private var totalBytesReceived = 0L

    fun start() {
        val format = MediaFormat.createVideoFormat(config.codecMime, config.width, config.height).apply {
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
        }

        val codec = MediaCodec.createDecoderByType(config.codecMime)
        codec.configure(format, surface, null, 0)
        codec.start()
        mediaCodec = codec
        isRunning.set(true)
    }

    fun decodeFrame(data: ByteArray, offset: Int, length: Int, timestampNano: Long, isGamingMode: Boolean) {
        val codec = mediaCodec ?: return
        if (!isRunning.get()) return

        val inputIndex = codec.dequeueInputBuffer(10_000)
        if (inputIndex >= 0) {
            val inputBuffer = codec.getInputBuffer(inputIndex)
            inputBuffer?.clear()
            inputBuffer?.put(data, offset, length)
            codec.queueInputBuffer(inputIndex, 0, length, timestampNano / 1000, 0)
        }

        val bufferInfo = MediaCodec.BufferInfo()
        var outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
        while (outputIndex >= 0) {
            // Render directly to the surface immediately (render = true)
            codec.releaseOutputBuffer(outputIndex, true)
            frameCount++
            totalBytesReceived += length

            // Calculate latency and FPS
            val nowNano = System.nanoTime()
            val latencyMs = ((nowNano - timestampNano).coerceAtLeast(0) / 1_000_000f)

            val nowMs = System.currentTimeMillis()
            if (nowMs - lastFpsTimestamp >= 1000) {
                val currentFps = frameCount
                val bitrateMbps = (totalBytesReceived * 8f) / (1_000_000f)
                
                onMetricsUpdated(
                    LatencyMetrics(
                        currentFps = currentFps,
                        latencyMs = latencyMs,
                        bitrateMbps = bitrateMbps,
                        isGamingModeActive = isGamingMode
                    )
                )

                frameCount = 0
                totalBytesReceived = 0L
                lastFpsTimestamp = nowMs
            }

            outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
        }
    }

    fun stop() {
        isRunning.set(false)
        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (e: Exception) {
            Log.e(tag, "Error releasing decoder", e)
        } finally {
            mediaCodec = null
        }
    }
}
