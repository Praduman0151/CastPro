package com.castpro.core.streaming.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.castpro.core.common.model.AudioMode
import com.castpro.core.common.protocol.Constants
import com.castpro.core.common.protocol.PacketHeader
import com.castpro.core.streaming.encoder.StreamPacketListener
import com.castpro.core.streaming.transport.StreamTransport
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures internal game audio on Android 10+ (or the microphone when the user
 * selects that mode) and sends 48 kHz stereo PCM to the TV and browser.
 */
class GameAudioCapture(
    private val context: Context,
    private val projection: MediaProjection?,
    private val transport: StreamTransport,
    private val mode: AudioMode,
    private val packetListener: StreamPacketListener? = null,
    private val onError: (String) -> Unit = {}
) {
    private val tag = "GameAudioCapture"
    private var audioRecord: AudioRecord? = null
    private val isRecording = AtomicBoolean(false)
    private val isMuted = AtomicBoolean(false)
    private var captureThread: Thread? = null
    private var sequenceNumber = 0

    fun start() {
        if (mode == AudioMode.MUTED) {
            Log.i(tag, "Audio is muted by settings")
            return
        }
        if (mode == AudioMode.INTERNAL_GAME && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.w(tag, "Internal audio capture requires Android 10 (API 29) or higher")
            onError("Internal game audio needs Android 10 or newer; switching to microphone")
            return
        }

        try {
            val record = when (mode) {
                AudioMode.INTERNAL_GAME -> createPlaybackRecord()
                AudioMode.MICROPHONE -> createMicrophoneRecord()
                AudioMode.MUTED -> return
            } ?: return

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(tag, "AudioRecord failed to initialize")
                onError("Could not initialize audio capture")
                record.release()
                return
            }

            record.startRecording()
            audioRecord = record
            isRecording.set(true)
            captureThread = Thread({ captureLoop(record) }, "CastPro-Audio-Thread").apply {
                isDaemon = true
                start()
            }
            Log.i(tag, "Audio capture active at ${Constants.AUDIO_SAMPLE_RATE} Hz Stereo ($mode)")
        } catch (e: Exception) {
            Log.e(tag, "Failed to start audio capture", e)
            onError(e.message ?: "Audio capture failed")
        }
    }

    @SuppressLint("MissingPermission")
    private fun createPlaybackRecord(): AudioRecord? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            onError("Audio permission is required for internal game audio")
            return null
        }
        val mediaProjection = projection ?: return null
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

        val minBufferSize = minBufferSize()
        return AudioRecord.Builder()
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(minBufferSize * 2)
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun createMicrophoneRecord(): AudioRecord? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            onError("Microphone permission is required for microphone audio")
            return null
        }
        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(Constants.AUDIO_SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        return AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(minBufferSize() * 2)
            .build()
    }

    private fun minBufferSize(): Int = AudioRecord.getMinBufferSize(
        Constants.AUDIO_SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_STEREO,
        AudioFormat.ENCODING_PCM_16BIT
    ).coerceAtLeast(Constants.AUDIO_BUFFER_SIZE)

    private fun captureLoop(record: AudioRecord) {
        val audioBuffer = ByteArray(Constants.AUDIO_BUFFER_SIZE)
        val headerBuffer = ByteBuffer.allocate(PacketHeader.HEADER_SIZE)
        while (isRecording.get()) {
            val bytesRead = try {
                record.read(audioBuffer, 0, audioBuffer.size)
            } catch (e: Exception) {
                Log.e(tag, "Audio read failed", e)
                break
            }
            if (bytesRead <= 0) continue
            if (isMuted.get()) continue

            headerBuffer.clear()
            val header = PacketHeader(
                type = Constants.TYPE_AUDIO_DATA,
                flags = 0,
                sequenceNumber = sequenceNumber++,
                timestampNano = System.currentTimeMillis() * 1_000_000L,
                payloadLength = bytesRead
            )
            header.writeTo(headerBuffer)

            // Copy the exact bytes that were read; the shared buffer is reused
            // on the next iteration.
            val payload = audioBuffer.copyOf(bytesRead)
            transport.send(header, payload)
            packetListener?.onPacket(header, payload)
        }
        Log.i(tag, "Audio capture loop ended")
    }

    fun setMuted(muted: Boolean) {
        isMuted.set(muted)
    }

    fun stop() {
        isRecording.set(false)
        captureThread?.interrupt()
        try {
            captureThread?.join(400)
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.w(tag, "Error stopping audio capture", e)
        } finally {
            runCatching { audioRecord?.release() }
            audioRecord = null
            captureThread = null
        }
    }
}
