package com.castpro.phone.service

import android.content.Context
import android.provider.Settings

/**
 * Dims the physical panel without drawing an overlay over the mirrored display.
 * This is not true display-off: MediaProjection needs the display pipeline alive.
 */
internal class ScreenDimController(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("castpro_screen_dim", Context.MODE_PRIVATE)

    fun isDimmed(): Boolean = prefs.getBoolean(KEY_ACTIVE, false)

    fun toggle(): Boolean = if (isDimmed()) restore() else dim()

    fun dim(): Boolean {
        if (!Settings.System.canWrite(appContext)) return false
        return runCatching {
            if (!prefs.getBoolean(KEY_SAVED, false)) {
                val brightness = Settings.System.getInt(
                    appContext.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    DEFAULT_BRIGHTNESS
                )
                val mode = Settings.System.getInt(
                    appContext.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                )
                check(prefs.edit()
                    .putInt(KEY_ORIGINAL_BRIGHTNESS, brightness)
                    .putInt(KEY_ORIGINAL_MODE, mode)
                    .putBoolean(KEY_SAVED, true)
                    .commit()) { "Could not save the original brightness" }
            }
            check(Settings.System.putInt(
                appContext.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )) { "Could not switch to manual brightness" }
            check(Settings.System.putInt(
                appContext.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                MIN_BRIGHTNESS
            )) { "Could not lower screen brightness" }
            prefs.edit().putBoolean(KEY_ACTIVE, true).apply()
            true
        }.getOrElse { false }
    }

    fun restore(): Boolean {
        if (!prefs.getBoolean(KEY_SAVED, false)) return true
        if (!Settings.System.canWrite(appContext)) return false
        return runCatching {
            val brightness = prefs.getInt(KEY_ORIGINAL_BRIGHTNESS, DEFAULT_BRIGHTNESS)
            val mode = prefs.getInt(
                KEY_ORIGINAL_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            check(Settings.System.putInt(
                appContext.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                brightness.coerceIn(MIN_BRIGHTNESS, MAX_BRIGHTNESS)
            )) { "Could not restore brightness" }
            check(Settings.System.putInt(
                appContext.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                mode
            )) { "Could not restore brightness mode" }
            prefs.edit().clear().apply()
            true
        }.getOrElse { false }
    }

    private companion object {
        const val KEY_ACTIVE = "active"
        const val KEY_SAVED = "saved"
        const val KEY_ORIGINAL_BRIGHTNESS = "original_brightness"
        const val KEY_ORIGINAL_MODE = "original_mode"
        const val MIN_BRIGHTNESS = 1
        const val MAX_BRIGHTNESS = 255
        const val DEFAULT_BRIGHTNESS = 128
    }
}
