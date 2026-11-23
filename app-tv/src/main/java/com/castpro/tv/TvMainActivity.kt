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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val player = TvAudioPlayer().apply { start() }
        audioPlayer = player

        viewModel.startServer(
            onFrameReceived = { data, offset, length, timestampNano, isGamingMode ->
                surfaceRenderer?.onFrame(data, offset, length, timestampNano, isGamingMode)
            },
            onAudioReceived = { data, offset, length ->
                player.playAudioChunk(data, offset, length)
            },
            onGeometryReceived = { geometry ->
                surfaceRenderer?.onGeometry(geometry)
            }
        )

        setContent {
            val state by viewModel.uiState.collectAsState()
            TvReceiverScreen(
                state = state,
                onSurfaceCreated = { surfaceView ->
                    surfaceRenderer = TvSurfaceRenderer(
                        surfaceView = surfaceView,
                        config = StreamConfig(),
                        onMetricsUpdated = { metrics ->
                            runOnUiThread {
                                viewModel.updateMetrics(metrics)
                            }
                        }
                    )
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
