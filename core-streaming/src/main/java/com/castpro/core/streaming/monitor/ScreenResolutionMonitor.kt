package com.castpro.core.streaming.monitor

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.OrientationEventListener
import android.view.Surface
import android.view.WindowManager
import com.castpro.core.common.model.GeometryPacket
import com.castpro.core.streaming.lifecycle.OrientationReconfigurationPolicy

class ScreenResolutionMonitor(
    private val context: Context,
    private val onGeometryChanged: (GeometryPacket) -> Unit
) {
    private val tag = "ScreenResolutionMonitor"

    companion object {
        const val ORIENTATION_DEBOUNCE_MS = OrientationReconfigurationPolicy.DEBOUNCE_MS
    }
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val handler = Handler(Looper.getMainLooper())
    private var started = false
    private val debouncedCheck = Runnable { checkAndNotifyGeometry() }
    private var lastWidth = 0
    private var lastHeight = 0
    private var lastRotation = -1

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) scheduleGeometryCheck()
        }
    }

    private val orientationListener = object : OrientationEventListener(context) {
        override fun onOrientationChanged(orientation: Int) {
            scheduleGeometryCheck()
        }
    }

    @Synchronized
    fun start() {
        if (started) return
        started = true
        displayManager.registerDisplayListener(displayListener, handler)
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
        checkAndNotifyGeometry(force = true)
    }

    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        handler.removeCallbacks(debouncedCheck)
        orientationListener.disable()
        runCatching { displayManager.unregisterDisplayListener(displayListener) }
            .onFailure { Log.w(tag, "Could not unregister display listener", it) }
    }

    private fun scheduleGeometryCheck() {
        handler.removeCallbacks(debouncedCheck)
        handler.postDelayed(debouncedCheck, ORIENTATION_DEBOUNCE_MS)
    }

    @Synchronized
    fun checkAndNotifyGeometry(force: Boolean = false) {
        val (width, height, rotation) = getCurrentScreenMetrics()
        if (force || width != lastWidth || height != lastHeight || rotation != lastRotation) {
            lastWidth = width
            lastHeight = height
            lastRotation = rotation

            val aspectRatio = if (height > 0) width.toFloat() / height.toFloat() else 1.777f
            val rotationDegrees = when (rotation) {
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }

            val geometry = GeometryPacket(
                width = width,
                height = height,
                aspectRatio = aspectRatio,
                rotationDegrees = rotationDegrees
            )
            Log.d(tag, "Screen geometry update: ${width}x${height}, ratio=$aspectRatio, rot=$rotationDegrees")
            onGeometryChanged(geometry)
        }
    }

    @Suppress("DEPRECATION")
    private fun getCurrentScreenMetrics(): Triple<Int, Int, Int> {
        // Context.getDisplay() throws UnsupportedOperationException on Android 16+
        // when the Context is not attached to a window (e.g. a background
        // Service). Query the default display through DisplayManager, which is
        // safe from any Context, and fall back to resource metrics if a window
        // query misbehaves. This monitor runs on the main thread from the
        // orientation listener, so it must never throw.
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val display = runCatching { displayManager?.getDisplay(Display.DEFAULT_DISPLAY) }.getOrNull()
            ?: runCatching { windowManager.defaultDisplay }.getOrNull()
        val rotation = runCatching { display?.rotation }.getOrNull() ?: Surface.ROTATION_0

        var width = 0
        var height = 0
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                windowManager.currentWindowMetrics.bounds.let { bounds ->
                    width = bounds.width()
                    height = bounds.height()
                }
            } else {
                val metrics = android.util.DisplayMetrics()
                display?.getRealMetrics(metrics)
                width = metrics.widthPixels
                height = metrics.heightPixels
            }
        }
        if (width <= 0 || height <= 0) {
            val fallback = context.resources.displayMetrics
            width = fallback.widthPixels
            height = fallback.heightPixels
        }
        return Triple(width.coerceAtLeast(1), height.coerceAtLeast(1), rotation)
    }
}
