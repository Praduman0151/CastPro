package com.castpro.phone.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.castpro.core.common.model.StreamConfig
import com.castpro.core.streaming.audio.GameAudioCapture
import com.castpro.core.streaming.encoder.HardwareScreenEncoder
import com.castpro.core.streaming.transport.LowLatencySocketClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ScreenCaptureService : Service() {
    private val tag = "ScreenCaptureService"
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: HardwareScreenEncoder? = null
    private var audioCapture: GameAudioCapture? = null
    private var socketClient: LowLatencySocketClient? = null

    companion object {
        const val ACTION_START = "com.castpro.phone.action.START"
        const val ACTION_STOP = "com.castpro.phone.action.STOP"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_HOST = "extra_host"
        const val EXTRA_PORT = "extra_port"
        const val EXTRA_GAMING_MODE = "extra_gaming_mode"
        const val EXTRA_WIDTH = "extra_width"
        const val EXTRA_HEIGHT = "extra_height"
        const val EXTRA_DPI = "extra_dpi"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Do NOT call startForeground here: On Android 14+, mediaProjection FGS
        // requires the MediaProjection token to be passed before or during startForeground.
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                val host = intent.getStringExtra(EXTRA_HOST) ?: "127.0.0.1"
                val port = intent.getIntExtra(EXTRA_PORT, 8998)
                val isGamingMode = intent.getBooleanExtra(EXTRA_GAMING_MODE, true)
                val width = intent.getIntExtra(EXTRA_WIDTH, 1920)
                val height = intent.getIntExtra(EXTRA_HEIGHT, 1080)
                val dpi = intent.getIntExtra(EXTRA_DPI, 320)

                if (resultData != null && resultCode != 0) {
                    startStreamingWithProjection(
                        resultCode = resultCode,
                        resultData = resultData,
                        host = host,
                        port = port,
                        isGamingMode = isGamingMode,
                        width = width,
                        height = height,
                        dpi = dpi
                    )
                } else {
                    Log.e(tag, "Missing MediaProjection data in ACTION_START")
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                stopStreaming()
            }
        }
        return START_NOT_STICKY
    }

    private fun startStreamingWithProjection(
        resultCode: Int,
        resultData: Intent,
        host: String,
        port: Int,
        isGamingMode: Boolean,
        width: Int,
        height: Int,
        dpi: Int
    ) {
        // Enforce foreground service with MEDIA_PROJECTION type on Android 14+
        val notification = createNotification("Cast Pro Connecting to $host...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1001, notification)
        }

        serviceScope.launch {
            try {
                val config = StreamConfig(
                    width = width,
                    height = height,
                    dpi = dpi,
                    port = port,
                    isGamingMode = isGamingMode
                )

                val client = LowLatencySocketClient(host, port)
                socketClient = client
                val outputStream = client.connect()

                val enc = HardwareScreenEncoder(config, outputStream)
                val inputSurface = enc.prepare()
                encoder = enc

                val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val projection = projectionManager.getMediaProjection(resultCode, resultData)
                mediaProjection = projection

                virtualDisplay = projection.createVirtualDisplay(
                    "CastPro-Display",
                    width,
                    height,
                    dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    inputSurface,
                    null,
                    null
                )

                enc.start()

                // Audio capture
                val audio = GameAudioCapture(projection, outputStream)
                audioCapture = audio
                audio.start()

                updateNotification("Streaming to $host • Gaming Mode: ${if (isGamingMode) "ON (60 FPS)" else "OFF"} • Audio: LIVE")
                Log.i(tag, "Screen casting successfully running")
            } catch (e: Exception) {
                Log.e(tag, "Failed to start streaming pipeline", e)
                stopStreaming()
            }
        }
    }

    fun stopStreaming() {
        try {
            audioCapture?.stop()
            encoder?.stop()
            virtualDisplay?.release()
            mediaProjection?.stop()
            socketClient?.disconnect()
        } catch (e: Exception) {
            Log.e(tag, "Error stopping stream", e)
        } finally {
            audioCapture = null
            encoder = null
            virtualDisplay = null
            mediaProjection = null
            socketClient = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopStreaming()
        serviceScope.cancel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "cast_pro_channel",
                "Cast Pro Streaming",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, "cast_pro_channel")
                .setContentTitle("Cast Pro Active")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Cast Pro Active")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build()
        }
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(1001, createNotification(text))
    }
}
