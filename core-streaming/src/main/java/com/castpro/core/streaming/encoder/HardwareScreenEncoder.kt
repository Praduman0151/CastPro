package com.castpro.core.streaming.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Surface
import com.castpro.core.common.model.StreamConfig
import com.castpro.core.common.protocol.AnnexBCodecConfig
import com.castpro.core.common.protocol.Constants
import com.castpro.core.common.protocol.PacketHeader
import com.castpro.core.streaming.transport.StreamTransport
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

fun interface StreamPacketListener {
    fun onPacket(header: PacketHeader, payload: ByteArray)
}

data class EncoderStats(
    val fps: Int,
    val bitrateMbps: Float,
    val framesSent: Long,
    val bytesSent: Long,
    val droppedFrames: Long
)

class HardwareScreenEncoder(
    private val config: StreamConfig,
    private val transport: StreamTransport? = null,
    private val packetListener: StreamPacketListener? = null,
    private val onStats: ((EncoderStats) -> Unit)? = null,
    private val onError: (String) -> Unit = {}
) : ScreenEncoderBackend {
    private val tag = "HardwareScreenEncoder"
    private var mediaCodec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private val isRunning = AtomicBoolean(false)
    private var encoderThread: Thread? = null
    private val sequenceNumber = AtomicInteger(0)

    /**
     * SPS/PPS (or VPS/SPS/PPS) reported by MediaCodec. It is resent on every
     * reconnect because the TV may have missed the output-format notification
     * while its socket was not connected yet.
     */
    @Volatile
    private var codecConfigBytes: ByteArray? = null

    private var framesSent = 0L
    private var bytesSent = 0L
    private var droppedFrames = 0L
    private var windowFrames = 0
    private var windowBytes = 0L
    private var windowStartMs = System.currentTimeMillis()
    private var loggedOutputPackets = 0

    override fun prepare(): Surface {
        val codec = createConfiguredEncoder()
        val surface = codec.createInputSurface()
        mediaCodec = codec
        inputSurface = surface
        return surface
    }

    private fun createConfiguredEncoder(): MediaCodec {
        val codec = MediaCodec.createEncoderByType(config.codecMime)
        val baselineLevel = if (config.codecMime.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true)) {
            runCatching {
                codec.codecInfo.getCapabilitiesForType(config.codecMime).profileLevels
                    .filter { it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline }
                    .maxByOrNull { it.level }
            }.onFailure { Log.w(tag, "Could not inspect advertised AVC profiles; omitting profile/level keys", it) }
                .getOrNull()
        } else null

        val fullFormat = MediaFormat.createVideoFormat(config.codecMime, config.width, config.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, Constants.DEFAULT_I_FRAME_INTERVAL)
            // Only request Baseline when this exact encoder advertises it. Pair
            // it with that profile's highest advertised level (including 4.2
            // when supported for 1080p60).
            baselineLevel?.let {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                setInteger(MediaFormat.KEY_LEVEL, it.level)
                Log.i(tag, "Requesting advertised AVC Baseline level=${it.level}")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            }
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            setInteger(MediaFormat.KEY_LATENCY, 0)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                runCatching { setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1) }
            }
        }

        try {
            codec.configure(fullFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            return codec
        } catch (primaryError: Exception) {
            Log.w(tag, "Primary encoder format rejected; retrying clean minimal fallback without profile/level/color keys", primaryError)
            runCatching { codec.release() }
        }

        // Deliberately contain only universally required surface-encoder keys.
        // This strips KEY_PROFILE, KEY_LEVEL, KEY_COLOR_STANDARD,
        // KEY_COLOR_TRANSFER and KEY_COLOR_RANGE, plus optional tuning keys.
        val fallbackFormat = MediaFormat.createVideoFormat(config.codecMime, config.width, config.height).apply {
            EncoderFormatPolicy.minimalFallbackValues(
                config.bitrate, config.fps, Constants.DEFAULT_I_FRAME_INTERVAL
            ).forEach { (key, value) -> setInteger(key, value) }
        }
        val fallbackCodec = MediaCodec.createEncoderByType(config.codecMime)
        try {
            Log.w(tag, "Using clean minimal encoder fallback: ${config.codecMime} ${config.width}x${config.height}")
            fallbackCodec.configure(fallbackFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            return fallbackCodec
        } catch (fallbackError: Exception) {
            runCatching { fallbackCodec.release() }
            onError("This device has no usable ${config.codecMime} encoder")
            throw fallbackError
        }
    }

    override fun start() {
        val codec = mediaCodec ?: throw IllegalStateException("Encoder not prepared")
        codec.start()
        Log.i(tag, "Encoder codec=${codec.name} mime=${config.codecMime}")
        isRunning.set(true)
        windowStartMs = System.currentTimeMillis()

        encoderThread = Thread({
            val bufferInfo = MediaCodec.BufferInfo()
            val headerBuffer = ByteBuffer.allocate(PacketHeader.HEADER_SIZE)

            while (isRunning.get()) {
                val outputIndex = try {
                    codec.dequeueOutputBuffer(bufferInfo, 10_000)
                } catch (e: Exception) {
                    if (isRunning.get()) {
                        Log.e(tag, "Error dequeuing output buffer", e)
                        onError(e.message ?: "Encoder stopped unexpectedly")
                    }
                    break
                }

                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    captureCodecConfig(codec.outputFormat)
                    continue
                }
                if (outputIndex < 0) {
                    reportStatsIfNeeded()
                    continue
                }

                val encodedData = codec.getOutputBuffer(outputIndex)
                if (encodedData != null && bufferInfo.size > 0) {
                    encodedData.position(bufferInfo.offset)
                    encodedData.limit(bufferInfo.offset + bufferInfo.size)

                    val isKeyframe = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                    val flags = ((if (isKeyframe) Constants.FLAG_KEYFRAME.toInt() else 0) or
                        (if (config.isGamingMode) Constants.FLAG_GAMING_MODE.toInt() else 0)).toByte()
                    // Wall-clock nanos are comparable across the phone and TV.
                    // MediaCodec presentation time is relative to encoder start
                    // and nanoTime() has a different epoch on each device.
                    val captureTimeNano = System.currentTimeMillis() * 1_000_000L

                    val payload = ByteArray(bufferInfo.size)
                    encodedData.get(payload)

                    if (loggedOutputPackets < 10) {
                        loggedOutputPackets++
                        Log.i(
                            tag,
                            "Video packet #$loggedOutputPackets type=${Constants.TYPE_VIDEO_NAL} " +
                                "flags=$flags bytes=${payload.size} first16=${AnnexBCodecConfig.hexPrefix(payload)} " +
                                "annexBNalTypes=${AnnexBCodecConfig.nalUnitTypes(config.codecMime, payload)} " +
                                "codec=${codec.name}"
                        )
                    }

                    // A receiver must have SPS/PPS (or VPS/SPS/PPS) before an
                    // IDR. The first connection can race the encoder's
                    // INFO_OUTPUT_FORMAT_CHANGED callback; never send an IDR
                    // ahead of the codec config packet.
                    if (isKeyframe) {
                        if (codecConfigBytes == null) {
                            Log.w(tag, "Dropping IDR until encoder codec config is available")
                            codec.releaseOutputBuffer(outputIndex, false)
                            reportStatsIfNeeded()
                            continue
                        }
                        sendCodecConfig()
                    }

                    // Allocate the video sequence number after any codec-config
                    // packet so TCP order and sequence order stay consistent.
                    headerBuffer.clear()
                    val header = PacketHeader(
                        type = Constants.TYPE_VIDEO_NAL,
                        flags = flags,
                        sequenceNumber = sequenceNumber.incrementAndGet(),
                        timestampNano = captureTimeNano,
                        payloadLength = bufferInfo.size
                    )
                    header.writeTo(headerBuffer)

                    val delivered = transport?.send(header, payload) ?: true
                    if (delivered) {
                        framesSent++
                        bytesSent += payload.size
                        windowFrames++
                        windowBytes += payload.size
                    } else {
                        droppedFrames++
                    }
                    packetListener?.onPacket(header, payload)
                }
                codec.releaseOutputBuffer(outputIndex, false)
                reportStatsIfNeeded()
            }
        }, "CastPro-Encoder-Thread").apply {
            isDaemon = true
            start()
        }
    }

    private fun reportStatsIfNeeded() {
        val now = System.currentTimeMillis()
        val elapsed = now - windowStartMs
        if (elapsed < 1_000L) return
        val fps = ((windowFrames * 1_000L) / elapsed).toInt()
        val bitrate = (windowBytes * 8f) / elapsed / 1_000f
        onStats?.invoke(
            EncoderStats(
                fps = fps,
                bitrateMbps = bitrate,
                framesSent = framesSent,
                bytesSent = bytesSent,
                droppedFrames = droppedFrames
            )
        )
        windowFrames = 0
        windowBytes = 0
        windowStartMs = now
    }

    private fun captureCodecConfig(format: MediaFormat) {
        val chunks = listOfNotNull(
            format.getByteBuffer("csd-0"),
            format.getByteBuffer("csd-1"),
            format.getByteBuffer("csd-2")
        )
        if (chunks.isEmpty()) return
        val total = chunks.sumOf { it.remaining() }
        if (total <= 0) return
        val data = ByteArray(total)
        var offset = 0
        for (chunk in chunks) {
            val copy = chunk.duplicate()
            copy.rewind()
            val length = copy.remaining()
            copy.get(data, offset, length)
            offset += length
        }
        val parameterSets = AnnexBCodecConfig.extractParameterSets(config.codecMime, data)
        if (parameterSets == null) {
            Log.e(
                tag,
                "Rejecting invalid/non-Annex-B codec config for ${config.codecMime}: " +
                    "bytes=${data.size} first16=${AnnexBCodecConfig.hexPrefix(data)} " +
                    "nalTypes=${AnnexBCodecConfig.nalUnitTypes(config.codecMime, data)}"
            )
            codecConfigBytes = null
            return
        }
        codecConfigBytes = parameterSets
        Log.i(
            tag,
            "Encoder codec config accepted: ${parameterSets.size} bytes " +
                "nalTypes=${AnnexBCodecConfig.nalUnitTypes(config.codecMime, parameterSets)}"
        )
        sendCodecConfig()
    }

    /**
     * Send SPS/PPS separately as well as prepending them to sync frames. Some
     * encoders only expose them in the output format, and a receiver that
     * connects after the format change would otherwise never see them.
     */
    private fun sendCodecConfig() {
        val data = codecConfigBytes ?: return
        val header = PacketHeader(
            type = Constants.TYPE_VIDEO_NAL,
            flags = Constants.FLAG_CODEC_CONFIG,
            sequenceNumber = sequenceNumber.incrementAndGet(),
            timestampNano = System.currentTimeMillis() * 1_000_000L,
            payloadLength = data.size
        )
        val delivered = transport?.send(header, data) ?: true
        if (delivered) {
            bytesSent += data.size
        }
        packetListener?.onPacket(header, data)
    }

    /** Ask the codec for an IDR so a reconnected TV can start cleanly. */
    override fun requestKeyFrame() {
        // The new socket may have joined after MediaCodec emitted its output
        // format. Send the stored parameter sets before the IDR.
        sendCodecConfig()
        val codec = mediaCodec ?: return
        try {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            codec.setParameters(params)
        } catch (e: Exception) {
            Log.w(tag, "Could not request a keyframe", e)
        }
    }

    override fun stop() {
        isRunning.set(false)
        encoderThread?.interrupt()
        try {
            encoderThread?.join(800)
            mediaCodec?.stop()
        } catch (e: Exception) {
            Log.w(tag, "Error stopping encoder", e)
        } finally {
            runCatching { mediaCodec?.release() }
            runCatching { inputSurface?.release() }
            mediaCodec = null
            inputSurface = null
            encoderThread = null
            codecConfigBytes = null
        }
    }
}
