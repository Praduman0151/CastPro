package com.castpro.phone.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.castpro.phone.ui.theme.GamerAccent
import com.castpro.phone.ui.theme.GamerCardBg
import com.castpro.phone.ui.theme.GamerNeonGreen
import com.castpro.phone.ui.theme.GamerTextSecondary
import com.castpro.phone.viewmodel.PhoneUiState
import com.castpro.phone.viewmodel.StreamStatus

/** Neon overview; unavailable metrics are shown as dashes, never invented. */
@Composable
fun CastProNeonOverview(state: PhoneUiState, modifier: Modifier = Modifier) {
    val connected = state.isReceiverConnected
    val streaming = state.status == StreamStatus.STREAMING
    val statusColor = when {
        state.status == StreamStatus.ERROR -> Color(0xFFFF526B)
        connected -> GamerNeonGreen
        state.status == StreamStatus.CONNECTING -> GamerAccent
        else -> GamerTextSecondary
    }
    val receiverName = state.selectedReceiver?.deviceName ?: "No TV selected"

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("CASTPRO", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
                Text("CAST BEYOND LIMITS  ·  CINEMATIC EDITION", color = GamerTextSecondary, fontSize = 9.sp, letterSpacing = 1.3.sp)
            }
            Row(
                modifier = Modifier
                    .background(statusColor.copy(alpha = 0.12f), RoundedCornerShape(30.dp))
                    .border(1.dp, statusColor.copy(alpha = 0.65f), RoundedCornerShape(30.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Text("●", color = statusColor, fontSize = 10.sp)
                Text(
                    text = when {
                        streaming -> "CASTING"
                        state.status == StreamStatus.CONNECTING -> "CONNECTING"
                        connected -> "READY"
                        state.status == StreamStatus.ERROR -> "ERROR"
                        else -> "OFFLINE"
                    },
                    color = statusColor, fontSize = 11.sp, fontWeight = FontWeight.Bold
                )
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = GamerCardBg),
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth().border(
                1.dp,
                Brush.linearGradient(listOf(Color(0xFFFF355D), Color(0xFF7A203D), Color(0xFF402033))),
                RoundedCornerShape(22.dp)
            )
        ) {
            Column(
                modifier = Modifier
                    .background(Brush.linearGradient(listOf(Color(0xFF351321), Color(0xFF171018), Color(0xFF0D0B12))))
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Tv, contentDescription = null, tint = GamerAccent)
                    Text("  ${if (connected) "CONNECTED DISPLAY" else "CAST YOUR SCREEN"}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                Text(receiverName, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = state.selectedReceiver?.let { "${it.hostAddress}:${it.port}" }
                        ?: "Open CastPro TV on the same network to pair.",
                    color = GamerTextSecondary, fontSize = 12.sp
                )
                Row(
                    modifier = Modifier.fillMaxWidth().background(Color(0x44000000), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (streaming) "STREAM ACTIVE" else state.connectionStatus.uppercase(), color = statusColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Icon(Icons.Default.Cast, contentDescription = null, tint = statusColor)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            MetricTile("FPS", if (streaming) state.metrics.currentFps.toString() else "—", GamerNeonGreen, Modifier.weight(1f))
            MetricTile("BITRATE", if (streaming && state.metrics.bitrateMbps > 0f) String.format("%.1fM", state.metrics.bitrateMbps) else "—", Color(0xFF9B8CFF), Modifier.weight(1f))
            MetricTile("LATENCY", if (streaming && state.metrics.latencyMs > 0f) String.format("%.0f ms", state.metrics.latencyMs) else "—", Color(0xFFFFB74D), Modifier.weight(1f))
            MetricTile("VIDEO", if (streaming) state.metrics.resolutionLabel.ifBlank { state.settings.resolution.displayName } else "—", GamerAccent, Modifier.weight(1f))
        }
    }
}

@Composable
private fun MetricTile(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.background(Color(0xFF21131B), RoundedCornerShape(14.dp))
            .border(1.dp, accent.copy(alpha = 0.28f), RoundedCornerShape(14.dp))
            .padding(horizontal = 8.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text(label, color = GamerTextSecondary, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        Text(value, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}
