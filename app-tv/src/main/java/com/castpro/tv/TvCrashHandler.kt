package com.castpro.tv

import android.content.Context
import android.util.Log
import java.io.File

private const val TAG = "CastProTvCrash"

/** Public paths checked in this order; the app-private copy always works. */
private val CRASH_PATHS = listOf(
    "/sdcard/Download/castpro-tv-crash.log",
    "/sdcard/castpro-tv-crash.log"
)

/** Path shown in the receiver UI after a restart, so a trace can be read without adb. */
fun tvCrashLogFile(context: Context): File =
    File(context.applicationContext.filesDir, "castpro-tv-crash.log")

/**
 * Records the exact exception instead of leaving a bare "app has stopped" dialog.
 *
 * A force close during casting is useless without the stack trace: the TV has
 * no logcat access for most users. Install this before any receiver component
 * is created, keep the default handler's behaviour of terminating the process,
 * and surface the trace on the next launch.
 */
fun installTvCrashHandler(context: Context) {
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    val appContext = context.applicationContext
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        val trace = buildString {
            append("thread=").append(thread.name).append('\n')
            append(android.util.Log.getStackTraceString(throwable))
        }
        val privateFile = tvCrashLogFile(appContext)
        for (path in CRASH_PATHS + privateFile.absolutePath) {
            runCatching { File(path).writeText(trace) }
        }
        Log.e(TAG, trace)
        // Preserve the platform's own force-close reporting when present.
        previous?.uncaughtException(thread, throwable)
            ?: run {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(1)
            }
    }
}
