package com.castpro.core.streaming.encoder

import android.util.Log
import android.view.Surface
import com.castpro.core.common.model.StreamConfig
import com.castpro.core.common.protocol.Constants
import com.castpro.core.common.protocol.PacketHeader
import com.castpro.core.streaming.transport.StreamTransport
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Experimental NDK surface encoder. The current TV wire protocol and transport
 * contract are retained. JNI output currently requires one encoded-byte copy.
 *
 * Codec configuration is retained independently of transport state so a TV
 * that connects/reconnects after the encoder starts can receive SPS/PPS before
 * the next IDR. This is especially important because the native codec can emit
 * its initial codec-config buffer before the TCP connection is ready.
 */
class NativeScreenEncoder(
    private val config: StreamConfig,
    private val transport: StreamTransport,
    private val onStats: ((EncoderStats) -> Unit)? = null,
    private val onError: (String) -> Unit = {}
) : ScreenEncoderBackend {
    companion object {
        private const val TAG = "NativeScreenEncoder"
        init { System.loadLibrary("castpro_native_engine") }
    }

    private external fun nativeStart(mime: String, width: Int, height: Int, fps: Int, bitrate: Int): Long
    private external fun nativeGetInputSurface(handle: Long): Surface?
    private external fun nativeRequestKeyFrame(handle: Long)
    private external fun nativeUpdateBitrate(handle: Long, bitrate: Int)
    private external fun nativeStop(handle: Long)

    private val sequence = AtomicInteger(0)
    private val running = AtomicBoolean(false)
    private val outputLock = Any()
    // Serialize JNI control calls against nativeStop() without holding outputLock
    // while nativeStop joins the drain thread.
    private val nativeCallLock = Any()
    @Volatile private var handle = 0L
    private var codecConfigBytes: ByteArray? = null
    private var frames = 0L
    private var bytes = 0L
    private var dropped = 0L
    private var windowFrames = 0
    private var windowBytes = 0L
    private var windowStart = System.currentTimeMillis()

    override fun prepare(): Surface {
        check(handle == 0L) { "Native encoder already prepared" }

        // nativeStart() starts its NDK drain thread before returning. Mark the
        // backend active first so an early SPS/PPS callback is not discarded.
        running.set(true)
        return try {
            val createdHandle = nativeStart(
                config.codecMime,
                config.width,
                config.height,
                config.fps,
                config.bitrate
            )
            check(createdHandle != 0L) {
                "NDK MediaCodec initialization failed for ${config.codecMime}"
            }
            handle = createdHandle
            nativeGetInputSurface(createdHandle) ?: error("NDK encoder did not return an input Surface")
        } catch (error: Throwable) {
            running.set(false)
            val current = handle
            handle = 0L
            if (current != 0L) runCatching { nativeStop(current) }
            throw error
        }
    }

    override fun start() {
        check(handle != 0L) { "Call prepare() before start()" }
        // running is set in prepare() because the native drain thread can emit
        // codec configuration before prepare() returns.
        windowStart = System.currentTimeMillis()
    }

    @Suppress("unused")
    private fun onNativeEncoded(data: ByteArray, presentationTimeUs: Long, nativeFlags: Int) {
        if (!running.get()) return
        synchronized(outputLock) {
            if (!running.get()) return

            val isCodecConfig = (nativeFlags and 1) != 0
            val isKeyframe = (nativeFlags and 2) != 0
            if (isCodecConfig) {
                // Cache even if transport.send() fails while the TV is offline.
                codecConfigBytes = data.copyOf()
                sendCodecConfigLocked()
                return
            }

            // Repeat parameter sets immediately before every IDR. This makes
            // recovery independent of whether the receiver saw the first CSD.
            if (isKeyframe) sendCodecConfigLocked()

            val flags = (
                (if (isKeyframe) Constants.FLAG_KEYFRAME.toInt() else 0) or
                    (if (config.isGamingMode) Constants.FLAG_GAMING_MODE.toInt() else 0)
                ).toByte()
            sendPacketLocked(data, flags, countAsFrame = true)
        }
    }

    private fun sendCodecConfigLocked() {
        val data = codecConfigBytes ?: return
        sendPacketLocked(data, Constants.FLAG_CODEC_CONFIG, countAsFrame = false)
    }

    private fun sendPacketLocked(data: ByteArray, flags: Byte, countAsFrame: Boolean) {
        val header = PacketHeader(
            type = Constants.TYPE_VIDEO_NAL,
            flags = flags,
            sequenceNumber = sequence.incrementAndGet(),
            timestampNano = System.currentTimeMillis() * 1_000_000L,
            payloadLength = data.size
        )
        val delivered = runCatching { transport.send(header, data) }
            .onFailure { Log.w(TAG, "Could not send encoded video packet", it) }
            .getOrDefault(false)

        if (delivered) {
            bytes += data.size
            windowBytes += data.size
            if (countAsFrame) {
                frames++
                windowFrames++
            }
        } else if (countAsFrame) {
            dropped++
        }
        reportStats()
    }

    private fun reportStats() {
        val now = System.currentTimeMillis()
        val elapsed = now - windowStart
        if (elapsed < 1_000L) return
        onStats?.invoke(
            EncoderStats(
                fps = (windowFrames * 1_000L / elapsed).toInt(),
                bitrateMbps = windowBytes * 8f / elapsed / 1_000f,
                framesSent = frames,
                bytesSent = bytes,
                droppedFrames = dropped
            )
        )
        windowFrames = 0
        windowBytes = 0L
        windowStart = now
    }

    override fun requestKeyFrame() {
        synchronized(nativeCallLock) {
            val current = handle
            if (current == 0L || !running.get()) return
            synchronized(outputLock) {
                // The TCP connection callback requests a keyframe after reconnect.
                // Replay cached codec config first, then ask MediaCodec for an IDR.
                sendCodecConfigLocked()
                runCatching { nativeRequestKeyFrame(current) }
                    .onFailure {
                        Log.w(TAG, "Native keyframe request failed", it)
                        onError("Could not request a fresh video keyframe")
                    }
            }
        }
    }

    fun updateBitrate(bitrate: Int) {
        synchronized(nativeCallLock) {
            val current = handle
            if (current != 0L && running.get()) {
                runCatching { nativeUpdateBitrate(current, bitrate) }
                    .onFailure { Log.w(TAG, "Native bitrate update failed", it) }
            }
        }
    }

    override fun stop() {
        running.set(false)
        synchronized(nativeCallLock) {
            val current = handle
            handle = 0L
            if (current != 0L) runCatching { nativeStop(current) }
                .onFailure { Log.w(TAG, "Native encoder shutdown failed", it) }
        }
        synchronized(outputLock) {
            codecConfigBytes = null
        }
    }
}
