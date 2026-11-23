package com.castpro.core.streaming.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import com.castpro.core.common.protocol.Constants
import com.castpro.core.common.protocol.PacketHeader
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class GameAudioCapture(
    private val mediaProjection: MediaProjection,
    private val outputStream: OutputStream
) {
    private val tag = "GameAudioCapture"
    private var audioRecord: AudioRecord? = null
    private val isRecording = AtomicBoolean(false)
    private var captureThread: Thread? = null
    private var sequenceNumber = 0

    @SuppressLint("MissingPermission")
    fun start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.w(tag, "AudioPlaybackCapture requires Android 10 (API 29) or higher")
            return
        }

        try {
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(Constants.AUDIO_SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()

            val minBufferSize = AudioRecord.getMinBufferSize(
                Constants.AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(Constants.AUDIO_BUFFER_SIZE)

            val record = AudioRecord.Builder()
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(minBufferSize * 2)
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(tag, "AudioRecord failed to initialize")
                return
            }

            record.startRecording()
            audioRecord = record
            isRecording.set(true)

            captureThread = Thread({
                val audioBuffer = ByteArray(Constants.AUDIO_BUFFER_SIZE)
                val headerBuffer = ByteBuffer.allocate(PacketHeader.HEADER_SIZE)

                while (isRecording.get()) {
                    val bytesRead = record.read(audioBuffer, 0, audioBuffer.size)
                    if (bytesRead > 0) {
                        headerBuffer.clear()
                        val header = PacketHeader(
                            type = Constants.TYPE_AUDIO_DATA,
                            flags = 0,
                            sequenceNumber = sequenceNumber++,
                            timestampNano = System.nanoTime(),
                            payloadLength = bytesRead
                        )
                        header.writeTo(headerBuffer)

                        synchronized(outputStream) {
                            try {
                                outputStream.write(headerBuffer.array(), 0, PacketHeader.HEADER_SIZE)
                                outputStream.write(audioBuffer, 0, bytesRead)
                                outputStream.flush()
                            } catch (e: Exception) {
                                Log.e(tag, "Error streaming audio frame", e)
                                isRecording.set(false)
                            }
                        }
                    }
                }
            }, "CastPro-Audio-Thread").apply { start() }

            Log.i(tag, "Game audio capture active at ${Constants.AUDIO_SAMPLE_RATE} Hz Stereo")
        } catch (e: Exception) {
            Log.e(tag, "Failed to start audio playback capture", e)
        }
    }

    fun stop() {
        isRecording.set(false)
        captureThread?.interrupt()
        try {
            captureThread?.join(300)
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(tag, "Error stopping audio capture", e)
        } finally {
            audioRecord = null
            captureThread = null
        }
    }
}
