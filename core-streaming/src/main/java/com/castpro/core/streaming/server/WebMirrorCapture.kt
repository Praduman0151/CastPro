package com.castpro.core.streaming.server

import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * A second, low-resolution mirror used only by the browser receiver.
 *
 * JPEG over WebSocket works in every browser over plain HTTP, unlike WebCodecs
 * (which requires HTTPS) or MSE H.264 (which would need an MP4 muxer). The
 * native TV receiver keeps the low-latency MediaCodec path; this is the
 * zero-install fallback.
 */
class WebMirrorCapture(
    private val projection: MediaProjection,
    private val width: Int = DEFAULT_WIDTH,
    private val height: Int = DEFAULT_HEIGHT,
    private val dpi: Int = 160,
    private val shouldCapture: () -> Boolean,
    private val onJpeg: (ByteArray) -> Unit,
    private val onError: (String) -> Unit = {}
) {
    private val tag = "WebMirrorCapture"
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var lastFrameMs = 0L
    private var running = false

    fun start() {
        if (running) return
        try {
            val thread = HandlerThread("CastPro-WebMirror").apply { start() }
            handlerThread = thread
            val callbackHandler = Handler(thread.looper)
            handler = callbackHandler

            val reader = ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2)
            imageReader = reader
            reader.setOnImageAvailableListener({ source ->
                val image = try {
                    source.acquireLatestImage()
                } catch (_: Exception) {
                    null
                } ?: return@setOnImageAvailableListener
                try {
                    val now = SystemClock.elapsedRealtime()
                    if (!running || !shouldCapture() || now - lastFrameMs < 90L) return@setOnImageAvailableListener
                    lastFrameMs = now
                    val jpeg = imageToJpeg(image) ?: return@setOnImageAvailableListener
                    onJpeg(jpeg)
                } catch (e: Exception) {
                    Log.w(tag, "Could not encode web frame", e)
                } finally {
                    image.close()
                }
            }, callbackHandler)

            virtualDisplay = projection.createVirtualDisplay(
                "CastPro-WebMirror",
                width,
                height,
                dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                callbackHandler
            )
            running = true
            Log.i(tag, "Web mirror started at ${width}x$height")
        } catch (e: Exception) {
            Log.e(tag, "Could not start web mirror", e)
            onError("Browser receiver could not start: ${e.message}")
            stop()
        }
    }

    private fun imageToJpeg(image: android.media.Image): ByteArray? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        if (pixelStride == 4 && rowStride == width * 4) {
            bitmap.copyPixelsFromBuffer(buffer)
        } else {
            val pixels = IntArray(width * height)
            for (row in 0 until height) {
                val rowStart = row * rowStride
                for (column in 0 until width) {
                    val index = rowStart + column * pixelStride
                    if (index + 3 >= buffer.limit()) continue
                    val r = buffer.get(index).toInt() and 0xFF
                    val g = buffer.get(index + 1).toInt() and 0xFF
                    val b = buffer.get(index + 2).toInt() and 0xFF
                    val a = buffer.get(index + 3).toInt() and 0xFF
                    pixels[row * width + column] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        }

        return try {
            val output = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 58, output)
            output.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    fun stop() {
        running = false
        try {
            imageReader?.setOnImageAvailableListener(null, null)
            virtualDisplay?.release()
            imageReader?.close()
        } catch (e: Exception) {
            Log.w(tag, "Error stopping web mirror", e)
        } finally {
            virtualDisplay = null
            imageReader = null
            handlerThread?.quitSafely()
            handlerThread = null
            handler = null
        }
    }

    companion object {
        /**
         * The browser mirror always produces this exact frame, independent of
         * the phone's display or the native stream configuration. The page
         * must letterbox this aspect ratio, not the rotated display size.
         */
        const val DEFAULT_WIDTH = 1280
        const val DEFAULT_HEIGHT = 720
    }
}
