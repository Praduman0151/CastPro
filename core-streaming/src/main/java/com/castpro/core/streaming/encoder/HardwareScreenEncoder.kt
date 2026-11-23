package com.castpro.core.streaming.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import com.castpro.core.common.model.StreamConfig
import com.castpro.core.common.protocol.Constants
import com.castpro.core.common.protocol.PacketHeader
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

fun interface StreamPacketListener {
    fun onPacket(header: PacketHeader, payload: ByteArray)
}

class HardwareScreenEncoder(
    private val config: StreamConfig,
    private val outputStream: OutputStream? = null,
    private val packetListener: StreamPacketListener? = null
) {
    private val tag = "HardwareScreenEncoder"
    private var mediaCodec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private val isRunning = AtomicBoolean(false)
    private var encoderThread: Thread? = null
    private var sequenceNumber = 0

    fun prepare(): Surface {
        val format = MediaFormat.createVideoFormat(config.codecMime, config.width, config.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, Constants.DEFAULT_I_FRAME_INTERVAL)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            
            // Ultra low-latency gaming settings
            setInteger(MediaFormat.KEY_LATENCY, 0)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            }
        }

        val codec = MediaCodec.createEncoderByType(config.codecMime)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        
        mediaCodec = codec
        inputSurface = surface
        return surface
    }

    fun start() {
        val codec = mediaCodec ?: throw IllegalStateException("Encoder not prepared")
        codec.start()
        isRunning.set(true)

        encoderThread = Thread({
            val bufferInfo = MediaCodec.BufferInfo()
            val headerBuffer = ByteBuffer.allocate(PacketHeader.HEADER_SIZE)

            while (isRunning.get()) {
                val outputIndex = try {
                    codec.dequeueOutputBuffer(bufferInfo, 10_000) // 10ms timeout
                } catch (e: Exception) {
                    Log.e(tag, "Error dequeuing output buffer", e)
                    break
                }

                if (outputIndex >= 0) {
                    val encodedData = codec.getOutputBuffer(outputIndex)
                    if (encodedData != null && bufferInfo.size > 0) {
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)

                        val isKeyframe = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                        val keyframeFlag = if (isKeyframe) Constants.FLAG_KEYFRAME.toInt() else 0
                        val gamingFlag = if (config.isGamingMode) Constants.FLAG_GAMING_MODE.toInt() else 0
                        val flags = (keyframeFlag or gamingFlag).toByte()

                        headerBuffer.clear()
                        val header = PacketHeader(
                            type = Constants.TYPE_VIDEO_NAL,
                            flags = flags,
                            sequenceNumber = sequenceNumber++,
                            timestampNano = System.nanoTime(),
                            payloadLength = bufferInfo.size
                        )
                        header.writeTo(headerBuffer)

                        val payload = ByteArray(bufferInfo.size)
                        encodedData.get(payload)

                        outputStream?.let { stream ->
                            synchronized(stream) {
                                try {
                                    stream.write(headerBuffer.array(), 0, PacketHeader.HEADER_SIZE)
                                    stream.write(payload)
                                    stream.flush()
                                } catch (e: Exception) {
                                    Log.e(tag, "Network send error", e)
                                    isRunning.set(false)
                                }
                            }
                        }

                        packetListener?.onPacket(header, payload)
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                }
            }
        }, "CastPro-Encoder-Thread").apply { start() }
    }

    fun stop() {
        isRunning.set(false)
        encoderThread?.interrupt()
        try {
            encoderThread?.join(500)
            mediaCodec?.stop()
            mediaCodec?.release()
            inputSurface?.release()
        } catch (e: Exception) {
            Log.e(tag, "Error releasing encoder", e)
        } finally {
            mediaCodec = null
            inputSurface = null
        }
    }
}
