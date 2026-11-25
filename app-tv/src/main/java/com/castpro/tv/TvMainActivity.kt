package com.castpro.tv

import android.os.Bundle
import android.view.KeyEvent
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
import com.castpro.tv.render.TvSurfaceRenderer
import com.castpro.tv.ui.screens.TvReceiverScreen
import com.castpro.tv.viewmodel.TvReceiverViewModel

class TvMainActivity : ComponentActivity() {
    private val viewModel: TvReceiverViewModel by viewModels()
    private var surfaceRenderer: TvSurfaceRenderer? = null
    private var audioPlayer: TvAudioPlayer? = null
    private var serverStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installTvCrashHandler(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Draw behind system bars so the video really reaches the panel edges.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())

        val player = TvAudioPlayer().apply { start() }
        audioPlayer = player

        setContent {
            val state by viewModel.uiState.collectAsState()
            // Apply display-mode changes to the live decoder without restarting
            // the stream. Cropping/fit is a MediaCodec surface transform.
            LaunchedEffect(state.displayMode) {
                surfaceRenderer?.setVideoScalingMode(state.displayMode.scalingMode)
            }
            TvReceiverScreen(
                state = state,
                onDismissCrash = { viewModel.dismissCrashReport() },
                onDisplayModeChanged = { mode -> viewModel.setDisplayMode(mode) },
                onColorFilterChanged = { filter -> viewModel.setColorFilter(filter) },
                onCycleDisplayMode = { viewModel.cycleDisplayMode() },
                onCycleColorFilter = { viewModel.cycleColorFilter() },
                onToggleSettings = { viewModel.toggleSettingsPanel() },
                onResetServer = { viewModel.resetServer() },
                onSurfaceCreated = { surfaceView ->
                    val renderer = TvSurfaceRenderer(
                        surfaceView = surfaceView,
                        initialConfig = viewModel.uiState.value.streamConfig,
                        initialScalingMode = viewModel.uiState.value.displayMode.scalingMode,
                        onMetricsUpdated = { metrics ->
                            runOnUiThread { viewModel.updateMetrics(metrics) }
                        },
                        onError = { message ->
                            // Surface-level codec errors are non-fatal; keep
                            // the receiver usable but show what happened.
                            android.util.Log.e("TvMainActivity", message)
                            runOnUiThread { viewModel.reportError(message) }
                        },
                        clockOffsetProvider = { viewModel.currentClockOffsetNs() }
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
                                // Rotation metadata only. The real phone aspect
                                // is used by Stretch mode; the decoder surface
                                // itself is never resized from sensor data.
                                viewModel.updateAspectRatio(geometry.aspectRatio)
                                surfaceRenderer?.onGeometry(geometry)
                            }
                        )
                    }
                }
            )
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_BUTTON_A) {
            if (viewModel.uiState.value.showSettingsPanel) {
                viewModel.toggleSettingsPanel()
            } else {
                viewModel.toggleHudOverlay()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        surfaceRenderer?.release()
        audioPlayer?.stop()
        audioPlayer = null
    }
}
