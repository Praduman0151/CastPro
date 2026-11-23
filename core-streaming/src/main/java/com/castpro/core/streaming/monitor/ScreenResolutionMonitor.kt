package com.castpro.core.streaming.monitor

import android.content.Context
import android.content.res.Configuration
import android.graphics.Point
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.OrientationEventListener
import android.view.Surface
import android.view.WindowManager
import com.castpro.core.common.model.GeometryPacket

class ScreenResolutionMonitor(
    private val context: Context,
    private val onGeometryChanged: (GeometryPacket) -> Unit
) {
    private val tag = "ScreenResolutionMonitor"
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var lastWidth = 0
    private var lastHeight = 0
    private var lastRotation = -1

    private val orientationListener = object : OrientationEventListener(context) {
        override fun onOrientationChanged(orientation: Int) {
            checkAndNotifyGeometry()
        }
    }

    fun start() {
        if (orientationListener.canDetectOrientation()) {
            orientationListener.enable()
        }
        checkAndNotifyGeometry(force = true)
    }

    fun stop() {
        orientationListener.disable()
    }

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
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display ?: windowManager.defaultDisplay
        } else {
            windowManager.defaultDisplay
        }

        val rotation = display?.rotation ?: Surface.ROTATION_0

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowMetrics = windowManager.currentWindowMetrics
            val bounds = windowMetrics.bounds
            Triple(bounds.width(), bounds.height(), rotation)
        } else {
            val point = Point()
            display?.getRealSize(point)
            Triple(point.x, point.y, rotation)
        }
    }
}
