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
    @Volatile private var handle = 0L
    private var frames = 0L
    private var bytes = 0L
    private var dropped = 0L
    private var windowFrames = 0
    private var windowBytes = 0L
    private var windowStart = System.currentTimeMillis()

    override fun prepare(): Surface {
        check(handle == 0L) { "Native encoder already prepared" }
        handle = nativeStart(config.codecMime, config.width, config.height, config.fps, config.bitrate)
        check(handle != 0L) { "NDK MediaCodec initialization failed for " + config.codecMime }
        return nativeGetInputSurface(handle) ?: run {
            stop()
            error("NDK encoder did not return an input Surface")
        }
    }

    override fun start() {
        check(handle != 0L) { "Call prepare() before start()" }
        running.set(true)
        windowStart = System.currentTimeMillis()
    }

    @Suppress("unused")
    private fun onNativeEncoded(data: ByteArray, presentationTimeUs: Long, nativeFlags: Int) {
        if (!running.get()) return
        val flags: Byte = if ((nativeFlags and 1) != 0) {
            Constants.FLAG_CODEC_CONFIG
        } else {
            ((if ((nativeFlags and 2) != 0) Constants.FLAG_KEYFRAME.toInt() else 0) or
                (if (config.isGamingMode) Constants.FLAG_GAMING_MODE.toInt() else 0)).toByte()
        }
        val header = PacketHeader(
            type = Constants.TYPE_VIDEO_NAL,
            flags = flags,
            sequenceNumber = sequence.incrementAndGet(),
            timestampNano = System.currentTimeMillis() * 1_000_000L,
            payloadLength = data.size
        )
        val delivered = runCatching { transport.send(header, data) }.getOrDefault(false)
        if (delivered) {
            frames++
            bytes += data.size
            windowFrames++
            windowBytes += data.size
        } else dropped++
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
        windowBytes = 0
        windowStart = now
    }

    override fun requestKeyFrame() { if (handle != 0L) nativeRequestKeyFrame(handle) }
    fun updateBitrate(bitrate: Int) { if (handle != 0L) nativeUpdateBitrate(handle, bitrate) }

    override fun stop() {
        running.set(false)
        val current = handle
        handle = 0L
        if (current != 0L) runCatching { nativeStop(current) }
            .onFailure { Log.w(TAG, "Native encoder shutdown failed", it) }
    }
}
