package com.castpro.tv.ui.screens

import android.view.SurfaceView
import android.view.ViewGroup
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.castpro.tv.render.TvSurfaceRenderer
import com.castpro.tv.ui.components.TvGamerHudOverlay
import com.castpro.tv.ui.components.TvNeonBlue
import com.castpro.tv.ui.components.TvNeonGreen
import com.castpro.tv.viewmodel.TvUiState

@Composable
fun TvReceiverScreen(
    state: TvUiState,
    onSurfaceCreated: (SurfaceView) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Hardware Video Rendering Surface
        AndroidView(
            factory = { context ->
                SurfaceView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    onSurfaceCreated(this)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        if (!state.isStreaming) {
            // Standby / Pairing Screen
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF070B11)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    modifier = Modifier
                        .background(Color(0xFF111824), RoundedCornerShape(24.dp))
                        .border(2.dp, TvNeonGreen.copy(alpha = 0.4f), RoundedCornerShape(24.dp))
                        .padding(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Tv,
                        contentDescription = null,
                        tint = TvNeonGreen,
                        modifier = Modifier.size(72.dp)
                    )

                    Text(
                        text = "Cast Pro TV Receiver",
                        color = Color.White,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = "Offline Ultra-Low Latency Display • Gaming Mode Ready",
                        color = Color.Gray,
                        fontSize = 16.sp
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    if (state.qrCodeBitmap != null) {
                        Image(
                            bitmap = state.qrCodeBitmap.asImageBitmap(),
                            contentDescription = "Scan with Cast Pro Phone to Pair",
                            modifier = Modifier
                                .size(180.dp)
                                .background(Color.White, RoundedCornerShape(12.dp))
                                .padding(8.dp)
                        )
                        Text(
                            text = "Scan QR code with Cast Pro Phone app",
                            color = Color.LightGray,
                            fontSize = 14.sp
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(color = TvNeonGreen, modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
                        Text(
                            text = "Listening for Sender: ${state.localIpAddress}:8998",
                            color = TvNeonBlue,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Text(
                        text = "Connect phone to same Wi-Fi or Phone Hotspot",
                        color = Color.DarkGray,
                        fontSize = 14.sp
                    )
                }
            }
        } else {
            // Active Fullscreen HUD Overlay
            if (state.showHudOverlay) {
                TvGamerHudOverlay(
                    metrics = state.metrics,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(24.dp)
                )
            }
        }
    }
}
