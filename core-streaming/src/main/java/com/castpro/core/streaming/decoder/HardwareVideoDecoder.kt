package com.castpro.core.streaming.decoder

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import com.castpro.core.common.model.LatencyMetrics
import com.castpro.core.common.model.StreamConfig
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil

/**
 * Hardware TV decoder. It is configured from the stream config packet sent by
 * the phone, so changing H.264/HEVC or resolution no longer leaves the TV
 * decoding with stale parameters. After a reconnect it waits for an IDR before
 * queueing anything, which prevents the frozen/corrupt first frame.
 */
class HardwareVideoDecoder(
    initialConfig: StreamConfig,
    private val surface: Surface,
    initialScalingMode: Int = MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING,
    private val onMetricsUpdated: (LatencyMetrics) -> Unit,
    private val onError: (String) -> Unit = {},
    private val clockOffsetProvider: () -> Long = { 0L }
) {
    private val tag = "HardwareVideoDecoder"
    private val lock = Any()
    @Volatile private var config: StreamConfig = initialConfig
    private var mediaCodec: MediaCodec? = null
    private val running = AtomicBoolean(false)
    private var waitingForKeyframe = true

    private var frameCount = 0
    private var bytesInWindow = 0L
    private var lastFpsTimestamp = System.currentTimeMillis()

    @Volatile private var decodedWidth: Int = config.width
    @Volatile private var decodedHeight: Int = config.height
    @Volatile private var decodedCodecLabel: String = codecDisplayName(config.codecMime)
    @Volatile private var videoScalingMode: Int = initialScalingMode

    fun start() {
        synchronized(lock) { startCodec() }
    }

    /** Called by the TV when the user changes display mode during a cast. */
    fun setVideoScalingMode(mode: Int) {
        if (videoScalingMode == mode) return
        videoScalingMode = mode
        synchronized(lock) {
            val codec = mediaCodec ?: return
            val applied = runCatching { codec.setVideoScalingMode(mode) }.isSuccess
            if (!applied) {
                // A few TV codecs only accept this before start(). Recreate
                // the decoder in that case; the phone will resend a keyframe.
                stopCodec()
                startCodec()
            }
        }
    }

    fun reconfigure(newConfig: StreamConfig) {
        synchronized(lock) {
            if (newConfig.codecMime == config.codecMime &&
                newConfig.width == config.width &&
                newConfig.height == config.height
            ) {
                config = newConfig
                return
            }
            Log.i(tag, "Reconfiguring decoder: ${newConfig.codecMime} ${newConfig.width}x${newConfig.height}")
            stopCodec()
            config = newConfig
            // Reset telemetry to the new configuration instead of showing the
            // previous resolution until the next output-format callback.
            decodedWidth = newConfig.width
            decodedHeight = newConfig.height
            decodedCodecLabel = codecDisplayName(newConfig.codecMime)
            resetMetrics()
            startCodec()
        }
    }

    private fun startCodec() {
        if (!running.compareAndSet(false, true)) return
        if (!surface.isValid) {
            running.set(false)
            onError("The TV display surface closed before the decoder started.")
            return
        }

        // Try the platform's preferred hardware decoder first, then the
        // software AVC/HEVC decoder. Some TV SoCs advertise a codec but reject
        // configure() or start() for the phone's resolution; without a fallback
        // the receiver reports an error and never draws another frame.
        val candidates = listOf(null) + softwareDecoderNames()
        var lastError: Exception? = null
        for (preferredName in candidates) {
            var codec: MediaCodec? = null
            try {
                codec = if (preferredName == null) {
                    MediaCodec.createDecoderByType(config.codecMime)
                } else {
                    MediaCodec.createByCodecName(preferredName)
                } ?: throw IllegalStateException("No decoder for ${config.codecMime}")

                val capabilities = codec.codecInfo
                    ?.getCapabilitiesForType(config.codecMime)
                    ?.videoCapabilities
                val width = align(config.width, capabilities?.widthAlignment ?: 1)
                val height = align(config.height, capabilities?.heightAlignment ?: 1)
                if (capabilities != null && !capabilities.isSizeSupported(width, height)) {
                    throw IllegalStateException("Decoder does not support ${width}x${height}")
                }

                val format = MediaFormat.createVideoFormat(config.codecMime, width, height).apply {
                    setInteger(MediaFormat.KEY_PRIORITY, 0)
                    // A full IDR at 25 Mbps can be larger than the decoder's
                    // default input buffer; without this, put() throws on the
                    // first keyframe (about one second into the stream).
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_BYTES)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                    }
                }
                codec.configure(format, surface, null, 0)
                runCatching { codec.setVideoScalingMode(videoScalingMode) }
                codec.start()
                mediaCodec = codec
                waitingForKeyframe = true
                resetMetrics()
                Log.i(
                    tag,
                    "Decoder started: ${preferredName ?: "hardware"} " +
                        "${config.codecMime} ${width}x${height}"
                )
                onError("")
                return
            } catch (e: Exception) {
                lastError = e
                Log.w(tag, "Decoder ${preferredName ?: "hardware"} rejected the stream", e)
                codec?.let { runCatching { it.release() } }
            }
        }

        running.set(false)
        Log.e(tag, "Failed to initialize decoder for ${config.codecMime}", lastError)
        onError("This TV cannot decode ${config.codecMime}. Switch the phone to H.264.")
    }

    private fun softwareDecoderNames(): List<String> = when {
        config.codecMime.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) ->
            listOf("c2.android.avc.decoder", "OMX.google.h264.decoder")
        config.codecMime.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true) ->
            listOf("c2.android.hevc.decoder", "OMX.google.hevc.decoder")
        else -> emptyList()
    }

    private fun align(value: Int, alignment: Int): Int {
        if (alignment <= 1) return value
        return ceil(value.toFloat() / alignment).toInt() * alignment
    }

    fun reset() {
        synchronized(lock) {
            runCatching { mediaCodec?.flush() }
            waitingForKeyframe = true
            resetMetrics()
        }
    }

    fun decodeFrame(
        data: ByteArray,
        offset: Int,
        length: Int,
        timestampNano: Long,
        isGamingMode: Boolean,
        isCodecConfig: Boolean = false,
        isKeyframe: Boolean = false
    ) {
        synchronized(lock) {
            // The sender marks IDRs; the byte scan is only a fallback for
            // older senders that emit Annex-B start codes.
            val keyFrame = isKeyframe || isKeyFrame(data, offset, length)
            if (mediaCodec == null && keyFrame && surface.isValid) {
                // A previous decode error stopped the codec. Rebuild it on the
                // next IDR instead of leaving the TV on a frozen frame.
                startCodec()
            }
            val codec = mediaCodec ?: return
            if (!running.get()) return

            try {
                // Parameter sets are safe (and necessary) before the first IDR.
                // Queue them even while waiting, but keep waiting for a real IDR.
                if (waitingForKeyframe && !isCodecConfig && !keyFrame) return

                // Never block the network thread waiting for an input buffer.
                // A late frame is worthless in a low-latency cast; dropping it
                // keeps motion real-time instead of turning the cast into slow
                // motion. Keyframes still get a short wait because they unlock
                // everything that follows.
                val inputIndex = codec.dequeueInputBuffer(if (keyFrame) 10_000 else 0)
                if (inputIndex < 0) return

                val inputBuffer = codec.getInputBuffer(inputIndex) ?: return
                val bytesToQueue = minOf(length, inputBuffer.remaining())
                if (bytesToQueue <= 0) return
                inputBuffer.put(data, offset, bytesToQueue)
                // Parameter sets must be flagged as codec config or decoders
                // on many TVs reject the following IDR.
                val codecFlags = if (isCodecConfig) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
                // MediaCodec presentation times must come from a monotonic
                // clock. The phone's wall-clock stamp is only for latency
                // reporting; feeding it here makes SurfaceFlinger delay frames
                // and the picture appears to run in slow motion.
                val presentationTimeUs = System.nanoTime() / 1_000L
                codec.queueInputBuffer(inputIndex, 0, bytesToQueue, presentationTimeUs, codecFlags)
                if (!isCodecConfig && keyFrame) waitingForKeyframe = false
                bytesInWindow += bytesToQueue

                drainOutput(codec, timestampNano, isGamingMode)
            } catch (e: Throwable) {
                Log.e(tag, "Decode error", e)
                // Once MediaCodec throws, its state is not trustworthy. Stop it
                // and rebuild from the next IDR instead of queuing into a dead
                // decoder for the rest of the session.
                stopCodec()
                waitingForKeyframe = true
                onError(e.message ?: "Video decoder error")
            }
        }
    }

    private fun drainOutput(codec: MediaCodec, timestampNano: Long, isGamingMode: Boolean) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(info, 0)
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val format = codec.outputFormat
                decodedWidth = runCatching { format.getInteger(MediaFormat.KEY_WIDTH) }.getOrDefault(config.width)
                decodedHeight = runCatching { format.getInteger(MediaFormat.KEY_HEIGHT) }.getOrDefault(config.height)
                val mime = runCatching { format.getString(MediaFormat.KEY_MIME) }.getOrDefault(config.codecMime) ?: config.codecMime
                val profile = runCatching { format.getInteger(MediaFormat.KEY_PROFILE) }.getOrDefault(-1)
                val profileLabel = profileName(mime, profile)
                decodedCodecLabel = codecDisplayName(mime) + if (profileLabel.isBlank()) "" else " $profileLabel"
                Log.i(tag, "Decoder output format: $decodedCodecLabel ${decodedWidth}x${decodedHeight}")
                continue
            }
            if (outputIndex < 0) break

            codec.releaseOutputBuffer(outputIndex, true)
            frameCount++

            val nowMs = System.currentTimeMillis()
            if (nowMs - lastFpsTimestamp >= 1_000) {
                val elapsed = (nowMs - lastFpsTimestamp).coerceAtLeast(1)
                val fps = ((frameCount * 1_000L) / elapsed).toInt()
                val bitrate = (bytesInWindow * 8f) / elapsed / 1_000f
                // Remove the TV/phone wall-clock skew learned from heartbeats;
                // raw cross-device timestamps are meaningless when the two
                // clocks differ by seconds.
                val nowNs = nowMs * 1_000_000L
                val latencyNs = (nowNs - timestampNano - clockOffsetProvider())
                    .coerceIn(0L, 1_000_000_000L)
                val latencyMs = latencyNs / 1_000_000f
                onMetricsUpdated(
                    LatencyMetrics(
                        currentFps = fps,
                        latencyMs = latencyMs,
                        bitrateMbps = bitrate,
                        isGamingModeActive = isGamingMode,
                        resolutionLabel = "${decodedWidth}x${decodedHeight}",
                        codecLabel = decodedCodecLabel,
                        targetBitrateMbps = config.bitrate / 1_000_000f
                    )
                )
                frameCount = 0
                bytesInWindow = 0L
                lastFpsTimestamp = nowMs
            }
        }
    }

    private fun profileName(mime: String, profile: Int): String {
        if (profile <= 0) return ""
        return when {
            mime.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) -> when (profile) {
                1 -> "Baseline"
                2 -> "Main"
                3, 4 -> "High"
                else -> ""
            }
            mime.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true) -> when (profile) {
                1 -> "Main"
                2 -> "Main10"
                else -> ""
            }
            else -> ""
        }
    }

    private fun codecDisplayName(mime: String): String =
        if (mime.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true)) "H.265" else "H.264"

    private fun isKeyFrame(data: ByteArray, offset: Int, length: Int): Boolean {
        if (length < 5) return false
        val hevc = config.codecMime.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true)
        var index = offset
        val end = offset + length
        while (index + 4 < end) {
            if (data[index].toInt() == 0 && data[index + 1].toInt() == 0) {
                val header = when {
                    data[index + 2].toInt() == 1 -> index + 3
                    data[index + 2].toInt() == 0 && data[index + 3].toInt() == 1 -> index + 4
                    else -> {
                        index++
                        continue
                    }
                }
                if (header < end) {
                    // AVC stores nal_unit_type in the low 5 bits. HEVC
                    // stores it in bits 1..6 of the 2-byte NAL header.
                    val nalType = if (hevc) {
                        (data[header].toInt() ushr 1) and 0x3F
                    } else {
                        data[header].toInt() and 0x1F
                    }
                    if (hevc) {
                        // HEVC IRAP pictures: BLA/IDR/CRA (16..21). VPS/SPS/PPS
                        // (32..34) may precede the picture in the same buffer.
                        if (nalType in 16..21) return true
                    } else if (nalType == 5) {
                        return true
                    }
                }
                index = header
            } else {
                index++
            }
        }
        return false
    }

    private fun resetMetrics() {
        frameCount = 0
        bytesInWindow = 0L
        lastFpsTimestamp = System.currentTimeMillis()
    }

    private fun stopCodec() {
        running.set(false)
        try {
            mediaCodec?.stop()
        } catch (_: Exception) {
        } finally {
            runCatching { mediaCodec?.release() }
            mediaCodec = null
        }
    }

    fun stop() {
        synchronized(lock) { stopCodec() }
    }

    companion object {
        /** Enough for a full frame at the maximum supported bitrate. */
        private const val MAX_INPUT_BYTES = 4 * 1024 * 1024
    }
}
