package com.castpro.tv

import android.os.Bundle
import android.view.KeyEvent
import android.view.TextureView
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.runtime.getValue
import com.castpro.core.common.model.StreamConfig
import com.castpro.core.streaming.audio.TvAudioPlayer
import com.castpro.tv.render.TvTextureRenderer
import com.castpro.tv.ui.screens.TvReceiverScreen
import com.castpro.tv.viewmodel.TvReceiverViewModel

class TvMainActivity : ComponentActivity() {
    private val viewModel: TvReceiverViewModel by viewModels()
    private var surfaceRenderer: TvTextureRenderer? = null
    private var audioPlayer: TvAudioPlayer? = null
    private var serverStarted = false
    private var rootLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installTvCrashHandler(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Draw behind system bars so the video really reaches the panel edges.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())

        // Real root-layout instrumentation: these bounds are logged whenever the
        // window changes, so a quality change cannot silently shift the picture.
        val decorView = window.decorView
        val layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
            android.util.Log.i(
                "TvLayout",
                "Root bounds=${decorView.width}x${decorView.height} " +
                    "x=${decorView.x} y=${decorView.y} rotation=${decorView.rotation}"
            )
        }
        decorView.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        rootLayoutListener = layoutListener

        val player = TvAudioPlayer().apply { start() }
        audioPlayer = player

        setContent {
            val state by viewModel.uiState.collectAsState()
            // Apply display-mode changes to the live decoder and its texture
            // matrix without restarting the stream.
            LaunchedEffect(state.displayMode) {
                surfaceRenderer?.setDisplayMode(state.displayMode)
            }
            TvReceiverScreen(
                state = state,
                onDismissCrash = { viewModel.dismissCrashReport() },
                onDisplayModeChanged = { mode -> viewModel.setDisplayMode(mode) },
                onColorFilterChanged = { filter -> viewModel.setColorFilter(filter) },
                onCycleDisplayMode = { viewModel.cycleDisplayMode() },
                onCycleColorFilter = { viewModel.cycleColorFilter() },
                onToggleSettings = { viewModel.toggleSettingsPanel() },
                onToggleCompactHud = { viewModel.toggleCompactHud() },
                onResetServer = { viewModel.resetServer() },
                onSurfaceCreated = { textureView ->
                    textureView.addOnLayoutChangeListener { view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                        if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) {
                            android.util.Log.i(
                                "TvLayout",
                                "Video bounds=${right - left}x${bottom - top} " +
                                    "l=$left t=$top r=$right b=$bottom"
                            )
                        }
                    }
                    val renderer = TvTextureRenderer(
                        textureView = textureView,
                        initialConfig = viewModel.uiState.value.streamConfig,
                        initialDisplayMode = viewModel.uiState.value.displayMode,
                        onMetricsUpdated = { metrics ->
                            runOnUiThread { viewModel.updateMetrics(metrics) }
                        },
                        onError = { message ->
                            // Surface-level codec errors are non-fatal; keep
                            // the receiver usable but show what happened.
                            android.util.Log.e("TvMainActivity", message)
                            runOnUiThread { viewModel.reportError(message) }
                        },
                        clockOffsetProvider = { viewModel.currentClockOffsetNs() },
                        onFrameRendered = {
                            runOnUiThread { viewModel.markFrameRendered() }
                        }
                    )
                    surfaceRenderer = renderer

                    if (!serverStarted) {
                        serverStarted = true
                        viewModel.startServer(
                            onStreamConfig = { packet ->
                                val config = StreamConfig(
                                    width = packet.width,
                                    height = packet.height,
                                    fps = packet.fps,
                                    bitrate = packet.bitrate,
                                    isGamingMode = packet.isGamingMode,
                                    codecMime = packet.codec.mimeType
                                )
                                surfaceRenderer?.onConfig(config)
                            },
                            onFrameReceived = { data, offset, length, timestampNano, isGamingMode, isCodecConfig, isKeyframe ->
                                // Do not reveal the video surface on packet arrival;
                                // only a real rendered frame may hide the standby UI.
                                surfaceRenderer?.onFrame(
                                    data,
                                    offset,
                                    length,
                                    timestampNano,
                                    isGamingMode,
                                    isCodecConfig,
                                    isKeyframe
                                )
                            },
                            onAudioReceived = { data, offset, length ->
                                player.playAudioChunk(data, offset, length)
                            },
                            onGeometryReceived = { geometry ->
                                // Rotation metadata only. Phone sensor geometry
                                // must never resize the video surface: doing so
                                // made the whole cast jump when quality changed.
                                surfaceRenderer?.onGeometry(geometry)
                            },
                            onSessionDisconnected = {
                                surfaceRenderer?.onSessionDisconnected()
                            }
                        )
                    }
                }
            )
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // The remote's OK button must always reach Cast settings while casting.
        // HUD visibility has its own key so OK is never swallowed by a toggle.
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_MENU -> {
                if (viewModel.uiState.value.showSettingsPanel) {
                    viewModel.toggleSettingsPanel()
                } else {
                    viewModel.openSettingsPanel()
                }
                return true
            }
            KeyEvent.KEYCODE_INFO -> {
                viewModel.toggleHudOverlay()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        rootLayoutListener?.let { window.decorView.viewTreeObserver.removeOnGlobalLayoutListener(it) }
        rootLayoutListener = null
        super.onDestroy()
        surfaceRenderer?.release()
        audioPlayer?.stop()
        audioPlayer = null
    }
}
