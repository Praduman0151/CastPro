package com.castpro.phone.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
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

    inner class LocalBinder : Binder() {
        fun getService(): ScreenCaptureService = this@ScreenCaptureService
    }

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(1001, createNotification("Cast Pro Ready"))
    }

    fun startStreaming(
        resultCode: Int,
        resultData: Intent,
        targetHost: String,
        config: StreamConfig,
        onError: (String) -> Unit
    ) {
        serviceScope.launch {
            try {
                updateNotification("Connecting to $targetHost...")
                val client = LowLatencySocketClient(targetHost, config.port)
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
                    config.width,
                    config.height,
                    config.dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    inputSurface,
                    null,
                    null
                )

                enc.start()

                // Start synchronized low-latency game audio capture
                val audio = GameAudioCapture(projection, outputStream)
                audioCapture = audio
                audio.start()

                updateNotification("Streaming to $targetHost • Gaming Mode: ${if (config.isGamingMode) "ON (60 FPS)" else "Standard"} • Audio: LIVE")
                Log.i(tag, "Screen casting successfully running")
            } catch (e: Exception) {
                Log.e(tag, "Failed to start streaming", e)
                onError(e.message ?: "Failed to connect to receiver")
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
