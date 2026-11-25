package com.castpro.phone.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.util.TypedValue
import android.view.*
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.castpro.core.common.model.CastSessionBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs

class InGameOverlayService : Service() {
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var statsText: TextView? = null
    private var muteButton: TextView? = null
    private var isPaused = false
    private var isMuted = false
    private val overlayScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        const val ACTION_SHOW = "com.castpro.phone.overlay.SHOW"
        const val ACTION_HIDE = "com.castpro.phone.overlay.HIDE"
        const val ACTION_UPDATE_STATS = "com.castpro.phone.overlay.UPDATE_STATS"
        const val ACTION_SET_MUTED = "com.castpro.phone.overlay.SET_MUTED"

        const val EXTRA_LATENCY_MS = "extra_latency_ms"
        const val EXTRA_FPS = "extra_fps"
        const val EXTRA_MUTED = "extra_muted"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        overlayScope.launch {
            CastSessionBus.state.collect { telemetry ->
                updateStats(
                    // The phone has no receiver-to-sender latency channel yet.
                    // Show FPS only rather than inventing a 0 ms measurement.
                    latencyMs = -1f,
                    fps = telemetry.currentFps,
                    connected = telemetry.isReceiverConnected
                )
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> showOverlay()
            ACTION_HIDE -> hideOverlay()
            ACTION_UPDATE_STATS -> {
                val latency = intent.getFloatExtra(EXTRA_LATENCY_MS, 0f)
                val fps = intent.getIntExtra(EXTRA_FPS, 60)
                updateStats(latency, fps, connected = true)
            }
            ACTION_SET_MUTED -> {
                isMuted = intent.getBooleanExtra(EXTRA_MUTED, false)
                muteButton?.text = if (isMuted) "🔇" else "🔊"
            }
        }
        return START_NOT_STICKY
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showOverlay() {
        if (overlayView != null) return

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 200
        }

        // Build Draggable Pill View
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8))
            gravity = Gravity.CENTER_VERTICAL

            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#E60D1117"))
                cornerRadius = dpToPx(24).toFloat()
                setStroke(dpToPx(1), Color.parseColor("#00E5FF"))
            }
            background = bg
        }

        // 1. Live Indicator Dot
        val dot = View(this).apply {
            val dotBg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#00E676"))
            }
            background = dotBg
            layoutParams = LinearLayout.LayoutParams(dpToPx(8), dpToPx(8)).apply {
                marginEnd = dpToPx(8)
            }
        }
        rootLayout.addView(dot)

        // 2. Latency & FPS Text
        val statsText = TextView(this).apply {
            id = View.generateViewId()
            text = "LIVE • 60fps"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = android.graphics.Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dpToPx(12)
            }
        }
        this.statsText = statsText
        rootLayout.addView(statsText)

        // 3. Audio Mute Button
        val muteBtn = TextView(this).apply {
            text = "🔊"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dpToPx(8)
            }
            setOnClickListener {
                isMuted = !isMuted
                text = if (isMuted) "🔇" else "🔊"
                startService(Intent(this@InGameOverlayService, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_TOGGLE_MUTE
                })
            }
        }
        this.muteButton = muteBtn
        rootLayout.addView(muteBtn)

        // 4. Stop Cast Button (Red X)
        val stopBtn = TextView(this).apply {
            text = "⏹"
            setTextColor(Color.parseColor("#FF1744"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4))
            setOnClickListener {
                val stopIntent = Intent(this@InGameOverlayService, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_STOP
                }
                startService(stopIntent)
                hideOverlay()
            }
        }
        rootLayout.addView(stopBtn)

        // Drag & Snap Touch Handling
        rootLayout.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isClick = false

            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isClick = true
                        return false // Allow child views to process clicks if not dragged
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (abs(dx) > 10 || abs(dy) > 10) {
                            isClick = false
                            params.x = initialX + dx
                            params.y = initialY + dy
                            windowManager?.updateViewLayout(rootLayout, params)
                            return true
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isClick) {
                            // Snap to nearest edge (left or right)
                            val displayMetrics = resources.displayMetrics
                            val screenWidth = displayMetrics.widthPixels
                            params.x = if (params.x < screenWidth / 2) 20 else screenWidth - rootLayout.width - 20
                            windowManager?.updateViewLayout(rootLayout, params)
                            return true
                        }
                    }
                }
                return false
            }
        })

        try {
            windowManager?.addView(rootLayout, params)
            overlayView = rootLayout
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateStats(latencyMs: Float, fps: Int, connected: Boolean = true) {
        val view = statsText ?: return
        val state = when {
            !connected -> "WAIT"
            fps > 0 -> "${fps}fps"
            else -> "LIVE"
        }
        view.text = if (latencyMs > 0f) "${latencyMs.toInt()}ms • $state" else state
    }

    private fun hideOverlay() {
        overlayView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                overlayView = null
                statsText = null
                muteButton = null
            }
        }
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        overlayScope.cancel()
        hideOverlay()
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}
