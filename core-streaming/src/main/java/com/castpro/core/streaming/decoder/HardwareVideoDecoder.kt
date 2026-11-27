package com.castpro.core.streaming.decoder

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import com.castpro.core.common.model.LatencyMetrics
import com.castpro.core.common.model.StreamConfig
import com.castpro.core.common.protocol.AnnexBCodecConfig
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
    initialScalingMode: Int = MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT,
    private val onMetricsUpdated: (LatencyMetrics) -> Unit,
    private val onVideoSizeChanged: (Int, Int) -> Unit = { _, _ -> },
    private val onError: (String) -> Unit = {},
    private val clockOffsetProvider: () -> Long? = { null },
    initialCodecConfig: ByteArray? = null,
    /** Diagnostic-only codec override; null preserves platform hardware-first selection. */
    private val diagnosticDecoderName: String? = null
) {
    private val tag = "HardwareVideoDecoder"
    private val lock = Any()
    @Volatile private var config: StreamConfig = initialConfig
    private var mediaCodec: MediaCodec? = null
    private val running = AtomicBoolean(false)
    private val decodeGate = VideoDecodeGate()
    // MediaCodec loses codec-specific data on flush/reconfigure/restart. Keep
    // the last SPS/PPS (or VPS/SPS/PPS) so every new codec can be primed.
    private var cachedCodecConfig: ByteArray? = initialCodecConfig?.let { bytes ->
        AnnexBCodecConfig.extractParameterSets(config.codecMime, bytes)
    }
    // Tracks CSD already queued into the current MediaCodec instance.
    private var lastSubmittedCsd: ByteArray? = null

    private var frameCount = 0
    private var bytesInWindow = 0L
    private var lastFpsTimestamp = System.currentTimeMillis()
    private var loggedInputPackets = 0
    private var loggedOutputBuffers = 0
    @Volatile private var outputDrainThread: Thread? = null
    @Volatile private var lastInputTimestampNano: Long = 0L
    @Volatile private var lastInputGamingMode: Boolean = false

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

    /**
     * A new TCP session must not inherit the previous peer's codec-config or
     * keyframe gate, even when the resolution and MIME type are unchanged.
     */
    fun resetSession() {
        synchronized(lock) {
            cachedCodecConfig = null
            lastSubmittedCsd = null
            decodeGate.resetForSession()
            lastInputTimestampNano = 0L
            lastInputGamingMode = false
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
            cachedCodecConfig = null
            lastSubmittedCsd = null
            decodeGate.resetForReconfigure()
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
        lastSubmittedCsd = null
        if (!surface.isValid) {
            running.set(false)
            onError("The TV display surface closed before the decoder started.")
            return
        }

        // Try the platform's preferred hardware decoder first, then the
        // software AVC/HEVC decoder. Some TV SoCs advertise a codec but reject
        // configure() or start() for the phone's resolution; without a fallback
        // the receiver reports an error and never draws another frame.
        val candidates = diagnosticDecoderName?.let { listOf(it) }
            ?: (listOf(null) + softwareDecoderNames())
        if (diagnosticDecoderName != null &&
            !config.codecMime.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true)
        ) {
            running.set(false)
            onError("Forced diagnostic decoder is only supported for H.264/AVC.")
            Log.e(tag, "Ignoring diagnostic decoder override for mime=${config.codecMime}")
            return
        }
        var lastError: Exception? = null
        for (preferredName in candidates) {
            // Each candidate is a new MediaCodec instance; CSD submitted to a
            // failed candidate must never suppress priming the next one.
            lastSubmittedCsd = null
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
                val width = align(config.width, maxOf(16, capabilities?.widthAlignment ?: 1))
                val height = align(config.height, maxOf(16, capabilities?.heightAlignment ?: 1))
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
                codec.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT)
                codec.start()
                mediaCodec = codec
                decodeGate.resetForFlush()
                cachedCodecConfig?.let { configBytes ->
                    if (AnnexBCodecConfig.hasRequiredParameterSets(config.codecMime, configBytes) &&
                        queueCodecConfig(codec, configBytes, 0, configBytes.size)
                    ) {
                        decodeGate.markCodecConfigAvailable()
                    }
                }
                resetMetrics()
                Log.i(
                    tag,
                    "Decoder started: name=${codec.name} preferred=${preferredName ?: "hardware"} " +
                        "${config.codecMime} ${width}x${height} format=$format"
                )
                startOutputDrain(codec)
                onError("")
                return
            } catch (e: Exception) {
                lastError = e
                Log.w(tag, "Decoder ${preferredName ?: "hardware"} rejected the stream", e)
                if (mediaCodec === codec) mediaCodec = null
                lastSubmittedCsd = null
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
            // flush() discards codec-specific data on many frameworks.
            lastSubmittedCsd = null
            decodeGate.resetForFlush()
            mediaCodec?.let { codec ->
                cachedCodecConfig?.let { configBytes ->
                    if (AnnexBCodecConfig.hasRequiredParameterSets(config.codecMime, configBytes) &&
                        queueCodecConfig(codec, configBytes, 0, configBytes.size)
                    ) {
                        decodeGate.markCodecConfigAvailable()
                    }
                }
            }
            resetMetrics()
        }
    }

    private fun queueCodecConfig(codec: MediaCodec, data: ByteArray, offset: Int, length: Int): Boolean {
        if (length <= 0 || offset < 0 || offset > data.size - length) return false
        val csd = data.copyOfRange(offset, offset + length)
        if (lastSubmittedCsd?.contentEquals(csd) == true) {
            Log.d(tag, "Skipping duplicate codec config already submitted to ${codec.name}: bytes=$length")
            return true
        }
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
        Log.d(
            tag,
            "DECODER_CSD_SUBMIT codec=${codec.name} bytes=$length " +
                "nalTypes=${AnnexBCodecConfig.nalUnitTypes(config.codecMime, data, offset, length)}"
        )
        buffer.put(data, offset, length)
        codec.queueInputBuffer(
            inputIndex,
            0,
            length,
            System.nanoTime() / 1_000L,
            MediaCodec.BUFFER_FLAG_CODEC_CONFIG
        )
        lastSubmittedCsd = csd
        return true
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
            // Count bytes received from the network, even when decoder backpressure
            // causes an input packet to be dropped. This is receive bitrate, not
            // merely the subset of bytes successfully queued into MediaCodec.
            if (!isCodecConfig && length > 0) bytesInWindow += length.toLong()

            // The sender marks IDRs; the byte scan is only a fallback for
            // older senders that emit Annex-B start codes.
            val keyFrame = isKeyframe || isKeyFrame(data, offset, length)
            lastInputTimestampNano = timestampNano
            lastInputGamingMode = isGamingMode
            if (loggedInputPackets < 5) {
                loggedInputPackets++
                Log.i(
                    tag,
                    "Video input #$loggedInputPackets type=VIDEO_NAL flags(codecConfig=$isCodecConfig,keyframe=$keyFrame) " +
                        "bytes=$length first16=${AnnexBCodecConfig.hexPrefix(data, offset, length)} " +
                        "annexBNalTypes=${AnnexBCodecConfig.nalUnitTypes(config.codecMime, data, offset, length)} " +
                        "codec=${mediaCodec?.name ?: "not-started"} mime=${config.codecMime}"
                )
            }
            if (mediaCodec == null && keyFrame && surface.isValid) {
                // A previous decode error stopped the codec. Rebuild it on the
                // next IDR instead of leaving the TV on a frozen frame.
                startCodec()
            }
            val codec = mediaCodec ?: return
            if (!running.get()) return

            try {
                if (isCodecConfig) {
                    val parameterSets = AnnexBCodecConfig.extractParameterSets(config.codecMime, data, offset, length)
                    if (parameterSets == null) {
                        Log.e(
                            tag,
                            "Rejecting invalid codec config: codec=${config.codecMime} bytes=$length " +
                                "first16=${AnnexBCodecConfig.hexPrefix(data, offset, length)} " +
                                "nalTypes=${AnnexBCodecConfig.nalUnitTypes(config.codecMime, data, offset, length)}"
                        )
                        return
                    }
                    cachedCodecConfig = parameterSets
                    if (queueCodecConfig(codec, parameterSets, 0, parameterSets.size)) {
                        decodeGate.markCodecConfigAvailable()
                    }
                    return
                }

                // Surface recreation may seed this decoder with the session CSD.
                // As a second line of defense, recover if a sender bundles valid
                // parameter sets and an IDR into the same Annex-B access unit.
                if (!decodeGate.hasCodecConfig() && keyFrame) {
                    val inlineConfig = decodeGate.extractInlineParameterSets(config.codecMime, data, offset, length)
                    if (inlineConfig != null && queueCodecConfig(codec, inlineConfig, 0, inlineConfig.size)) {
                        cachedCodecConfig = inlineConfig
                        decodeGate.markCodecConfigAvailable()
                        Log.i(tag, "Recovered codec config inline with IDR: ${inlineConfig.size} bytes")
                    }
                }
                // An IDR alone is not enough: require validated SPS/PPS (AVC)
                // or VPS/SPS/PPS (HEVC) before any access unit reaches MediaCodec.
                if (!decodeGate.canQueueFrame(keyFrame)) return

                // Never block the network thread waiting for an input buffer.
                // A late frame is worthless in a low-latency cast; dropping it
                // keeps motion real-time instead of turning the cast into slow
                // motion. Keyframes still get a short wait because they unlock
                // everything that follows.
                val inputIndex = codec.dequeueInputBuffer(if (keyFrame) 10_000 else 0)
                if (inputIndex < 0) return

                // Once dequeueInputBuffer() succeeds, this index must be
                // returned to MediaCodec. Leaving it unqueued can gradually
                // starve the decoder after a transient buffer problem.
                val presentationTimeUs = System.nanoTime() / 1_000L
                val inputBuffer = codec.getInputBuffer(inputIndex)
                if (inputBuffer == null) {
                    Log.w(tag, "Decoder returned a null input buffer; returning the slot empty")
                    codec.queueInputBuffer(inputIndex, 0, 0, presentationTimeUs, 0)
                    return
                }
                if (offset < 0 || length <= 0 || offset > data.size - length) {
                    Log.w(tag, "Rejecting invalid video payload range: offset=$offset length=$length size=${data.size}")
                    codec.queueInputBuffer(inputIndex, 0, 0, presentationTimeUs, 0)
                    return
                }
                if (length > inputBuffer.remaining()) {
                    // Never truncate an access unit: a partial NAL/frame can
                    // poison decoder state and produce a green/corrupt picture.
                    Log.w(tag, "Dropping oversized video access unit: $length bytes, buffer=${inputBuffer.remaining()}")
                    codec.queueInputBuffer(inputIndex, 0, 0, presentationTimeUs, 0)
                    return
                }
                inputBuffer.put(data, offset, length)
                // MediaCodec presentation times must come from a monotonic
                // clock. The phone's wall-clock stamp is only for latency
                // reporting; feeding it here makes SurfaceFlinger delay frames
                // and the picture appears to run in slow motion.
                codec.queueInputBuffer(inputIndex, 0, length, presentationTimeUs, 0)
                if (keyFrame) decodeGate.markKeyframeQueued()
            } catch (e: Throwable) {
                Log.e(tag, "Decode error", e)
                // Once MediaCodec throws, its state is not trustworthy. Stop it
                // and rebuild from the next IDR instead of queuing into a dead
                // decoder for the rest of the session.
                stopCodec()
                decodeGate.resetForFlush()
                onError(e.message ?: "Video decoder error")
            }
        }
    }

    /** Drain decoded frames independently of TCP packet arrival. */
    private fun startOutputDrain(codec: MediaCodec) {
        outputDrainThread = Thread({
            while (running.get() && mediaCodec === codec) {
                try {
                    synchronized(lock) {
                        if (running.get() && mediaCodec === codec) {
                            drainOutput(codec, lastInputTimestampNano, lastInputGamingMode)
                        }
                    }
                    Thread.sleep(5)
                } catch (interrupted: InterruptedException) {
                    break
                } catch (error: Throwable) {
                    Log.e(tag, "Decoder output drain failed", error)
                    synchronized(lock) {
                        if (mediaCodec === codec) {
                            stopCodec()
                            decodeGate.resetForFlush()
                            onError(error.message ?: "Video output drain failed")
                        }
                    }
                    break
                }
            }
        }, "CastPro-Decoder-Output").apply {
            isDaemon = true
            start()
        }
    }

    private fun drainOutput(codec: MediaCodec, timestampNano: Long, isGamingMode: Boolean) {
        val info = MediaCodec.BufferInfo()
        var latestFrameIndex = -1
        var latestFrameInfo: MediaCodec.BufferInfo? = null

        // Drain every output currently queued, but do not render a backlog of
        // stale frames. Release superseded pictures immediately and render only
        // the newest picture from this drain pass.
        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(info, 0)
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val format = codec.outputFormat
                val formatWidth = runCatching { format.getInteger(MediaFormat.KEY_WIDTH) }.getOrDefault(config.width)
                val formatHeight = runCatching { format.getInteger(MediaFormat.KEY_HEIGHT) }.getOrDefault(config.height)
                fun hasKey(key: String): Boolean = runCatching { format.containsKey(key) }.getOrDefault(false)
                fun intValueOr(key: String, fallback: Int): Int = runCatching {
                    if (hasKey(key)) format.getInteger(key) else fallback
                }.getOrDefault(fallback)
                val hasCrop = hasKey("crop-left") && hasKey("crop-right") &&
                    hasKey("crop-top") && hasKey("crop-bottom")
                val cropLeft = intValueOr("crop-left", 0)
                val cropRight = intValueOr("crop-right", formatWidth - 1)
                val cropTop = intValueOr("crop-top", 0)
                val cropBottom = intValueOr("crop-bottom", formatHeight - 1)
                decodedWidth = if (hasCrop && cropRight >= cropLeft) cropRight - cropLeft + 1 else formatWidth
                decodedHeight = if (hasCrop && cropBottom >= cropTop) cropBottom - cropTop + 1 else formatHeight
                val mime = runCatching { format.getString(MediaFormat.KEY_MIME) }.getOrDefault(config.codecMime) ?: config.codecMime
                val profile = runCatching { format.getInteger(MediaFormat.KEY_PROFILE) }.getOrDefault(-1)
                val profileLabel = profileName(mime, profile)
                decodedCodecLabel = codecDisplayName(mime) + if (profileLabel.isBlank()) "" else " $profileLabel"
                onVideoSizeChanged(decodedWidth, decodedHeight)
                fun intValue(key: String): Int = runCatching {
                    if (format.containsKey(key)) format.getInteger(key) else -1
                }.getOrDefault(-1)
                val colorFormat = intValue(MediaFormat.KEY_COLOR_FORMAT)
                Log.i(
                    tag,
                    "DECODER_OUTPUT_FORMAT codec=${codec.name} " +
                        "size=${decodedWidth}x${decodedHeight} " +
                        "color-format=$colorFormat " +
                        "color-standard=${intValue(MediaFormat.KEY_COLOR_STANDARD)} " +
                        "color-transfer=${intValue(MediaFormat.KEY_COLOR_TRANSFER)} " +
                        "color-range=${intValue(MediaFormat.KEY_COLOR_RANGE)} " +
                        "stride=${intValue("stride")} slice-height=${intValue("slice-height")} " +
                        "crop-left=$cropLeft crop-right=$cropRight crop-top=$cropTop crop-bottom=$cropBottom " +
                        "mime=$mime profile=$profile format=$format"
                )
                continue
            }
            if (outputIndex < 0) break

            val isConfigOutput = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
            val isPicture = info.size > 0 && !isConfigOutput
            if (!isPicture) {
                codec.releaseOutputBuffer(outputIndex, false)
                continue
            }

            if (loggedOutputBuffers < 10) {
                loggedOutputBuffers++
                Log.i(
                    tag,
                    "Decoded output #$loggedOutputBuffers size=${info.size} flags=${info.flags} ptsUs=${info.presentationTimeUs}"
                )
            }

            // BufferInfo is reused by dequeueOutputBuffer(), so preserve the
            // metadata belonging to the selected output buffer.
            val candidateInfo = MediaCodec.BufferInfo().apply {
                set(info.offset, info.size, info.presentationTimeUs, info.flags)
            }
            if (latestFrameIndex >= 0) {
                codec.releaseOutputBuffer(latestFrameIndex, false)
            }
            latestFrameIndex = outputIndex
            latestFrameInfo = candidateInfo
        }

        if (latestFrameIndex >= 0 && latestFrameInfo != null) {
            // Count only after MediaCodec accepts the render release.
            codec.releaseOutputBuffer(latestFrameIndex, true)
            frameCount++
        }

        val nowMs = System.currentTimeMillis()
        if (nowMs - lastFpsTimestamp >= 1_000) {
            val elapsed = (nowMs - lastFpsTimestamp).coerceAtLeast(1)
            val fps = ((frameCount * 1_000L) / elapsed).toInt()
            val bitrate = (bytesInWindow * 8f) / elapsed / 1_000f
            // Remove the TV/phone wall-clock skew learned from heartbeats;
            // raw cross-device timestamps are meaningless when the two
            // clocks differ by seconds.
            val nowNs = nowMs * 1_000_000L
            // A one-way sample is not trustworthy until a heartbeat has
            // established the phone/TV wall-clock offset. Do not clamp a
            // negative/invalid sample to 0 ms: show it as unavailable instead.
            val latencyNs = clockOffsetProvider()?.let { offset ->
                (nowNs - timestampNano - offset).takeIf { it > 0L }
            }
            val latencyMs = latencyNs?.coerceAtMost(1_000_000_000L)
                ?.div(1_000_000f) ?: -1f
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
        lastSubmittedCsd = null
        outputDrainThread?.interrupt()
        outputDrainThread = null
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
