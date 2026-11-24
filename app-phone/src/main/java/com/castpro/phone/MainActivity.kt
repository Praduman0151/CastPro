package com.castpro.phone

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.castpro.core.common.model.AudioMode
import com.castpro.core.common.model.CastSessionBus
import com.castpro.core.common.model.SessionConnectionState
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
    private var pendingProjectionStart = false
    private var audioPermissionDenied = false

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startCaptureService(result.resultCode, result.data!!)
        } else {
            viewModel.setStatus(StreamStatus.IDLE)
            Toast.makeText(this, "Screen capture permission is required to cast", Toast.LENGTH_SHORT).show()
        }
    }

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        audioPermissionDenied = !granted
        if (!granted) {
            Toast.makeText(
                this,
                "Audio permission denied. Casting will continue muted.",
                Toast.LENGTH_LONG
            ).show()
        }
        if (pendingProjectionStart) {
            pendingProjectionStart = false
            launchProjectionPrompt()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* The foreground service still runs if notifications are hidden. */ }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        // Capture crashes to a readable file so failures on this device can be
        // diagnosed without ADB access to the phone.
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val trace = "thread=${thread.name}\n${android.util.Log.getStackTraceString(throwable)}"
            for (path in listOf(
                "/sdcard/Download/castpro-crash.log",
                "/sdcard/castpro-crash.log"
            )) {
                try {
                    java.io.File(path).writeText(trace)
                    break
                } catch (_: Exception) {
                }
            }
            try {
                getExternalFilesDir(null)?.let { java.io.File(it, "castpro-crash.log").writeText(trace) }
            } catch (_: Exception) {
            }
            android.util.Log.e("CastProCrash", trace)
            android.os.Process.killProcess(android.os.Process.myPid())
            System.exit(1)
        }
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            CastProTheme {
                val state by viewModel.uiState.collectAsState()

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("Cast Pro Gamer Suite") },
                            actions = {
                                IconButton(onClick = { viewModel.refreshLocalNetwork(); viewModel.startDiscovery() }) {
                                    Icon(
                                        Icons.Default.Refresh,
                                        contentDescription = "Refresh Discovery",
                                        tint = GamerAccent
                                    )
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
                                colors = navItemColors()
                            )
                            NavigationBarItem(
                                selected = state.activeNavTab == 1,
                                onClick = { viewModel.setNavTab(1) },
                                icon = { Icon(Icons.Default.QrCodeScanner, contentDescription = "Pair") },
                                label = { Text("Pair") },
                                colors = navItemColors()
                            )
                            NavigationBarItem(
                                selected = state.activeNavTab == 2,
                                onClick = { viewModel.setNavTab(2) },
                                icon = { Icon(Icons.Default.Tune, contentDescription = "Settings") },
                                label = { Text("Settings") },
                                colors = navItemColors()
                            )
                            NavigationBarItem(
                                selected = state.activeNavTab == 3,
                                onClick = { viewModel.setNavTab(3) },
                                icon = { Icon(Icons.Default.Analytics, contentDescription = "Telemetry") },
                                label = { Text("Telemetry") },
                                colors = navItemColors()
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
                                onToggleAutoConnect = { viewModel.toggleAutoConnect(it) },
                                onSelectReceiver = { viewModel.selectReceiver(it) },
                                onManualIpChanged = { viewModel.updateManualIp(it) },
                                onRefreshDiscovery = { viewModel.startDiscovery() },
                                onScanQrClicked = { viewModel.setNavTab(1) },
                                onStartCastClicked = { beginStartFlow() },
                                onDismissError = { viewModel.clearError() },
                                onStopCastClicked = {
                                    val stopIntent = Intent(this@MainActivity, ScreenCaptureService::class.java).apply {
                                        action = ScreenCaptureService.ACTION_STOP
                                    }
                                    startService(stopIntent)
                                    CastSessionBus.stop()
                                    viewModel.setStatus(StreamStatus.IDLE)
                                }
                            )
                            1 -> PhoneQrScanScreen(
                                onQrCodeDetected = { code ->
                                    val success = viewModel.handleScannedQrCode(code)
                                    if (success) {
                                        Toast.makeText(
                                            this@MainActivity,
                                            "TV paired. Tap Start Stream to cast.",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    } else {
                                        Toast.makeText(
                                            this@MainActivity,
                                            "That is not a Cast Pro TV code",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                    success
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

    override fun onResume() {
        super.onResume()
        viewModel.onResume()
    }

    private fun beginStartFlow() {
        appendStatus("beginStartFlow")
        val state = viewModel.uiState.value
        audioPermissionDenied = false
        val target = viewModel.resolvedTarget()
        if (target == null && !state.settings.enableWebServer) {
            Toast.makeText(
                this,
                "Select a TV or enable the web receiver first",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        if (state.settings.enableFloatingOverlay &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || !Settings.canDrawOverlays(this))
        ) {
            Toast.makeText(
                this,
                "Allow 'Display over other apps' to see the in-game HUD",
                Toast.LENGTH_LONG
            ).show()
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }

        // Both internal playback capture and microphone capture are AudioRecord
        // clients and need RECORD_AUDIO. Asking only for microphone mode used
        // to leave the default internal-audio path without permission.
        if (state.settings.audioMode != AudioMode.MUTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingProjectionStart = true
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        launchProjectionPrompt()
    }

    private fun launchProjectionPrompt() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        // A caster mirrors the whole device. On Android 14+ the default dialog
        // can capture a single app, which would show only Cast Pro on the TV.
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            manager.createScreenCaptureIntent()
        }
        projectionLauncher.launch(intent)
    }

    private fun startCaptureService(resultCode: Int, resultData: Intent) {
        appendStatus("startCaptureService")
        val state = viewModel.uiState.value
        val target = viewModel.resolvedTarget()
        val config = viewModel.getStreamConfig()

        val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_START
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, resultData)
            putExtra(ScreenCaptureService.EXTRA_HOST, target?.host.orEmpty())
            putExtra(ScreenCaptureService.EXTRA_PORT, target?.port ?: config.port)
            putExtra(ScreenCaptureService.EXTRA_RECEIVER_NAME, target?.name.orEmpty())
            putExtra(ScreenCaptureService.EXTRA_GAMING_MODE, config.isGamingMode)
            putExtra(ScreenCaptureService.EXTRA_WIDTH, config.width)
            putExtra(ScreenCaptureService.EXTRA_HEIGHT, config.height)
            putExtra(ScreenCaptureService.EXTRA_DPI, config.dpi)
            putExtra(ScreenCaptureService.EXTRA_BITRATE, config.bitrate)
            putExtra(ScreenCaptureService.EXTRA_FPS, config.fps)
            putExtra(ScreenCaptureService.EXTRA_CODEC, config.codecMime)
            putExtra(
                ScreenCaptureService.EXTRA_AUDIO_MODE,
                if (audioPermissionDenied) AudioMode.MUTED.name else state.settings.audioMode.name
            )
            putExtra(ScreenCaptureService.EXTRA_ENABLE_OVERLAY, state.settings.enableFloatingOverlay)
            putExtra(ScreenCaptureService.EXTRA_ENABLE_WEB_SERVER, state.settings.enableWebServer)
            putExtra(ScreenCaptureService.EXTRA_WEB_PORT, state.settings.webPort)
            putExtra(ScreenCaptureService.EXTRA_ENABLE_WAKE_LOCK, state.settings.enableKeepAwake)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        viewModel.setActiveQuality(state.settings)
        viewModel.setStatus(StreamStatus.CONNECTING)
    }

    private fun appendStatus(msg: String) {
        try {
            java.io.File("/sdcard/Download/castpro-status.txt").appendText(
                "${System.currentTimeMillis()} $msg\\n"
            )
        } catch (_: Exception) {
        }
    }

    @Composable
    private fun navItemColors() = NavigationBarItemDefaults.colors(
        selectedIconColor = GamerAccent,
        selectedTextColor = GamerAccent,
        indicatorColor = GamerAccent.copy(alpha = 0.2f),
        unselectedIconColor = Color.Gray,
        unselectedTextColor = Color.Gray
    )
}
