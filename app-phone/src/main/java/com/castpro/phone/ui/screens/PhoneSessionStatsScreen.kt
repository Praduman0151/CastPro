package com.castpro.phone.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.castpro.core.common.model.CastingSessionStats
import com.castpro.phone.ui.theme.*

@Composable
fun PhoneSessionStatsScreen(
    stats: CastingSessionStats,
    isStreaming: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GamerDarkBg)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Live Stream Telemetry",
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )

        // Session Status Card
        Card(
            colors = CardDefaults.cardColors(containerColor = GamerCardBg),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, if (isStreaming) GamerNeonGreen.copy(alpha = 0.5f) else GamerCardBorder, RoundedCornerShape(16.dp))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = if (isStreaming) "STREAMING ACTIVE" else "STANDBY",
                        color = if (isStreaming) GamerNeonGreen else GamerTextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = stats.durationFormatted,
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }

                Icon(
                    imageVector = if (isStreaming) Icons.Default.Sensors else Icons.Default.SensorsOff,
                    contentDescription = null,
                    tint = if (isStreaming) GamerNeonGreen else Color.Gray,
                    modifier = Modifier.size(48.dp)
                )
            }
        }

        // Metrics Grid
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            TelemetryMetricCard(
                title = "LATENCY",
                value = "${stats.currentLatencyMs.toInt()} ms",
                icon = Icons.Default.Timer,
                color = if (stats.currentLatencyMs < 35f) GamerNeonGreen else Color(0xFFFFB300),
                modifier = Modifier.weight(1f)
            )
            TelemetryMetricCard(
                title = "FPS",
                value = "${stats.currentFps}",
                icon = Icons.Default.Speed,
                color = GamerAccent,
                modifier = Modifier.weight(1f)
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            TelemetryMetricCard(
                title = "BITRATE",
                value = String.format("%.1f Mbps", stats.currentBitrateMbps),
                icon = Icons.Default.NetworkCheck,
                color = Color(0xFFD500F9),
                modifier = Modifier.weight(1f)
            )
            TelemetryMetricCard(
                title = "DROPPED",
                value = "${stats.totalFramesDropped}",
                icon = Icons.Default.LayersClear,
                color = if (stats.totalFramesDropped == 0L) GamerNeonGreen else Color(0xFFFF1744),
                modifier = Modifier.weight(1f)
            )
        }

        // Audio & Hardware info
        Card(
            colors = CardDefaults.cardColors(containerColor = GamerCardBg),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, GamerCardBorder, RoundedCornerShape(16.dp))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Pipeline Details", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text("• Encoding: Hardware MediaCodec CBR Low-Latency", color = GamerTextSecondary, fontSize = 12.sp)
                Text("• Audio: 48kHz Stereo PCM Zero-Buffer Capture", color = GamerTextSecondary, fontSize = 12.sp)
                Text("• Protocol: Binary TCP_NODELAY + WebSocket Multiplex", color = GamerTextSecondary, fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun TelemetryMetricCard(
    title: String,
    value: String,
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = GamerCardBg),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = title, color = GamerTextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
            }
            Text(text = value, color = color, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
    }
}
