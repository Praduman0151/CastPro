package com.castpro.phone

import android.content.Context
import com.castpro.core.common.model.AudioMode
import com.castpro.core.common.model.BitratePreset
import com.castpro.core.common.model.Framerate
import com.castpro.core.common.model.PairingInfo
import com.castpro.core.common.model.Resolution
import com.castpro.core.common.model.StreamingSettings
import com.castpro.core.common.model.VideoCodec

/** Small, dependency-free persistence for the settings and last TV. */
class SettingsStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("castpro_settings", Context.MODE_PRIVATE)

    fun load(): StreamingSettings {
        return StreamingSettings(
            resolution = enumOf(prefs.getString(KEY_RESOLUTION, null), Resolution.FHD_1080P),
            framerate = enumOf(prefs.getString(KEY_FRAMERATE, null), Framerate.FPS_60),
            bitrate = enumOf(prefs.getString(KEY_BITRATE, null), BitratePreset.GAMING_15M),
            codec = enumOf(prefs.getString(KEY_CODEC, null), VideoCodec.H264),
            audioMode = enumOf(prefs.getString(KEY_AUDIO, null), AudioMode.INTERNAL_GAME),
            enableFloatingOverlay = prefs.getBoolean(KEY_OVERLAY, true),
            enableKeepAwake = prefs.getBoolean(KEY_WAKE, true),
            enableWebServer = prefs.getBoolean(KEY_WEB, true),
            webPort = prefs.getInt(KEY_WEB_PORT, 8080),
            isGamingMode = prefs.getBoolean(KEY_GAMING, true),
            autoConnect = prefs.getBoolean(KEY_AUTO_CONNECT, true)
        )
    }

    fun save(settings: StreamingSettings) {
        prefs.edit()
            .putString(KEY_RESOLUTION, settings.resolution.name)
            .putString(KEY_FRAMERATE, settings.framerate.name)
            .putString(KEY_BITRATE, settings.bitrate.name)
            .putString(KEY_CODEC, settings.codec.name)
            .putString(KEY_AUDIO, settings.audioMode.name)
            .putBoolean(KEY_OVERLAY, settings.enableFloatingOverlay)
            .putBoolean(KEY_WAKE, settings.enableKeepAwake)
            .putBoolean(KEY_WEB, settings.enableWebServer)
            .putInt(KEY_WEB_PORT, settings.webPort)
            .putBoolean(KEY_GAMING, settings.isGamingMode)
            .putBoolean(KEY_AUTO_CONNECT, settings.autoConnect)
            .apply()
    }

    fun loadLastReceiver(): PairingInfo? {
        val host = prefs.getString(KEY_LAST_HOST, null)?.trim().orEmpty()
        if (host.isBlank()) return null
        return PairingInfo(
            host = host,
            port = prefs.getInt(KEY_LAST_PORT, 8998),
            name = prefs.getString(KEY_LAST_NAME, null),
            webPort = prefs.getInt(KEY_LAST_WEB_PORT, 8080)
        )
    }

    fun saveLastReceiver(info: PairingInfo) {
        prefs.edit()
            .putString(KEY_LAST_HOST, info.host)
            .putInt(KEY_LAST_PORT, info.port)
            .putString(KEY_LAST_NAME, info.name)
            .putInt(KEY_LAST_WEB_PORT, info.webPort)
            .apply()
    }

    fun clearLastReceiver() {
        prefs.edit()
            .remove(KEY_LAST_HOST)
            .remove(KEY_LAST_PORT)
            .remove(KEY_LAST_NAME)
            .remove(KEY_LAST_WEB_PORT)
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumOf(value: String?, fallback: T): T {
        if (value.isNullOrBlank()) return fallback
        return runCatching { enumValueOf<T>(value) }.getOrDefault(fallback)
    }

    private companion object {
        const val KEY_RESOLUTION = "resolution"
        const val KEY_FRAMERATE = "framerate"
        const val KEY_BITRATE = "bitrate"
        const val KEY_CODEC = "codec"
        const val KEY_AUDIO = "audio_mode"
        const val KEY_OVERLAY = "overlay"
        const val KEY_WAKE = "wake"
        const val KEY_WEB = "web"
        const val KEY_WEB_PORT = "web_port"
        const val KEY_GAMING = "gaming"
        const val KEY_AUTO_CONNECT = "auto_connect"
        const val KEY_LAST_HOST = "last_host"
        const val KEY_LAST_PORT = "last_port"
        const val KEY_LAST_NAME = "last_name"
        const val KEY_LAST_WEB_PORT = "last_web_port"
    }
}
