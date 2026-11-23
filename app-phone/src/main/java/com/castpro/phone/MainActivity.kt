package com.castpro.phone

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.castpro.phone.service.ScreenCaptureService
import com.castpro.phone.ui.screens.PhoneDashboardScreen
import com.castpro.phone.ui.screens.PhoneQrScanScreen
import com.castpro.phone.ui.screens.PhoneSessionStatsScreen
import com.castpro.phone.ui.screens.PhoneSettingsScreen
import com.castpro.phone.ui.theme.CastProTheme
import com.castpro.phone.ui.theme.GamerAccent
import com.castpro.phone.ui.theme.GamerDarkBg
import com.castpro.phone.ui.theme.GamerNeonGreen
import com.castpro.phone.viewmodel.PhoneStreamViewModel
import com.castpro.phone.viewmodel.StreamStatus

class MainActivity : ComponentActivity() {
    private val viewModel: PhoneStreamViewModel by viewModels()

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val host = viewModel.uiState.value.manualIp.trim()
            val settings = viewModel.uiState.value.settings
            val config = settings.toStreamConfig()

            // Check overlay permission if overlay is enabled
            if (settings.enableFloatingOverlay && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!Settings.canDrawOverlays(this)) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                    Toast.makeText(this, "Enable 'Display over other apps' for in-game HUD", Toast.LENGTH_LONG).show()
                }
            }

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
                putExtra(ScreenCaptureService.EXTRA_BITRATE, config.bitrate)
                putExtra(ScreenCaptureService.EXTRA_FPS, config.fps)
                putExtra(ScreenCaptureService.EXTRA_CODEC, config.codecMime)
                putExtra(ScreenCaptureService.EXTRA_ENABLE_OVERLAY, settings.enableFloatingOverlay)
                putExtra(ScreenCaptureService.EXTRA_ENABLE_WEB_SERVER, settings.enableWebServer)
                putExtra(ScreenCaptureService.EXTRA_ENABLE_WAKE_LOCK, settings.enableKeepAwake)
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

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            CastProTheme {
                val state by viewModel.uiState.collectAsState()

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("Cast Pro Gamer Suite") },
                            actions = {
                                IconButton(onClick = { viewModel.startDiscovery() }) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Refresh Discovery", tint = GamerAccent)
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = GamerDarkBg,
                                titleContentColor = GamerNeonGreen
                            )
                        )
                    },
                    bottomBar = {
                        NavigationBar(
                            containerColor = Color(0xFF0D1117),
                            tonalElevation = 8.dp
                        ) {
                            NavigationBarItem(
                                selected = state.activeNavTab == 0,
                                onClick = { viewModel.setNavTab(0) },
                                icon = { Icon(Icons.Default.Tv, contentDescription = "Cast") },
                                label = { Text("Cast") },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = GamerAccent,
                                    selectedTextColor = GamerAccent,
                                    indicatorColor = GamerAccent.copy(alpha = 0.2f),
                                    unselectedIconColor = Color.Gray,
                                    unselectedTextColor = Color.Gray
                                )
                            )
                            NavigationBarItem(
                                selected = state.activeNavTab == 1,
                                onClick = { viewModel.setNavTab(1) },
                                icon = { Icon(Icons.Default.QrCodeScanner, contentDescription = "Pair") },
                                label = { Text("Pair") },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = GamerAccent,
                                    selectedTextColor = GamerAccent,
                                    indicatorColor = GamerAccent.copy(alpha = 0.2f),
                                    unselectedIconColor = Color.Gray,
                                    unselectedTextColor = Color.Gray
                                )
                            )
                            NavigationBarItem(
                                selected = state.activeNavTab == 2,
                                onClick = { viewModel.setNavTab(2) },
                                icon = { Icon(Icons.Default.Tune, contentDescription = "Settings") },
                                label = { Text("Settings") },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = GamerAccent,
                                    selectedTextColor = GamerAccent,
                                    indicatorColor = GamerAccent.copy(alpha = 0.2f),
                                    unselectedIconColor = Color.Gray,
                                    unselectedTextColor = Color.Gray
                                )
                            )
                            NavigationBarItem(
                                selected = state.activeNavTab == 3,
                                onClick = { viewModel.setNavTab(3) },
                                icon = { Icon(Icons.Default.Analytics, contentDescription = "Telemetry") },
                                label = { Text("Telemetry") },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = GamerAccent,
                                    selectedTextColor = GamerAccent,
                                    indicatorColor = GamerAccent.copy(alpha = 0.2f),
                                    unselectedIconColor = Color.Gray,
                                    unselectedTextColor = Color.Gray
                                )
                            )
                        }
                    }
                ) { padding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .background(GamerDarkBg)
                    ) {
                        when (state.activeNavTab) {
                            0 -> PhoneDashboardScreen(
                                state = state,
                                onToggleGamingMode = { viewModel.toggleGamingMode(it) },
                                onToggleWebServer = { viewModel.toggleWebServer(it) },
                                onSelectReceiver = { viewModel.selectReceiver(it) },
                                onManualIpChanged = { viewModel.updateManualIp(it) },
                                onRefreshDiscovery = { viewModel.startDiscovery() },
                                onStartCastClicked = {
                                    val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                                    projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
                                },
                                onStopCastClicked = {
                                    val stopIntent = Intent(this@MainActivity, ScreenCaptureService::class.java).apply {
                                        action = ScreenCaptureService.ACTION_STOP
                                    }
                                    startService(stopIntent)
                                    viewModel.setStatus(StreamStatus.IDLE)
                                }
                            )
                            1 -> PhoneQrScanScreen(
                                onQrCodeDetected = { code ->
                                    val success = viewModel.handleScannedQrCode(code)
                                    if (success) {
                                        Toast.makeText(this@MainActivity, "Receiver Paired Successfully!", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(this@MainActivity, "Invalid QR Code format", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                            2 -> PhoneSettingsScreen(
                                settings = state.settings,
                                onSettingsChanged = { viewModel.updateSettings(it) }
                            )
                            3 -> PhoneSessionStatsScreen(
                                stats = state.sessionStats,
                                isStreaming = state.status == StreamStatus.STREAMING
                            )
                        }
                    }
                }
            }
        }
    }
}
