package com.castpro.phone.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.castpro.phone.ui.components.QrCodeAnalyzer
import com.castpro.phone.ui.theme.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun PhoneQrScanScreen(
    onQrCodeDetected: (String) -> Boolean
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnQrCodeDetected = rememberUpdatedState(onQrCodeDetected)
    val handled = remember { AtomicBoolean(false) }
    var manualQrInput by remember { mutableStateOf("") }
    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var permissionAsked by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var scannedCode by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionAsked = true
        permissionGranted = granted
        if (!granted) cameraError = "Camera permission is required to scan the TV QR code."
    }

    LaunchedEffect(Unit) {
        if (!permissionGranted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    LaunchedEffect(permissionGranted, lifecycleOwner) {
        if (!permissionGranted) return@LaunchedEffect
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            runCatching {
                val provider = future.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()
                analysis.setAnalyzer(analyzerExecutor, QrCodeAnalyzer { code ->
                    // The analyzer runs on a camera executor. ViewModel updates
                    // are thread-safe, but Toast and Compose state belong on main.
                    mainHandler.post {
                        if (!handled.compareAndSet(false, true)) return@post
                        val accepted = runCatching {
                            currentOnQrCodeDetected.value(code)
                        }.getOrDefault(false)
                        if (accepted) {
                            scannedCode = code
                        } else {
                            handled.set(false)
                        }
                    }
                })
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
                cameraProvider = provider
                cameraError = null
            }.onFailure { error ->
                cameraError = error.message ?: "Camera is not available on this device."
            }
        }, ContextCompat.getMainExecutor(context))
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { cameraProvider?.unbindAll() }
            analyzerExecutor.shutdown()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GamerDarkBg)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Scan & Pair Instantly",
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Open Cast Pro TV, then point this camera at the QR code on the TV.",
            color = GamerTextSecondary,
            fontSize = 14.sp,
            textAlign = TextAlign.Center
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(290.dp)
                .background(Color(0xFF070B11), RoundedCornerShape(20.dp))
                .border(2.dp, GamerAccent, RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (permissionGranted) {
                AndroidView(
                    factory = { previewView },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(6.dp)
                )
                if (scannedCode != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xCC070B11)),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = GamerNeonGreen,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text("TV found", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text("Switching to the receiver list…", color = GamerTextSecondary, fontSize = 12.sp)
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.64f)
                            .fillMaxHeight(0.64f)
                            .border(3.dp, GamerNeonGreen.copy(alpha = 0.9f), RoundedCornerShape(12.dp))
                    )
                }
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.QrCodeScanner,
                        contentDescription = null,
                        tint = GamerAccent,
                        modifier = Modifier.size(64.dp)
                    )
                    Text(
                        text = cameraError ?: "Camera permission is needed to scan the TV code.",
                        color = Color.White,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                            colors = ButtonDefaults.buttonColors(containerColor = GamerNeonGreen)
                        ) {
                            Text("Allow camera", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                        if (permissionAsked) {
                            OutlinedButton(
                                onClick = {
                                    context.startActivity(
                                        Intent(
                                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            Uri.parse("package:${context.packageName}")
                                        )
                                    )
                                }
                            ) {
                                Text("Open settings", color = Color.White)
                            }
                        }
                    }
                }
            }
        }

        if (cameraError != null && permissionGranted) {
            Text(
                text = cameraError ?: "",
                color = Color(0xFFFFB300),
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = GamerCardBg),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, GamerCardBorder, RoundedCornerShape(16.dp))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Or enter the TV address",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "You can type the IP shown on the TV, e.g. 192.168.1.50 or 192.168.1.50:8998.",
                    color = GamerTextSecondary,
                    fontSize = 11.sp
                )
                OutlinedTextField(
                    value = manualQrInput,
                    onValueChange = { manualQrInput = it },
                    placeholder = { Text("192.168.1.50:8998", color = Color.Gray, fontSize = 12.sp) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = GamerAccent,
                        unfocusedBorderColor = GamerCardBorder,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        val value = manualQrInput.trim()
                        if (value.isNotEmpty()) onQrCodeDetected(value)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = GamerNeonGreen),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = manualQrInput.isNotBlank()
                ) {
                    Text("Pair Display", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        }

        Text(
            text = "TV and phone must be on the same Wi-Fi or phone hotspot.",
            color = GamerTextSecondary,
            fontSize = 11.sp,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(12.dp))
    }
}
