package com.castpro.core.streaming.monitor

import android.content.Context
import android.os.PowerManager
import android.util.Log

class ScreenWakeLock(context: Context) {
    private val tag = "ScreenWakeLock"
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var wakeLock: PowerManager.WakeLock? = null

    @Suppress("DEPRECATION")
    fun acquire() {
        if (wakeLock?.isHeld == true) return
        try {
            val lock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
                "CastPro:GamingWakeLock"
            )
            lock.setReferenceCounted(false)
            lock.acquire(4 * 60 * 60 * 1000L) // 4 hours maximum timeout safety guard
            wakeLock = lock
            Log.d(tag, "Screen wake lock acquired for gaming session")
        } catch (e: Exception) {
            Log.e(tag, "Failed to acquire wake lock", e)
        }
    }

    fun release() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d(tag, "Screen wake lock released")
            }
        } catch (e: Exception) {
            Log.e(tag, "Error releasing wake lock", e)
        } finally {
            wakeLock = null
        }
    }
}
