package com.castpro.core.streaming.encoder

import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.castpro.core.common.model.StreamConfig
import com.castpro.core.common.protocol.Constants
import com.castpro.core.common.protocol.PacketHeader
import com.castpro.core.streaming.transport.StreamTransport
import com.castpro.core.streaming.transport.TransportCounters
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
    // ANativeWindow_toSurface() returns a Java Surface that owns a reference
    // to the codec's input window. Keep it alive for the whole session; if it
    // is collected, the virtual display can silently stop receiving frames.
    @Volatile private var inputSurface: Surface? = null
    private var codecConfigBytes: ByteArray? = null
    private var pendingKeyframe = false
    /** Frames accepted into the transport queue, not yet necessarily written. */
    private var frames = 0L
    private var bytes = 0L
    private var dropped = 0L
    private var baselineWrittenFrames = 0L
    private var baselineWrittenBytes = 0L
    private var windowFrames = 0
    private var windowBytes = 0L
    private var windowStart = SystemClock.elapsedRealtime()

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
            Log.i(TAG, "Native Engine encoder prepared: ${config.codecMime} " +
                "${config.width}x${config.height}@${config.fps} bitrate=${config.bitrate}")
            val surface = nativeGetInputSurface(createdHandle)
                ?: error("NDK encoder did not return an input Surface")
            inputSurface = surface
            surface
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
        windowStart = SystemClock.elapsedRealtime()
        transport.counters.let {
            baselineWrittenFrames = it.writtenVideoFrames
            baselineWrittenBytes = it.writtenVideoBytes
        }
    }

    @Suppress("unused")
    private fun onNativeEncoded(data: ByteArray, presentationTimeUs: Long, nativeFlags: Int) {
        if (!running.get()) return
        var requestDeferredKeyframe = false
        synchronized(outputLock) {
            if (!running.get()) return@synchronized

            val isCodecConfig = (nativeFlags and 1) != 0
            val isKeyframe = (nativeFlags and 2) != 0
            if (isCodecConfig) {
                // Cache even if transport.send() fails while the TV is offline.
                codecConfigBytes = data.copyOf()
                Log.i(TAG, "Native encoder codec config cached: ${data.size} bytes")
                if (pendingKeyframe) {
                    pendingKeyframe = false
                    requestDeferredKeyframe = true
                }
                if (!isKeyframe) {
                    sendCodecConfigLocked()
                    return@synchronized
                }
                // A combined config+IDR buffer already carries its own parameter
                // sets. Sending the cached copy as well would duplicate CSD and
                // can desynchronise strict TV decoders, so treat it as the IDR.
            }

            // Never release an IDR before parameter sets exist. The compatibility
            // encoder drops these too; sending a bare IDR is a known green-screen
            // trigger on TVs that require SPS/PPS in the format or in-band.
            if (isKeyframe && codecConfigBytes == null) {
                pendingKeyframe = true
                Log.w(TAG, "Dropping native IDR until codec config is available")
                return@synchronized
            }

            // Repeat parameter sets immediately before every IDR. This makes
            // recovery independent of whether the receiver saw the first CSD.
            if (isKeyframe && !isCodecConfig) sendCodecConfigLocked()

            val flags = (
                (if (isKeyframe) Constants.FLAG_KEYFRAME.toInt() else 0) or
                    (if (config.isGamingMode) Constants.FLAG_GAMING_MODE.toInt() else 0)
                ).toByte()
            if (frames < 8) {
                Log.i(TAG, "Sending encoded frame bytes=${data.size} keyframe=$isKeyframe flags=$flags")
            }
            sendPacketLocked(data, flags, countAsFrame = true)
        }
        if (requestDeferredKeyframe) requestNativeKeyFrame()
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
            timestampNano = SystemClock.elapsedRealtimeNanos(),
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
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - windowStart
        if (elapsed < 1_000L) return
        // Prefer real socket-write counters; fall back to queue acceptance
        // for transports that do not expose them.
        val counters = transport.counters.takeIf { it != TransportCounters() }
        val writtenFrames = counters?.let { (it.writtenVideoFrames - baselineWrittenFrames).coerceAtLeast(0) }
            ?: frames
        val writtenBytes = counters?.let { (it.writtenVideoBytes - baselineWrittenBytes).coerceAtLeast(0) }
            ?: bytes
        onStats?.invoke(
            EncoderStats(
                fps = (windowFrames * 1_000L / elapsed).toInt(),
                bitrateMbps = windowBytes * 8f / elapsed / 1_000f,
                framesSent = writtenFrames,
                bytesSent = writtenBytes,
                droppedFrames = (frames - writtenFrames).coerceAtLeast(0) + dropped
            )
        )
        windowFrames = 0
        windowBytes = 0L
        windowStart = now
    }

    /** Ask MediaCodec for an IDR after replaying cached parameter sets. */
    private fun requestNativeKeyFrame() {
        synchronized(nativeCallLock) {
            val current = handle
            if (current == 0L || !running.get()) return
            synchronized(outputLock) {
                Log.i(TAG, "Keyframe requested; replaying cached codec config " +
                    "(${codecConfigBytes?.size ?: 0} bytes)")
                sendCodecConfigLocked()
            }
            runCatching { nativeRequestKeyFrame(current) }
                .onFailure {
                    Log.w(TAG, "Native keyframe request failed", it)
                    onError("Could not request a fresh video keyframe")
                }
        }
    }

    override fun requestKeyFrame() {
        var requestNow = false
        synchronized(outputLock) {
            if (codecConfigBytes == null) {
                // A reconnect or live reconfigure can request a keyframe before
                // the native drain thread has delivered csd-0/1. Defer instead
                // of emitting an IDR the TV cannot decode.
                pendingKeyframe = true
                Log.i(TAG, "Keyframe deferred until native codec config arrives")
            } else {
                requestNow = true
            }
        }
        if (requestNow) requestNativeKeyFrame()
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
            pendingKeyframe = false
        }
        inputSurface = null
    }
}
