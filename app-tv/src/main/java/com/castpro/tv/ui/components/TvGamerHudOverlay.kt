package com.castpro.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.castpro.core.common.model.LatencyMetrics
import com.castpro.tv.viewmodel.TvColorFilter
import com.castpro.tv.viewmodel.TvDisplayMode

val TvNeonGreen = Color(0xFF00FF66)
val TvNeonBlue = Color(0xFF00D1FF)
val TvWarning = Color(0xFFFF3366)

@Composable
fun TvGamerHudOverlay(
    metrics: LatencyMetrics,
    displayMode: TvDisplayMode,
    colorFilter: TvColorFilter,
    onCycleDisplayMode: () -> Unit = {},
    onCycleColorFilter: () -> Unit = {},
    onToggleSettings: () -> Unit = {},
    onToggleCompactHud: () -> Unit = {},
    compact: Boolean = false,
    settingsVisible: Boolean = false,
    error: String? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = if (compact) 0.78f else 0.65f), RoundedCornerShape(10.dp))
            .border(1.dp, TvNeonGreen.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
            .padding(
                horizontal = if (compact) 10.dp else 16.dp,
                vertical = if (compact) 5.dp else 8.dp
            ),
        verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp)
    ) {
        if (compact) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${metrics.currentFps} FPS",
                    color = TvNeonGreen,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
                Text(
                    text = "${String.format("%.1f", metrics.latencyMs)} ms",
                    color = if (metrics.latencyMs < 40) TvNeonGreen else TvWarning,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
                Text(
                    text = "● ${displayMode.label}",
                    color = TvNeonGreen,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    modifier = Modifier.clickable { onCycleDisplayMode() }
                )
                Text(
                    text = "FULL HUD",
                    color = TvNeonBlue,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { onToggleCompactHud() }
                )
                Text(
                    text = if (settingsVisible) "✕" else "⚙",
                    color = TvNeonGreen,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.clickable { onToggleSettings() }
                )
            }
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "FPS", style = MaterialTheme.typography.labelSmall, color = Color.LightGray, fontSize = 11.sp)
                    Text(text = "${metrics.currentFps}", color = TvNeonGreen, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "LATENCY", style = MaterialTheme.typography.labelSmall, color = Color.LightGray, fontSize = 11.sp)
                    Text(
                        text = "${String.format("%.1f", metrics.latencyMs)} ms",
                        color = if (metrics.latencyMs < 40) TvNeonGreen else TvWarning,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "BITRATE", style = MaterialTheme.typography.labelSmall, color = Color.LightGray, fontSize = 11.sp)
                    Text(text = "${String.format("%.1f", metrics.bitrateMbps)} Mb/s", color = TvNeonBlue, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "QUALITY", style = MaterialTheme.typography.labelSmall, color = Color.LightGray, fontSize = 11.sp)
                    Text(
                        text = metrics.resolutionLabel.ifBlank { "—" },
                        color = TvNeonBlue,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    Text(
                        text = metrics.codecLabel.ifBlank { "Video" },
                        color = Color.LightGray,
                        fontSize = 10.sp
                    )
                }
                if (metrics.isGamingModeActive) {
                    Box(
                        modifier = Modifier
                            .background(TvNeonGreen.copy(alpha = 0.2f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(text = "GAME MODE", color = TvNeonGreen, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                    }
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .background(TvNeonGreen.copy(alpha = 0.18f), RoundedCornerShape(6.dp))
                        .border(1.dp, TvNeonGreen.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                        .clickable { onCycleDisplayMode() }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "● DISPLAY: ${displayMode.label}",
                        color = TvNeonGreen,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Box(
                    modifier = Modifier
                        .background(TvNeonBlue.copy(alpha = 0.18f), RoundedCornerShape(6.dp))
                        .border(1.dp, TvNeonBlue.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                        .clickable { onCycleColorFilter() }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "● COLOR: ${colorFilter.label}",
                        color = TvNeonBlue,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                if (metrics.targetBitrateMbps > 0f) {
                    Text(
                        text = "TARGET ${String.format("%.0f", metrics.targetBitrateMbps)}M",
                        color = Color.LightGray,
                        fontSize = 10.sp
                    )
                }
                Text(
                    text = "MINI HUD",
                    color = TvNeonBlue,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { onToggleCompactHud() }
                )
                Text(
                    text = if (settingsVisible) "SETTINGS ✕" else "SETTINGS ⚙",
                    color = TvNeonGreen,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { onToggleSettings() }
                )
            }
        }
        error?.takeIf { it.isNotBlank() }?.let { message ->
            Text(
                text = message,
                color = Color(0xFFFF8A80),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
