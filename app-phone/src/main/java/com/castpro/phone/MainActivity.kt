package com.castpro.phone

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
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
    private var captureService: ScreenCaptureService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as ScreenCaptureService.LocalBinder
            captureService = binder.getService()
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            captureService = null
            isBound = false
        }
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val host = viewModel.uiState.value.manualIp
            val config = viewModel.getStreamConfig()

            val serviceIntent = Intent(this, ScreenCaptureService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

            captureService?.startStreaming(
                resultCode = result.resultCode,
                resultData = result.data!!,
                targetHost = host,
                config = config,
                onError = { error ->
                    runOnUiThread {
                        viewModel.setStatus(StreamStatus.ERROR, error)
                        Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                    }
                }
            )
            viewModel.setStatus(StreamStatus.STREAMING)
        } else {
            viewModel.setStatus(StreamStatus.IDLE)
            Toast.makeText(this, "Screen capture permission required", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val intent = Intent(this, ScreenCaptureService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

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
                        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
                    },
                    onStopCastClicked = {
                        captureService?.stopStreaming()
                        viewModel.setStatus(StreamStatus.IDLE)
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }
}
