package com.castpro.phone.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.castpro.core.common.model.AudioMode
import com.castpro.core.common.model.SessionConnectionState
import com.castpro.core.common.model.CastSessionBus
import com.castpro.core.common.model.GeometryPacket
import com.castpro.core.common.model.StreamConfig
import com.castpro.core.common.protocol.Constants
import com.castpro.core.common.protocol.PacketHeader
import com.castpro.core.common.protocol.StreamConfigPacket
import com.castpro.core.streaming.audio.GameAudioCapture
import com.castpro.core.streaming.encoder.ScreenEncoderBackend
import com.castpro.core.streaming.encoder.ScreenEncoderFactory
import com.castpro.core.streaming.encoder.EncoderStats
import com.castpro.core.streaming.encoder.computeAspectMatchedSize
import com.castpro.core.streaming.monitor.ScreenResolutionMonitor
import com.castpro.core.streaming.lifecycle.CleanupStep
import com.castpro.core.streaming.lifecycle.IndependentCleanup
import com.castpro.core.streaming.lifecycle.PipelineReplacementGate
import com.castpro.core.streaming.lifecycle.KeyFrameRequestLimiter
import com.castpro.core.streaming.lifecycle.OrientationReconfigurationPolicy
import com.castpro.core.streaming.lifecycle.VideoStallAction
import com.castpro.core.streaming.lifecycle.VideoStallPolicy
import com.castpro.core.streaming.monitor.ScreenWakeLock
import com.castpro.core.streaming.network.LocalNetwork
import com.castpro.core.streaming.server.WebMirrorCapture
import com.castpro.core.streaming.server.WebStreamingServer
import com.castpro.core.streaming.transport.NoopStreamTransport
import com.castpro.core.streaming.transport.StreamTransport
import com.castpro.core.streaming.transport.TcpStreamTransport
import com.castpro.phone.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class ScreenCaptureService : Service() {
    private val tag = "ScreenCaptureService"
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: ScreenEncoderBackend? = null
    private var audioCapture: GameAudioCapture? = null
    private var transport: StreamTransport? = null
    private var resolutionMonitor: ScreenResolutionMonitor? = null
    private var wakeLock: ScreenWakeLock? = null
    private val screenDimController by lazy { ScreenDimController(this) }
    private var webServer: WebStreamingServer? = null
    private var webMirror: WebMirrorCapture? = null

    private val stopping = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val jpegSequence = AtomicInteger(0)
    private var audioMuted = false
    private var currentHost: String = ""
    private var currentReceiverName: String = ""
    @Volatile private var currentConfig: StreamConfig? = null
    private var currentStreamConfigPacket: StreamConfigPacket? = null
    // Live quality changes run on the service coroutine; transport reconnect
    // callbacks run on the TCP thread. Both touch the encoder/config pair, so
    // serialize them to avoid stale keyframes and half-swapped pipelines.
    private val pipelineLock = Any()
    private val pipelineGate = PipelineReplacementGate(pipelineLock)
    private val reconfigurationLock = Any()
    private val lifecycleLock = Any()
    // Incremented on every START/STOP. Atomic reads are used by preparation
    // work that runs outside pipelineLock.
    private val sessionGeneration = AtomicInteger(0)
    @Volatile private var currentRotationDegrees: Int = 0
    @Volatile private var useNativeEncoder: Boolean = false

    @Volatile
    private var transportConnected = false
    private var hasConnectedOnce = false
    private var lastTransportError: String? = null
    private var connectionTimeoutJob: Job? = null
    private var heartbeatJob: Job? = null
    private var videoStallWatchdogJob: Job? = null
    private val lastVideoFramesSent = AtomicLong(0)
    private val lastVideoProgressAtMs = AtomicLong(0)
    private val stallRecoveryRequested = AtomicBoolean(false)
    private val watchdogKeyFrameLimiter = KeyFrameRequestLimiter(minIntervalMs = 500L)

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(tag, "MediaProjection stopped by the system")
            serviceScope.launch { stopStreaming() }
        }
    }

    companion object {
        const val ACTION_START = "com.castpro.phone.action.START"
        const val ACTION_UPDATE_CONFIG = "com.castpro.phone.action.UPDATE_CONFIG"
        const val ACTION_STOP = "com.castpro.phone.action.STOP"
        const val ACTION_TOGGLE_MUTE = "com.castpro.phone.action.TOGGLE_MUTE"
        const val ACTION_TOGGLE_SCREEN_DIM = "com.castpro.phone.action.TOGGLE_SCREEN_DIM"
        const val ACTION_RESTORE_SCREEN_DIM = "com.castpro.phone.action.RESTORE_SCREEN_DIM"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_HOST = "extra_host"
        const val EXTRA_PORT = "extra_port"
        const val EXTRA_RECEIVER_NAME = "extra_receiver_name"
        const val EXTRA_GAMING_MODE = "extra_gaming_mode"
        const val EXTRA_WIDTH = "extra_width"
        const val EXTRA_HEIGHT = "extra_height"
        const val EXTRA_DPI = "extra_dpi"
        const val EXTRA_BITRATE = "extra_bitrate"
        const val EXTRA_FPS = "extra_fps"
        const val EXTRA_CODEC = "extra_codec"
        const val EXTRA_AUDIO_MODE = "extra_audio_mode"
        const val EXTRA_ENABLE_OVERLAY = "extra_enable_overlay"
        const val EXTRA_ENABLE_WEB_SERVER = "extra_enable_web_server"
        const val EXTRA_WEB_PORT = "extra_web_port"
        const val EXTRA_ENABLE_WAKE_LOCK = "extra_enable_wake_lock"
        /** Experimental opt-in; absent/false always selects HardwareScreenEncoder. */
        const val EXTRA_USE_NATIVE_ENCODER = "extra_use_native_encoder"

        private const val CHANNEL_ID = "cast_pro_channel"
        private const val NOTIFICATION_ID = 1001
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Use the display's live orientation/aspect ratio rather than forcing a
     * 16:9 virtual display, which makes Android bake letterbox bars into frames.
     */
    private fun cappedStreamDimensions(width: Int, height: Int): Pair<Int, Int> {
        val metrics = DisplayMetrics()
        val measured = runCatching {
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
        }.isSuccess
        val displayWidth = if (measured && metrics.widthPixels > 0) metrics.widthPixels else width
        val displayHeight = if (measured && metrics.heightPixels > 0) metrics.heightPixels else height
        return computeAspectMatchedSize(displayWidth, displayHeight)
    }

    private fun cappedFps(value: Int) = value.coerceIn(1, 60)
    private fun cappedBitrate(value: Int) = value.coerceIn(500_000, 12_000_000)

    private fun appendStatus(msg: String) {
        try {
            java.io.File("/sdcard/Download/castpro-status.txt").appendText(
                "${System.currentTimeMillis()} $msg\n"
            )
        } catch (_: Exception) {
        }
    }

    override fun onCreate() {
        super.onCreate()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val trace = "thread=${thread.name}\n${android.util.Log.getStackTraceString(throwable)}"
            for (path in listOf("/sdcard/Download/castpro-crash.log", "/sdcard/castpro-crash.log")) {
                try { java.io.File(path).writeText(trace); break } catch (_: Exception) {}
            }
            try { getExternalFilesDir(null)?.let { java.io.File(it, "castpro-crash.log").writeText(trace) } } catch (_: Exception) {}
            android.util.Log.e("CastProCrash", trace)
            android.os.Process.killProcess(android.os.Process.myPid())
            System.exit(1)
        }
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                useNativeEncoder = intent.getBooleanExtra(EXTRA_USE_NATIVE_ENCODER, false)
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                if (resultData == null || resultCode == 0) {
                    Log.e(tag, "Missing MediaProjection data in ACTION_START")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startStreamingWithProjection(
                    resultCode = resultCode,
                    resultData = resultData,
                    host = intent.getStringExtra(EXTRA_HOST).orEmpty(),
                    port = intent.getIntExtra(EXTRA_PORT, Constants.DEFAULT_STREAM_PORT),
                    receiverName = intent.getStringExtra(EXTRA_RECEIVER_NAME).orEmpty(),
                    isGamingMode = intent.getBooleanExtra(EXTRA_GAMING_MODE, true),
                    width = intent.getIntExtra(EXTRA_WIDTH, 1920),
                    height = intent.getIntExtra(EXTRA_HEIGHT, 1080),
                    dpi = intent.getIntExtra(EXTRA_DPI, 320),
                    bitrate = intent.getIntExtra(EXTRA_BITRATE, 12_000_000),
                    fps = intent.getIntExtra(EXTRA_FPS, 60),
                    codec = intent.getStringExtra(EXTRA_CODEC) ?: "video/avc",
                    audioMode = runCatching {
                        AudioMode.valueOf(intent.getStringExtra(EXTRA_AUDIO_MODE) ?: AudioMode.INTERNAL_GAME.name)
                    }.getOrDefault(AudioMode.INTERNAL_GAME),
                    enableOverlay = intent.getBooleanExtra(EXTRA_ENABLE_OVERLAY, true),
                    enableWebServer = intent.getBooleanExtra(EXTRA_ENABLE_WEB_SERVER, true),
                    webPort = intent.getIntExtra(EXTRA_WEB_PORT, Constants.DEFAULT_WEB_PORT),
                    enableWakeLock = intent.getBooleanExtra(EXTRA_ENABLE_WAKE_LOCK, true)
                )
            }
            ACTION_UPDATE_CONFIG -> {
                val requestedNativeEncoder = intent.getBooleanExtra(EXTRA_USE_NATIVE_ENCODER, useNativeEncoder)
                val encoderBackendChanged = requestedNativeEncoder != useNativeEncoder
                useNativeEncoder = requestedNativeEncoder
                // Apply quality changes to the live session. Recreating the
                // projection would require the consent dialog again, so keep
                // MediaProjection and only swap the virtual display + encoder.
                applyLiveConfig(
                    width = intent.getIntExtra(EXTRA_WIDTH, 1920),
                    height = intent.getIntExtra(EXTRA_HEIGHT, 1080),
                    dpi = intent.getIntExtra(EXTRA_DPI, 320),
                    bitrate = intent.getIntExtra(EXTRA_BITRATE, 12_000_000),
                    fps = intent.getIntExtra(EXTRA_FPS, 60),
                    codec = intent.getStringExtra(EXTRA_CODEC) ?: "video/avc",
                    isGamingMode = intent.getBooleanExtra(EXTRA_GAMING_MODE, true),
                    forceRecreate = encoderBackendChanged
                )
            }
            ACTION_STOP -> stopStreaming()
            ACTION_TOGGLE_MUTE -> {
                audioMuted = !audioMuted
                audioCapture?.setMuted(audioMuted)
                updateNotification(if (audioMuted) "Audio muted" else "Audio on")
                startService(Intent(this, InGameOverlayService::class.java).apply {
                    action = InGameOverlayService.ACTION_SET_MUTED
                    putExtra(InGameOverlayService.EXTRA_MUTED, audioMuted)
                })
            }
            ACTION_TOGGLE_SCREEN_DIM -> {
                when {
                    !android.provider.Settings.System.canWrite(this) ->
                        updateNotification("Allow Modify System Settings in CastPro to dim the display")
                    screenDimController.toggle() ->
                        updateNotification(if (screenDimController.isDimmed()) {
                            "Screen dimmed for casting • restore from notification"
                        } else {
                            "Screen brightness restored"
                        })
                    else -> updateNotification("Could not change screen brightness • check system settings access")
                }
            }
            ACTION_RESTORE_SCREEN_DIM -> {
                updateNotification(
                    if (screenDimController.restore()) "Screen brightness restored"
                    else "Could not restore brightness • reopen CastPro and check permission"
                )
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Reconfigure an active cast when the user changes resolution, FPS, codec
     * or bitrate. The MediaProjection token stays alive, so no permission
     * dialog is needed; only the input surface and encoder are replaced.
     */
    private fun applyLiveConfig(
        width: Int,
        height: Int,
        dpi: Int,
        bitrate: Int,
        fps: Int,
        codec: String,
        isGamingMode: Boolean,
        forceRecreate: Boolean = false
    ) {
        if (!started.get() || stopping.get()) return
        serviceScope.launch {
            synchronized(reconfigurationLock) {
            val expectedGeneration = sessionGeneration.get()
            if (!started.get() || stopping.get()) return@synchronized
            val project = mediaProjection ?: return@synchronized
            val tcp = transport ?: return@launch
            if (!transportConnected) return@launch
            val (outputWidth, outputHeight) = cappedStreamDimensions(width, height)
            val safeFps = cappedFps(fps)
            val safeBitrate = cappedBitrate(bitrate)
            val next = StreamConfig(
                width = outputWidth,
                height = outputHeight,
                dpi = dpi,
                fps = safeFps,
                bitrate = safeBitrate,
                port = currentConfig?.port ?: Constants.DEFAULT_STREAM_PORT,
                isGamingMode = isGamingMode,
                codecMime = codec
            )
            if (currentConfig == next && !forceRecreate) return@launch
            appendStatus("applyLiveConfig ${outputWidth}x$outputHeight@${safeFps}fps ${safeBitrate / 1_000_000}M $codec")
            Log.i(tag, "Applying live quality: ${next.width}x${next.height}@${next.fps} $codec")
            try {
                // Prepare the replacement encoder first. If this fails the
                // current stream keeps running instead of being left with no
                // encoder and no capture surface.
                val (enc, inputSurface) = ScreenEncoderFactory.prepare(
                    config = next,
                    transport = tcp,
                    onStats = { stats ->
                        recordEncoderStats(stats)
                        CastSessionBus.updateStats(
                            fps = stats.fps,
                            bitrateMbps = stats.bitrateMbps,
                            framesSent = stats.framesSent,
                            bytesSent = stats.bytesSent,
                            droppedFrames = stats.droppedFrames
                        )
                    },
                    onError = { message -> CastSessionBus.setError(message) },
                    useNativeEncoder = useNativeEncoder
                )
                val installed = pipelineGate.installOrRelease(
                    expectedGeneration = expectedGeneration,
                    currentGeneration = { sessionGeneration.get() },
                    isStarted = { started.get() },
                    isStopping = { stopping.get() },
                    replacement = enc,
                    release = { replacement ->
                        runCatching { replacement.stop() }
                        runCatching { inputSurface.release() }
                    },
                    install = {
                    if (!transportConnected || mediaProjection !== project || transport !== tcp) {
                        runCatching { enc.stop() }
                        runCatching { inputSurface.release() }
                        throw IllegalStateException("Session resources changed while applying quality")
                    }
                    // Do NOT create a second VirtualDisplay from the same
                    // MediaProjection: Android rejects that even after release().
                    // Keep the display alive and swap the encoder's input surface.
                    val oldDisplay = virtualDisplay
                    encoder?.stop()
                    encoder = enc
                    lastVideoFramesSent.set(0)
                    lastVideoProgressAtMs.set(System.nanoTime() / 1_000_000L)
                    stallRecoveryRequested.set(false)

                    currentConfig = next
                    currentStreamConfigPacket = StreamConfigPacket(
                        codec = com.castpro.core.common.model.VideoCodec.fromMime(codec),
                        width = outputWidth,
                        height = outputHeight,
                        fps = safeFps,
                        bitrate = safeBitrate,
                        rotationDegrees = currentRotationDegrees,
                        isGamingMode = isGamingMode
                    )
                    // The TV reconfigures its decoder on this packet, so send it
                    // before the first frame of the new pipeline.
                    sendStreamConfig()

                    if (oldDisplay == null) {
                        virtualDisplay = project.createVirtualDisplay(
                            "CastPro-Display",
                            outputWidth,
                            outputHeight,
                            dpi,
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                            inputSurface,
                            null,
                            null
                        )
                        if (virtualDisplay == null) {
                            throw IllegalStateException("Could not create the capture display")
                        }
                    } else {
                        // Same MediaProjection, but the capture display itself must
                        // be resized. Swapping only the encoder surface left the
                        // old-size mirror scaling into a new-size format, which the
                        // TV saw as a horizontal shift. VirtualDisplay.resize()
                        // keeps the mirror geometry in step with the encoder.
                        runCatching { oldDisplay.resize(outputWidth, outputHeight, dpi) }
                            .onFailure { Log.w(tag, "VirtualDisplay.resize was rejected", it) }
                        oldDisplay.setSurface(inputSurface)
                        virtualDisplay = oldDisplay
                        // Keep rotation metadata aligned with the new pipeline.
                        resolutionMonitor?.checkAndNotifyGeometry(force = true)
                    }
                    sendHeartbeat(tcp)
                    enc.requestKeyFrame()
                    CastSessionBus.setError(null)
                    updateNotification("Streaming ${outputWidth}×${outputHeight} @ ${safeFps}FPS • quality updated")
                    CastSessionBus.setAppliedQuality(
                        "${outputWidth}x${outputHeight} · ${fps} fps · ${codec.substringAfterLast('/').uppercase()}"
                    )
                    appendStatus("applyLiveConfig done")
                    }
                )
                if (!installed) {
                    Log.i(tag, "Discarded prepared encoder because session generation changed")
                    return@synchronized
                }
            } catch (e: Exception) {
                Log.e(tag, "Could not apply live quality change", e)
                stallRecoveryRequested.set(false)
                CastSessionBus.setAppliedQuality(null)
                CastSessionBus.setError("Could not apply quality: ${e.message ?: "encoder error"}")
                appendStatus("applyLiveConfig failed: ${e.message}")
            }
            }
        }
    }

    private fun startStreamingWithProjection(
        resultCode: Int,
        resultData: Intent,
        host: String,
        port: Int,
        receiverName: String,
        isGamingMode: Boolean,
        width: Int,
        height: Int,
        dpi: Int,
        bitrate: Int,
        fps: Int,
        codec: String,
        audioMode: AudioMode,
        enableOverlay: Boolean,
        enableWebServer: Boolean,
        webPort: Int,
        enableWakeLock: Boolean
    ) {
        val (streamWidth, streamHeight) = cappedStreamDimensions(width, height)
        val safeFps = cappedFps(fps)
        val safeBitrate = cappedBitrate(bitrate)
        // Serialize session ownership with stop completion. A new START cannot
        // clear `stopping` until the previous teardown has left pipelineLock.
        val generation = synchronized(lifecycleLock) {
            if (started.get()) stopStreaming()
            stopping.set(false)
            started.set(true)
            sessionGeneration.incrementAndGet()
        }
        appendStatus("startStreamingWithProjection entered generation=$generation")
        currentHost = host
        currentReceiverName = receiverName
        CastSessionBus.setConnectionState(
            if (host.isBlank()) SessionConnectionState.SEARCHING else SessionConnectionState.CONNECTING,
            host = host,
            receiverName = receiverName
        )
        val safeWebPort = webPort.coerceIn(1024, 65000)
        // Android 14+ permits exactly one virtual display per MediaProjection.
        // The native TV encoder and the browser JPEG mirror both need one, so
        // the low-latency TV path wins when a TV target exists.
        val webEnabled = enableWebServer && host.isBlank()
        if (host.isBlank() && !webEnabled) {
            failStart("Select a TV or enable the browser receiver first.")
            return
        }

        val notification = createNotification("Starting Cast Pro…")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                if (audioMode == AudioMode.MICROPHONE &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                ) {
                    types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
                startForeground(NOTIFICATION_ID, notification, types)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(tag, "Could not enter the mediaProjection foreground service", e)
            failStart(e.message ?: "Could not start the screen capture service")
            return
        }

        if (enableWakeLock) {
            val wake = ScreenWakeLock(this)
            wake.acquire()
            wakeLock = wake
        }

        serviceScope.launch {
            if (generation != sessionGeneration.get() || stopping.get()) return@launch
            try {
                val config = StreamConfig(
                    width = streamWidth,
                    height = streamHeight,
                    dpi = dpi,
                    fps = safeFps,
                    bitrate = safeBitrate,
                    port = port,
                    isGamingMode = isGamingMode,
                    codecMime = codec
                )
                currentConfig = config
                currentStreamConfigPacket = StreamConfigPacket(
                    codec = com.castpro.core.common.model.VideoCodec.fromMime(codec),
                    width = streamWidth,
                    height = streamHeight,
                    fps = safeFps,
                    bitrate = safeBitrate,
                    rotationDegrees = currentRotationDegrees,
                    isGamingMode = isGamingMode
                )
                val project = (getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager)
                    .getMediaProjection(resultCode, resultData)
                if (project == null) {
                    failStart("Screen recording permission was not granted. Start the projection and accept the dialog.")
                    return@launch
                }
                mediaProjection = project
                project.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
                CastSessionBus.start(host.takeIf { it.isNotBlank() })
                CastSessionBus.setConnectionState(
                    if (host.isBlank()) SessionConnectionState.SEARCHING else SessionConnectionState.CONNECTING,
                    host = currentHost,
                    receiverName = currentReceiverName.takeIf { it.isNotBlank() }
                )

                // 1. Browser receiver (web-only fallback; see webEnabled above)
                if (webEnabled) {
                    val web = WebStreamingServer(
                        context = this@ScreenCaptureService,
                        httpPort = safeWebPort,
                        wsPort = safeWebPort + 1,
                        onClientCountChanged = { count ->
                            CastSessionBus.setWebClients(count)
                            if (host.isBlank()) {
                                CastSessionBus.setConnectionState(
                                    if (count > 0) SessionConnectionState.CONNECTED else SessionConnectionState.SEARCHING,
                                    receiverName = if (count > 0) "Browser viewer" else null
                                )
                            }
                        },
                        onError = { message -> CastSessionBus.setError(message) }
                    )
                    web.start()
                    webServer = web

                    val mirror = WebMirrorCapture(
                        projection = project,
                        shouldCapture = { web.hasClients() },
                        onJpeg = { jpeg -> sendJpegToWeb(jpeg) },
                        onError = { message -> CastSessionBus.setError(message) }
                    )
                    mirror.start()
                    webMirror = mirror
                }

                // 2. Native TV connection (optional, low latency)
                if (host.isNotBlank()) {
                    val tcp = TcpStreamTransport(host, port) { connected, _, error ->
                        onTransportChanged(connected, error)
                    }
                    transport = tcp
                    tcp.start()
                    startHeartbeatLoop(tcp)

                    try {
                        val (enc, inputSurface) = ScreenEncoderFactory.prepare(
                            config = config,
                            transport = tcp,
                            onStats = { stats ->
                                recordEncoderStats(stats)
                                CastSessionBus.updateStats(
                                    fps = stats.fps,
                                    bitrateMbps = stats.bitrateMbps,
                                    framesSent = stats.framesSent,
                                    bytesSent = stats.bytesSent,
                                    droppedFrames = stats.droppedFrames
                                )
                            },
                            onError = { message -> CastSessionBus.setError(message) },
                            useNativeEncoder = useNativeEncoder
                        )
                        encoder = enc
                        lastVideoFramesSent.set(0)
                        lastVideoProgressAtMs.set(System.nanoTime() / 1_000_000L)
                        stallRecoveryRequested.set(false)
                        virtualDisplay = project.createVirtualDisplay(
                            "CastPro-Display",
                            streamWidth,
                            streamHeight,
                            dpi,
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                            inputSurface,
                            null,
                            null
                        )
                        if (virtualDisplay == null) {
                            failStart("Could not create a virtual display from the screen projection.")
                            return@launch
                        }
                    } catch (e: Exception) {
                        Log.e(tag, "Could not start TV encoder", e)
                        failStart(e.message ?: "Could not start the video encoder.")
                        return@launch
                    }

                }

                // Audio goes to the TV and to any browser viewer. Browser-only
                // sessions still need the capture thread, so use a sink that
                // drops packets when there is no native transport.
                if (host.isNotBlank() || webEnabled) {
                    try {
                        val audio = GameAudioCapture(
                            context = this@ScreenCaptureService,
                            projection = project,
                            transport = transport ?: NoopStreamTransport,
                            mode = audioMode,
                            packetListener = { header, payload ->
                                webServer?.broadcastPacket(header, payload)
                            },
                            onError = { message -> CastSessionBus.setError(message) }
                        )
                        audio.start()
                        audioCapture = audio
                    } catch (e: Exception) {
                        Log.e(tag, "Could not start audio", e)
                    }
                }

                // 3. Geometry/orientation updates for both receivers.
                val monitor = ScreenResolutionMonitor(this@ScreenCaptureService) { geometry ->
                    currentRotationDegrees = geometry.rotationDegrees
                    webServer?.broadcastGeometry(geometry)
                    transport?.let { sendGeometry(it, geometry) }

                    // Rebuild only when the physical display crosses portrait /
                    // landscape. The existing MediaProjection remains active;
                    // no consent prompt or manual settings action is required.
                    val active = currentConfig
                    if (started.get() && !stopping.get() && transportConnected && active != null) {
                        if (OrientationReconfigurationPolicy.shouldReconfigure(
                                active.width, active.height, geometry.width, geometry.height
                            )
                        ) {
                            Log.i(
                                tag,
                                "Display orientation changed to ${geometry.width}x${geometry.height}; " +
                                    "reconfiguring live capture from ${active.width}x${active.height}"
                            )
                            applyLiveConfig(
                                width = geometry.width,
                                height = geometry.height,
                                dpi = active.dpi,
                                bitrate = active.bitrate,
                                fps = active.fps,
                                codec = active.codecMime,
                                isGamingMode = active.isGamingMode
                            )
                        }
                    }
                }
                monitor.start()
                resolutionMonitor = monitor
                if (host.isNotBlank()) startVideoStallWatchdog()

                if (enableOverlay &&
                    (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this@ScreenCaptureService))
                ) {
                    runCatching {
                        startService(Intent(this@ScreenCaptureService, InGameOverlayService::class.java).apply {
                            action = InGameOverlayService.ACTION_SHOW
                        })
                    }
                }

                val destination = listOfNotNull(
                    receiverName.takeIf { host.isNotBlank() }?.let { "TV: $it" }
                        ?: currentHost.takeIf { host.isNotBlank() }?.let { "TV: $it" },
                    if (webEnabled) "Web: http://${LocalNetwork.activeIpv4(this@ScreenCaptureService)}:$safeWebPort" else null
                ).joinToString(" • ")
                updateNotification("Streaming $streamWidth×$streamHeight @ ${fps}FPS${if (destination.isBlank()) "" else " • $destination"}")
                CastSessionBus.setAppliedQuality(
                    "${streamWidth}x${streamHeight} · ${fps} fps · ${codec.substringAfterLast('/').uppercase()}"
                )
                Log.i(tag, "Screen casting pipeline started (host='$host', web=$webEnabled)")
            } catch (e: Exception) {
                Log.e(tag, "Failed to start streaming pipeline", e)
                stopStreaming(failure = e.message ?: "Could not start screen capture")
            }
        }
    }

    private fun onTransportChanged(connected: Boolean, error: String?) {
        transportConnected = connected
        CastSessionBus.setReceiverConnected(connected, currentHost)
        if (connected) {
            lastVideoProgressAtMs.set(System.nanoTime() / 1_000_000L)
            stallRecoveryRequested.set(false)
            hasConnectedOnce = true
            lastTransportError = null
            connectionTimeoutJob?.cancel()
            connectionTimeoutJob = null
            CastSessionBus.setConnectionState(
                SessionConnectionState.CONNECTED,
                currentHost,
                currentReceiverName.takeIf { it.isNotBlank() }
            )
            synchronized(pipelineLock) {
                sendStreamConfig()
                // Calibrate the TV's clock offset immediately, before the first
                // latency sample is reported.
                transport?.let { sendHeartbeat(it) }
                // requestKeyFrame() waits for native CSD when the encoder has
                // just been recreated, so reconnect cannot emit a bare IDR.
                encoder?.requestKeyFrame()
            }
            // Re-check orientation after the transport comes back: the display
            // may have rotated while the receiver was disconnected.
            resolutionMonitor?.checkAndNotifyGeometry(force = true)
            val name = currentReceiverName.takeIf { it.isNotBlank() }
                ?: currentHost.takeIf { it.isNotBlank() }
            updateNotification("Connected to TV${name?.let { " ($it)" } ?: ""}")
        } else {
            if (error != null) lastTransportError = error
            val state = if (hasConnectedOnce) {
                SessionConnectionState.DISCONNECTED
            } else {
                SessionConnectionState.CONNECTING
            }
            CastSessionBus.setConnectionState(
                state,
                currentHost,
                currentReceiverName.takeIf { it.isNotBlank() },
                error ?: lastTransportError
            )
            val target = currentHost.takeIf { it.isNotBlank() }
            updateNotification(
                if (state == SessionConnectionState.DISCONNECTED) {
                    "TV disconnected${target?.let { " ($it)" } ?: ""} • reconnecting…"
                } else {
                    "Connecting to TV${target?.let { " ($it)" } ?: ""}…"
                }
            )
            scheduleConnectionFailure()
        }
    }

    /**
     * A failing TCP connect is retried forever by TcpStreamTransport. Keep the
     * UI in Connecting/Disconnected while that happens, and only call it a
     * failure after a short grace period; a later reconnect clears it again.
     */
    private fun scheduleConnectionFailure() {
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = serviceScope.launch {
            delay(8_000)
            if (!transportConnected && started.get() && !stopping.get()) {
                CastSessionBus.setConnectionState(
                    SessionConnectionState.FAILED,
                    currentHost,
                    currentReceiverName.takeIf { it.isNotBlank() },
                    lastTransportError ?: "TV did not accept the connection"
                )
                updateNotification("Connection Failed${lastTransportError?.let { ": $it" } ?: ""}")
            }
        }
    }

    private fun failStart(message: String) {
        Log.e(tag, "Screen casting could not start: $message")
        stopStreaming(failure = message)
    }

    /**
     * The TV learns the phone/TV clock skew from these packets so its HUD can
     * report a real one-way latency instead of a value distorted by seconds of
     * wall-clock difference between the two devices.
     */
    private fun recordEncoderStats(stats: EncoderStats) {
        val previous = lastVideoFramesSent.getAndSet(stats.framesSent)
        if (stats.framesSent > previous) {
            lastVideoProgressAtMs.set(System.nanoTime() / 1_000_000L)
            stallRecoveryRequested.set(false)
        }
    }

    /**
     * Heartbeats only prove TCP liveness. This watchdog independently tracks
     * delivered video-frame progression and asks for an IDR, then rebuilds the
     * encoder/VirtualDisplay pipeline if video remains stalled.
     */
    private fun startVideoStallWatchdog() {
        videoStallWatchdogJob?.cancel()
        lastVideoFramesSent.set(0)
        lastVideoProgressAtMs.set(System.nanoTime() / 1_000_000L)
        stallRecoveryRequested.set(false)
        videoStallWatchdogJob = serviceScope.launch {
            while (isActive && started.get() && !stopping.get()) {
                delay(2_000)
                if (!transportConnected || encoder == null) continue
                val now = System.nanoTime() / 1_000_000L
                val stalledFor = now - lastVideoProgressAtMs.get()
                val action = VideoStallPolicy.action(stalledFor)
                if (action != VideoStallAction.NONE && watchdogKeyFrameLimiter.tryAcquire()) {
                    runCatching { encoder?.requestKeyFrame() }
                        .onFailure { Log.w(tag, "Stall watchdog could not request IDR", it) }
                }
                if (action == VideoStallAction.RECREATE_ENCODER &&
                    stallRecoveryRequested.compareAndSet(false, true)
                ) {
                    val active = currentConfig
                    if (active != null) {
                        Log.e(tag, "VIDEO_STALL detected: no delivered frame for ${stalledFor}ms; rebuilding encoder")
                        applyLiveConfig(
                            width = active.width,
                            height = active.height,
                            dpi = active.dpi,
                            bitrate = active.bitrate,
                            fps = active.fps,
                            codec = active.codecMime,
                            isGamingMode = active.isGamingMode,
                            forceRecreate = true
                        )
                    } else {
                        stallRecoveryRequested.set(false)
                    }
                }
            }
        }
    }

    private fun startHeartbeatLoop(activeTransport: StreamTransport) {
        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch {
            while (isActive && started.get() && !stopping.get() && transport === activeTransport) {
                sendHeartbeat(activeTransport)
                delay(1_000)
            }
        }
    }

    private fun sendHeartbeat(target: StreamTransport? = transport) {
        val active = target ?: return
        val header = PacketHeader(
            type = Constants.TYPE_HEARTBEAT,
            flags = 0,
            sequenceNumber = 0,
            timestampNano = System.currentTimeMillis() * 1_000_000L,
            payloadLength = 0
        )
        active.send(header, ByteArray(0))
    }

    private fun sendStreamConfig() {
        val tcp = transport ?: return
        val packet = currentStreamConfigPacket ?: return
        val payload = packet.toByteArray()
        val header = PacketHeader(
            type = Constants.TYPE_STREAM_CONFIG,
            flags = 0,
            sequenceNumber = 0,
            timestampNano = System.currentTimeMillis() * 1_000_000L,
            payloadLength = payload.size
        )
        tcp.send(header, payload)
    }

    private fun sendGeometry(tcp: StreamTransport, geometry: GeometryPacket) {
        val payload = geometry.toByteArray()
        val header = PacketHeader(
            type = Constants.TYPE_GEOMETRY,
            flags = 0,
            sequenceNumber = 0,
            timestampNano = System.currentTimeMillis() * 1_000_000L,
            payloadLength = payload.size
        )
        tcp.send(header, payload)
    }

    private fun sendJpegToWeb(jpeg: ByteArray) {
        val web = webServer ?: return
        val header = PacketHeader(
            type = Constants.TYPE_VIDEO_JPEG,
            flags = 0,
            sequenceNumber = jpegSequence.incrementAndGet(),
            timestampNano = System.currentTimeMillis() * 1_000_000L,
            payloadLength = jpeg.size
        )
        web.broadcastPacket(header, jpeg)
    }

    private fun sendSessionEnd(target: StreamTransport? = transport) {
        val active = target ?: return
        val header = PacketHeader(
            type = Constants.TYPE_SESSION_END,
            flags = 0,
            sequenceNumber = 0,
            timestampNano = System.currentTimeMillis() * 1_000_000L,
            payloadLength = 0
        )
        val sent = runCatching { active.send(header, ByteArray(0)) }.getOrDefault(false)
        Log.i(tag, "SESSION_END sent=$sent")
    }

    private fun stopStreaming(failure: String? = null) {
        synchronized(lifecycleLock) {
        // Invalidate preparation work before any network/resource cleanup.
        if (!stopping.compareAndSet(false, true)) return
        val generation = sessionGeneration.incrementAndGet()
        val failedHost = currentHost
        val failedName = currentReceiverName
        Log.i(tag, "Stopping screen casting${failure?.let { ": $it" } ?: ""}")
        pipelineGate.withPipelineLock {
        try {
            // IndependentCleanup guarantees later resources are released even
            // when an earlier close/stop implementation throws.
            IndependentCleanup.run(
            listOf(
                CleanupStep("SESSION_END") { sendSessionEnd() },
                CleanupStep("heartbeat") { heartbeatJob?.cancel() },
                CleanupStep("video stall watchdog") { videoStallWatchdogJob?.cancel() },
                CleanupStep("web mirror") { webMirror?.stop() },
                CleanupStep("resolution monitor") { resolutionMonitor?.stop() },
                CleanupStep("wake lock") { wakeLock?.release() },
                CleanupStep("web server") { webServer?.stop() },
                CleanupStep("audio capture") { audioCapture?.stop() },
                // Detach the encoder input surface before releasing the codec.
                CleanupStep("VirtualDisplay") { virtualDisplay?.release() },
                CleanupStep("encoder") { encoder?.stop() },
                CleanupStep("MediaProjection callback") {
                    mediaProjection?.unregisterCallback(projectionCallback)
                },
                CleanupStep("MediaProjection") { mediaProjection?.stop() },
                CleanupStep("transport") { transport?.stop() },
                CleanupStep("in-game overlay") {
                    startService(Intent(this, InGameOverlayService::class.java).apply {
                        action = InGameOverlayService.ACTION_HIDE
                    })
                }
            )
            ) { resource, error -> Log.w(tag, "Could not release $resource", error) }
            virtualDisplay = null
        } finally {
            // Always return the panel to the user's previous brightness after a cast.
            runCatching { screenDimController.restore() }
            webMirror = null
            resolutionMonitor = null
            wakeLock = null
            webServer = null
            audioCapture = null
            encoder = null
            virtualDisplay = null
            mediaProjection = null
            transport = null
            currentConfig = null
            currentStreamConfigPacket = null
            currentHost = ""
            currentReceiverName = ""
            audioMuted = false
            transportConnected = false
            hasConnectedOnce = false
            lastTransportError = null
            connectionTimeoutJob?.cancel()
            connectionTimeoutJob = null
            heartbeatJob?.cancel()
            heartbeatJob = null
            videoStallWatchdogJob = null
            lastVideoFramesSent.set(0)
            lastVideoProgressAtMs.set(0)
            stallRecoveryRequested.set(false)
            CastSessionBus.stop(failure)
            if (failure != null) {
                CastSessionBus.setConnectionState(
                    SessionConnectionState.FAILED,
                    failedHost.takeIf { it.isNotBlank() },
                    failedName.takeIf { it.isNotBlank() },
                    failure
                )
            }
            // stopForeground / stopSelf touch the service's main-thread state;
            // post them so a background (IO) teardown cannot trigger an ANR or
            // a CalledFromWrongThread crash.
            Handler(Looper.getMainLooper()).post {
                // A new START may already own the service; do not tear it down.
                if (generation == sessionGeneration.get()) {
                    stopForegroundCompat()
                    stopSelf()
                }
            }
            if (generation == sessionGeneration.get()) started.set(false)
        }
        }
        }
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
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
                CHANNEL_ID,
                "Cast Pro Streaming",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ScreenCaptureService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Cast Pro Active")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openIntent)
        if (screenDimController.isDimmed()) {
            val restoreIntent = PendingIntent.getService(
                this,
                2,
                Intent(this, ScreenCaptureService::class.java).apply { action = ACTION_RESTORE_SCREEN_DIM },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_menu_day, "Restore brightness", restoreIntent)
        }
        return builder
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(text: String) {
        runCatching {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, createNotification(text))
        }
    }
}
