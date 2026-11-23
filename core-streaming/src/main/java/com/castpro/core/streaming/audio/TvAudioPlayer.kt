package com.castpro.core.streaming.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import com.castpro.core.common.protocol.Constants

class TvAudioPlayer {
    private val tag = "TvAudioPlayer"
    private var audioTrack: AudioTrack? = null
    var isMuted = false

    fun start() {
        try {
            val minBufferSize = AudioTrack.getMinBufferSize(
                Constants.AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(Constants.AUDIO_BUFFER_SIZE)

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(Constants.AUDIO_SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build()

            val track = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                AudioTrack.Builder()
                    .setAudioAttributes(audioAttributes)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(minBufferSize * 2)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    Constants.AUDIO_SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBufferSize * 2,
                    AudioTrack.MODE_STREAM
                )
            }

            track.play()
            audioTrack = track
            Log.i(tag, "TV low-latency AudioTrack initialized")
        } catch (e: Exception) {
            Log.e(tag, "Failed to initialize TV AudioTrack", e)
        }
    }

    fun playAudioChunk(data: ByteArray, offset: Int, length: Int) {
        if (isMuted) return
        val track = audioTrack ?: return
        try {
            track.write(data, offset, length)
        } catch (e: Exception) {
            Log.e(tag, "Error writing audio to AudioTrack", e)
        }
    }

    fun stop() {
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            Log.e(tag, "Error releasing audio track", e)
        } finally {
            audioTrack = null
        }
    }
}
