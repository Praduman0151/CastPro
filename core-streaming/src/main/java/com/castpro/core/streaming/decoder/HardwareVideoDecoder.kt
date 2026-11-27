package com.castpro.core.streaming.decoder

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
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
    private val clockOffsetProvider: () -> Long? = { null }
) : VideoDecoderSink {
    private val tag = "HardwareVideoDecoder"
    private val lock = Any()
    @Volatile private var config: StreamConfig = initialConfig
    private var mediaCodec: MediaCodec? = null
    private val running = AtomicBoolean(false)
    private var waitingForKeyframe = true
    private val outputInfo = MediaCodec.BufferInfo()
    // MediaCodec loses codec-specific data on flush/reconfigure/restart. Keep
    // the last SPS/PPS (or VPS/SPS/PPS) so every new codec can be primed.
    private var cachedCodecConfig: ByteArray? = null

    private var frameCount = 0
    private var bytesInWindow = 0L
    private var lastFpsTimestamp = SystemClock.elapsedRealtime()
    private var loggedInputPackets = 0
    private var loggedOutputBuffers = 0

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

    override fun reconfigure(newConfig: StreamConfig) {
        synchronized(lock) {
            // The config packet is resent on every connection and quality
            // change. Even when the format is unchanged, flush so the decoder
            // waits for the sender's fresh IDR instead of decoding against a
            // stale reference picture from the previous socket.
            if (newConfig.codecMime == config.codecMime &&
                newConfig.width == config.width &&
                newConfig.height == config.height
            ) {
                config = newConfig
                reset()
                return
            }
            Log.i(tag, "Reconfiguring decoder: ${newConfig.codecMime} ${newConfig.width}x${newConfig.height}")
            stopCodec()
            cachedCodecConfig = null
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
                codec = createDecoder(preferredName)

                val capabilities = codec.codecInfo
                    ?.getCapabilitiesForType(config.codecMime)
                    ?.videoCapabilities
                val width = align(config.width, capabilities?.widthAlignment ?: 1)
                val height = align(config.height, capabilities?.heightAlignment ?: 1)
                if (capabilities != null && !capabilities.isSizeSupported(width, height)) {
                    throw IllegalStateException("Decoder does not support ${width}x${height}")
                }

                // Low latency is a hint. Some TV codecs advertise it but
                // reject KEY_LOW_LATENCY in configure(); retry the same codec
                // without the hint before falling back to software decoding.
                try {
                    codec.configure(decoderFormat(width, height, lowLatency = true), surface, null, 0)
                } catch (lowLatencyError: Exception) {
                    Log.w(tag, "Low-latency decoder format rejected; retrying without it", lowLatencyError)
                    runCatching { codec.release() }
                    codec = createDecoder(preferredName)
                    codec.configure(decoderFormat(width, height, lowLatency = false), surface, null, 0)
                }
                runCatching { codec.setVideoScalingMode(videoScalingMode) }
                codec.start()
                mediaCodec = codec
                waitingForKeyframe = true
                cachedCodecConfig?.let { configBytes ->
                    queueCodecConfig(codec, configBytes, 0, configBytes.size)
                }
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

    private fun createDecoder(preferredName: String?): MediaCodec =
        (if (preferredName == null) {
            MediaCodec.createDecoderByType(config.codecMime)
        } else {
            MediaCodec.createByCodecName(preferredName)
        }) ?: throw IllegalStateException("No decoder for ${config.codecMime}")

    private fun decoderFormat(width: Int, height: Int, lowLatency: Boolean): MediaFormat =
        MediaFormat.createVideoFormat(config.codecMime, width, height).apply {
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            // A full IDR at 25 Mbps can be larger than the decoder's
            // default input buffer; without this, put() throws on the
            // first keyframe (about one second into the stream).
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_BYTES)
            if (lowLatency && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
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

    override fun reset() {
        synchronized(lock) {
            runCatching { mediaCodec?.flush() }
            // flush() discards codec-specific data on many frameworks.
            mediaCodec?.let { codec ->
                cachedCodecConfig?.let { configBytes ->
                    queueCodecConfig(codec, configBytes, 0, configBytes.size)
                }
            }
            waitingForKeyframe = true
            resetMetrics()
        }
    }

    private fun queueCodecConfig(codec: MediaCodec, data: ByteArray, offset: Int, length: Int): Boolean {
        if (length <= 0 || offset < 0 || offset > data.size - length) return false
        val inputIndex = codec.dequeueInputBuffer(10_000)
        if (inputIndex < 0) {
            Log.w(tag, "No input buffer available to resubmit codec config")
            return false
        }
        val buffer = codec.getInputBuffer(inputIndex)
        if (buffer == null || length > buffer.remaining()) {
            Log.w(tag, "Cannot resubmit codec config: buffer unavailable or too small")
            codec.queueInputBuffer(inputIndex, 0, 0, System.nanoTime() / 1_000L, 0)
            return false
        }
        buffer.put(data, offset, length)
        codec.queueInputBuffer(
            inputIndex,
            0,
            length,
            System.nanoTime() / 1_000L,
            MediaCodec.BUFFER_FLAG_CODEC_CONFIG
        )
        return true
    }

    /**
     * Called only from [DecoderPipeline]'s thread. Returns true when MediaCodec
     * accepted the frame; false means its reference state is unknown and the
     * pipeline must wait for a fresh IDR before feeding P-frames again.
     */
    override fun decodeFrame(frame: DecoderCommand.Frame): Boolean {
        val data = frame.data
        val offset = frame.offset
        val length = frame.length
        val timestampNano = frame.timestampNano
        val isGamingMode = frame.isGamingMode
        val isCodecConfig = frame.isCodecConfig
        synchronized(lock) {
            if (length <= 0 || offset < 0 || offset > data.size - length) {
                Log.w(tag, "Rejecting invalid video payload range: offset=$offset length=$length size=${data.size}")
                return false
            }
            // Count bytes received from the network, even when decoder backpressure
            // causes an input packet to be dropped. This is receive bitrate, not
            // merely the subset of bytes successfully queued into MediaCodec.
            if (!isCodecConfig && length > 0) bytesInWindow += length.toLong()

            // The sender marks IDRs; the byte scan is only a fallback for
            // older senders that emit Annex-B start codes.
            val keyFrame = frame.isKeyframe || isKeyFrame(data, offset, length)
            if (loggedInputPackets < 5) {
                loggedInputPackets++
                Log.i(
                    tag,
                    "Video input #$loggedInputPackets bytes=$length codecConfig=$isCodecConfig keyframe=$keyFrame"
                )
            }
            if (mediaCodec == null && keyFrame && surface.isValid) {
                // A previous decode error stopped the codec. Rebuild it on the
                // next IDR instead of leaving the TV on a frozen frame.
                startCodec()
            }
            val codec = mediaCodec ?: return false
            if (!running.get()) return false

            try {
                if (isCodecConfig) {
                    if (length > 0 && offset >= 0 && offset <= data.size - length) {
                        cachedCodecConfig = data.copyOfRange(offset, offset + length)
                    }
                    val queued = queueCodecConfig(codec, data, offset, length)
                    if (!queued) waitingForKeyframe = true
                    if (running.get() && mediaCodec === codec) {
                        drainOutput(codec, timestampNano, isGamingMode)
                    }
                    return queued
                }

                // Parameter sets are safe (and necessary) before the first IDR.
                // Queue them even while waiting, but keep waiting for a real IDR.
                if (waitingForKeyframe && !keyFrame) return false

                // This runs on the decoder thread, not on the TCP reader. A
                // bounded keyframe wait lets a large IDR acquire an input
                // buffer without ever stalling socket reads; P-frames are
                // dropped immediately because late frames are worthless.
                val inputIndex = codec.dequeueInputBuffer(if (keyFrame) KEYFRAME_WAIT_MS else 0L)
                if (inputIndex < 0) {
                    waitingForKeyframe = true
                    return false
                }

                // Once dequeueInputBuffer() succeeds, this index must be
                // returned to MediaCodec. Leaving it unqueued can gradually
                // starve the decoder after a transient buffer problem.
                val presentationTimeUs = System.nanoTime() / 1_000L
                val inputBuffer = codec.getInputBuffer(inputIndex)
                if (inputBuffer == null) {
                    Log.w(tag, "Decoder returned a null input buffer; returning the slot empty")
                    codec.queueInputBuffer(inputIndex, 0, 0, presentationTimeUs, 0)
                    waitingForKeyframe = true
                    return false
                }
                if (offset < 0 || length <= 0 || offset > data.size - length) {
                    Log.w(tag, "Rejecting invalid video payload range: offset=$offset length=$length size=${data.size}")
                    codec.queueInputBuffer(inputIndex, 0, 0, presentationTimeUs, 0)
                    waitingForKeyframe = true
                    return false
                }
                if (length > inputBuffer.remaining()) {
                    // Never truncate an access unit: a partial NAL/frame can
                    // poison decoder state and produce a green/corrupt picture.
                    Log.w(tag, "Dropping oversized video access unit: $length bytes, buffer=${inputBuffer.remaining()}")
                    codec.queueInputBuffer(inputIndex, 0, 0, presentationTimeUs, 0)
                    waitingForKeyframe = true
                    return false
                }
                inputBuffer.put(data, offset, length)
                // MediaCodec presentation times must come from a monotonic
                // clock. The phone's wall-clock stamp is only for latency
                // reporting; feeding it here makes SurfaceFlinger delay frames
                // and the picture appears to run in slow motion.
                codec.queueInputBuffer(inputIndex, 0, length, presentationTimeUs, 0)
                if (keyFrame) waitingForKeyframe = false
                return true
            } catch (e: Throwable) {
                Log.e(tag, "Decode error", e)
                // Once MediaCodec throws, its state is not trustworthy. Stop it
                // and rebuild from the next IDR instead of queuing into a dead
                // decoder for the rest of the session.
                stopCodec()
                waitingForKeyframe = true
                onError(e.message ?: "Video decoder error")
                return false
            } finally {
                // Output can be produced after an earlier input queue operation,
                // not only after the most recent successful queue. Draining here
                // prevents already-decoded frames from sitting unrendered while
                // the decoder waits for the next command.
                if (running.get() && mediaCodec === codec) {
                    drainOutput(codec, timestampNano, isGamingMode)
                }
            }
        }
    }

    private fun drainOutput(codec: MediaCodec, timestampNano: Long, isGamingMode: Boolean) {
        val info = outputInfo
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

            if (loggedOutputBuffers < 5) {
                loggedOutputBuffers++
                Log.i(
                    tag,
                    "Decoded output #$loggedOutputBuffers size=${info.size} flags=${info.flags} ptsUs=${info.presentationTimeUs}"
                )
            }
            val isConfigOutput = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
            // Empty/config buffers are not pictures. Rendering them produces a
            // blank/green TextureView update and inflates the FPS counter.
            val render = info.size > 0 && !isConfigOutput
            codec.releaseOutputBuffer(outputIndex, render)
            if (render) frameCount++

            // Wall clocks can jump (NTP, manual changes, sleep). FPS windows
            // and latency samples must use a monotonic clock instead.
            val nowMonoMs = SystemClock.elapsedRealtime()
            if (nowMonoMs - lastFpsTimestamp >= 1_000L) {
                val elapsed = (nowMonoMs - lastFpsTimestamp).coerceAtLeast(1)
                val fps = ((frameCount * 1_000L) / elapsed).toInt()
                val bitrate = (bytesInWindow * 8f) / elapsed / 1_000f
                // The offset is learned from heartbeats as
                // (phoneMonotonicNs - tvMonotonicNs - oneWay). Adding it to a
                // current monotonic sample cancels the epoch difference.
                // Before calibration latency is unknown, not zero.
                val latencyMs = clockOffsetProvider()?.let { offset ->
                    val nowMonoNs = SystemClock.elapsedRealtimeNanos()
                    (nowMonoNs + offset - timestampNano)
                        .coerceIn(0L, 1_000_000_000L) / 1_000_000f
                } ?: -1f
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
                lastFpsTimestamp = nowMonoMs
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
        lastFpsTimestamp = SystemClock.elapsedRealtime()
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

    override fun stop() {
        synchronized(lock) { stopCodec() }
    }

    companion object {
        /** Enough for a full frame at the maximum supported bitrate. */
        private const val MAX_INPUT_BYTES = 4 * 1024 * 1024
        /** Bounded keyframe wait: fits a large IDR, never stalls socket reads. */
        private const val KEYFRAME_WAIT_MS = 50L
    }
}
