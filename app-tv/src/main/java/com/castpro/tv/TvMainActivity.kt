package com.castpro.tv

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
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
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val player = TvAudioPlayer().apply { start() }
        audioPlayer = player

        setContent {
            val state by viewModel.uiState.collectAsState()
            TvReceiverScreen(
                state = state,
                onSurfaceCreated = { surfaceView ->
                    val renderer = TvSurfaceRenderer(
                        surfaceView = surfaceView,
                        initialConfig = viewModel.uiState.value.streamConfig,
                        onMetricsUpdated = { metrics ->
                            runOnUiThread { viewModel.updateMetrics(metrics) }
                        },
                        onError = { message ->
                            // Surface-level codec errors are non-fatal; keep
                            // the receiver usable but show what happened.
                            android.util.Log.e("TvMainActivity", message)
                            runOnUiThread { viewModel.reportError(message) }
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
                                runOnUiThread { viewModel.updateAspectRatio(geometry.aspectRatio) }
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
            viewModel.toggleHudOverlay()
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
