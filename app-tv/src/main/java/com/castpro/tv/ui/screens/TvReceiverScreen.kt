package com.castpro.tv.ui.screens

import android.view.SurfaceView
import android.view.ViewGroup
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import com.castpro.core.common.protocol.Constants
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
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        // The decoder renders into this surface. It keeps the stream's aspect
        // ratio so a 16:9 phone does not stretch across a 4:3 panel.
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
            modifier = Modifier
                .fillMaxSize()
                .aspectRatio(state.aspectRatio.coerceIn(0.5f, 3f))
        )

        if (!state.isStreaming) {
            // Standby / pairing screen
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF070B11)),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(40.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(Color(0xFF111824), RoundedCornerShape(24.dp))
                        .border(2.dp, TvNeonGreen.copy(alpha = 0.4f), RoundedCornerShape(24.dp))
                        .padding(horizontal = 44.dp, vertical = 36.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.width(320.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tv,
                            contentDescription = null,
                            tint = TvNeonGreen,
                            modifier = Modifier.size(64.dp)
                        )
                        Text(
                            text = "Cast Pro TV Receiver",
                            color = Color.White,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Offline ultra-low latency display",
                            color = Color.Gray,
                            fontSize = 14.sp
                        )
                        if (state.qrCodeBitmap != null) {
                            Image(
                                bitmap = state.qrCodeBitmap.asImageBitmap(),
                                contentDescription = "Scan with the Cast Pro phone app",
                                modifier = Modifier
                                    .size(210.dp)
                                    .background(Color.White, RoundedCornerShape(12.dp))
                                    .padding(8.dp)
                            )
                            Text(
                                text = "Scan with the Cast Pro phone app",
                                color = Color.LightGray,
                                fontSize = 13.sp
                            )
                        } else {
                            Text(
                                text = "Connect the TV to Wi-Fi or Ethernet to show a pairing code",
                                color = Color(0xFFFFB300),
                                fontSize = 12.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }

                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.width(330.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                color = TvNeonGreen,
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.5.dp
                            )
                            Text(
                                text = if (state.isServerRunning) "Listening for phone" else "Starting receiver…",
                                color = TvNeonGreen,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Text(
                            text = "TV address: ${state.localIpAddress}:${Constants.DEFAULT_STREAM_PORT}",
                            color = TvNeonBlue,
                            fontSize = 16.sp
                        )
                        Text(
                            text = "Phone and TV must be on the same Wi-Fi or phone hotspot.",
                            color = Color.Gray,
                            fontSize = 12.sp
                        )

                        state.connectionError?.let { error ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = error,
                                color = Color(0xFFFF8A80),
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        } else if (state.showHudOverlay) {
            TvGamerHudOverlay(
                metrics = state.metrics,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(24.dp)
            )
        }
    }
}
