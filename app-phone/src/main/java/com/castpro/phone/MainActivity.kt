package com.castpro.phone

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.castpro.phone.service.ScreenCaptureService
import com.castpro.phone.ui.screens.PhoneDashboardScreen
import com.castpro.phone.ui.theme.CastProTheme
import com.castpro.phone.viewmodel.PhoneStreamViewModel
import com.castpro.phone.viewmodel.StreamStatus

class MainActivity : ComponentActivity() {
    private val viewModel: PhoneStreamViewModel by viewModels()

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val host = viewModel.uiState.value.manualIp.trim()
            val config = viewModel.getStreamConfig()

            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
                putExtra(ScreenCaptureService.EXTRA_HOST, host)
                putExtra(ScreenCaptureService.EXTRA_PORT, config.port)
                putExtra(ScreenCaptureService.EXTRA_GAMING_MODE, config.isGamingMode)
                putExtra(ScreenCaptureService.EXTRA_WIDTH, config.width)
                putExtra(ScreenCaptureService.EXTRA_HEIGHT, config.height)
                putExtra(ScreenCaptureService.EXTRA_DPI, config.dpi)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            viewModel.setStatus(StreamStatus.STREAMING)
        } else {
            viewModel.setStatus(StreamStatus.IDLE)
            Toast.makeText(this, "Screen capture permission is required to cast", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            CastProTheme {
                val state by viewModel.uiState.collectAsState()
                PhoneDashboardScreen(
                    state = state,
                    onToggleGamingMode = { viewModel.toggleGamingMode(it) },
                    onSelectReceiver = { viewModel.selectReceiver(it) },
                    onManualIpChanged = { viewModel.updateManualIp(it) },
                    onRefreshDiscovery = { viewModel.startDiscovery() },
                    onStartCastClicked = {
                        val host = viewModel.uiState.value.manualIp.trim()
                        if (host.isBlank()) {
                            Toast.makeText(this, "Please select or enter a TV IP address", Toast.LENGTH_SHORT).show()
                            return@PhoneDashboardScreen
                        }
                        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
                    },
                    onStopCastClicked = {
                        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
                            action = ScreenCaptureService.ACTION_STOP
                        }
                        startService(stopIntent)
                        viewModel.setStatus(StreamStatus.IDLE)
                    }
                )
            }
        }
    }
}
