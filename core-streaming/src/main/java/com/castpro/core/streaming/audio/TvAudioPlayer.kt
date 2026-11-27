package com.castpro.core.streaming.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import com.castpro.core.common.protocol.Constants
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Plays the phone's PCM stream on the TV.
 *
 * AudioTrack.write() blocks when the hardware buffer is full. The caller is the
 * TCP reader thread, so writing inline used to stall video packet reads behind
 * audio backpressure (a frozen picture with choppy sound). Chunks are queued
 * here and a dedicated thread performs the blocking writes; if the queue grows
 * beyond a short latency budget the oldest audio is dropped instead of delaying
 * the live stream.
 */
class TvAudioPlayer {
    private val tag = "TvAudioPlayer"
    @Volatile private var audioTrack: AudioTrack? = null
    private val running = AtomicBoolean(false)
    private val queueLock = Object()
    private val queue = ArrayDeque<ByteArray>()
    private var queuedBytes = 0
    private var writerThread: Thread? = null
    /** Toggled from the UI/network threads and read by the network thread. */
    @Volatile var isMuted = false

    fun start() {
        if (audioTrack != null) return
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
            running.set(true)
            writerThread = Thread({ writeLoop(track) }, "CastPro-TvAudio").apply {
                isDaemon = true
                start()
            }
            Log.i(tag, "TV low-latency AudioTrack initialized")
        } catch (e: Exception) {
            Log.e(tag, "Failed to initialize TV AudioTrack", e)
        }
    }

    /** Called from the network thread; never blocks on the audio hardware. */
    fun playAudioChunk(data: ByteArray, offset: Int, length: Int) {
        if (isMuted || length <= 0 || !running.get()) return
        if (offset < 0 || offset + length > data.size) return
        val chunk = data.copyOfRange(offset, offset + length)
        synchronized(queueLock) {
            // Keep at most half a second of audio queued. Dropping the oldest
            // chunk keeps sound in step with the video instead of accumulating
            // an ever-growing delay.
            while (queuedBytes + chunk.size > MAX_QUEUED_BYTES && queue.isNotEmpty()) {
                queuedBytes -= queue.pollFirst()?.size ?: 0
            }
            if (chunk.size > MAX_QUEUED_BYTES) return
            queue.addLast(chunk)
            queuedBytes += chunk.size
            queueLock.notifyAll()
        }
    }

    private fun writeLoop(track: AudioTrack) {
        try {
            while (running.get()) {
                val chunk = synchronized(queueLock) {
                    while (queue.isEmpty() && running.get()) {
                        try {
                            queueLock.wait(200)
                        } catch (_: InterruptedException) {
                            return
                        }
                    }
                    if (!running.get()) return
                    queue.pollFirst()?.also { queuedBytes -= it.size }
                } ?: continue

                try {
                    var offset = 0
                    while (offset < chunk.size && running.get()) {
                        // write() can accept fewer bytes than requested; keep
                        // writing the remainder instead of losing audio.
                        val written = track.write(chunk, offset, chunk.size - offset)
                        if (written <= 0) {
                            Log.w(tag, "AudioTrack write failed: $written")
                            break
                        }
                        offset += written
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Error writing audio to AudioTrack", e)
                    break
                }
            }
        } finally {
            // The writer owns the track: releasing it here guarantees no
            // blocking write() can still be in flight when release() runs.
            releaseTrack(track)
        }
    }

    private fun releaseTrack(track: AudioTrack) {
        try {
            track.pause()
            track.flush()
            track.stop()
            track.release()
        } catch (e: Exception) {
            Log.e(tag, "Error releasing audio track", e)
        }
    }

    fun stop() {
        running.set(false)
        synchronized(queueLock) {
            queue.clear()
            queuedBytes = 0
            queueLock.notifyAll()
        }
        val writer = writerThread
        writerThread = null
        val track = audioTrack
        audioTrack = null
        if (writer != null && writer != Thread.currentThread()) {
            runCatching { writer.join(1_000) }
            // If the writer is still blocked in write(), its own finally block
            // will release the track. Never release it from this thread.
        } else {
            track?.let(::releaseTrack)
        }
    }

    private companion object {
        /** ~0.5 s of 48 kHz 16-bit stereo audio. */
        const val MAX_QUEUED_BYTES = 48_000 * 4 / 2
    }
}
