package com.castpro.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
fun TvFocusableButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    focusRequester: FocusRequester? = null,
    content: @Composable RowScope.(isFocused: Boolean) -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val bgColor = when {
        isFocused -> Color(0xFF00E5FF)
        isSelected -> Color(0xFF1E3A5F)
        else -> Color(0xCC1A1A1A)
    }
    val borderColor = if (isFocused) Color.White else Color(0x66FFFFFF)
    val borderWidth = if (isFocused) 3.dp else 1.dp
    var buttonModifier = modifier
        .scale(if (isFocused) 1.08f else 1f)
        .clip(RoundedCornerShape(8.dp))
        .background(bgColor)
        .border(borderWidth, borderColor, RoundedCornerShape(8.dp))
    if (focusRequester != null) buttonModifier = buttonModifier.focusRequester(focusRequester)
    Row(
        modifier = buttonModifier
            .focusable(interactionSource = interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) { content(isFocused) }
}

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
    val firstControlFocus = remember { FocusRequester() }
    LaunchedEffect(settingsVisible, compact) {
        if (!settingsVisible) runCatching { firstControlFocus.requestFocus() }
    }
    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = if (compact) 0.78f else 0.65f), RoundedCornerShape(10.dp))
            .border(1.dp, TvNeonGreen.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
            .padding(horizontal = if (compact) 10.dp else 16.dp, vertical = if (compact) 5.dp else 8.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp)
    ) {
        if (compact) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${metrics.currentFps} FPS", color = TvNeonGreen, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text(if (metrics.latencyMs >= 0f && metrics.latencyMs.isFinite()) "${String.format("%.1f", metrics.latencyMs)} ms" else "—",
                    color = if (metrics.latencyMs in 0f..40f) TvNeonGreen else Color.LightGray, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                TvFocusableButton(onClick = onCycleDisplayMode, isSelected = true, focusRequester = firstControlFocus) { focused ->
                    Text(displayMode.label, color = if (focused) Color.Black else Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                }
                TvFocusableButton(onClick = onToggleCompactHud) { focused ->
                    Text("FULL HUD", color = if (focused) Color.Black else Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                TvFocusableButton(onClick = onToggleSettings) { focused ->
                    Text(if (settingsVisible) "✕" else "⚙", color = if (focused) Color.Black else Color.White, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("FPS", style = MaterialTheme.typography.labelSmall, color = Color.LightGray, fontSize = 11.sp)
                    Text("${metrics.currentFps}", color = TvNeonGreen, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("LATENCY", style = MaterialTheme.typography.labelSmall, color = Color.LightGray, fontSize = 11.sp)
                    Text(if (metrics.latencyMs >= 0f && metrics.latencyMs.isFinite()) "${String.format("%.1f", metrics.latencyMs)} ms" else "—",
                        color = if (metrics.latencyMs in 0f..40f) TvNeonGreen else Color.LightGray, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("BITRATE", style = MaterialTheme.typography.labelSmall, color = Color.LightGray, fontSize = 11.sp)
                    Text("${String.format("%.1f", metrics.bitrateMbps)} Mb/s", color = TvNeonBlue, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("QUALITY", style = MaterialTheme.typography.labelSmall, color = Color.LightGray, fontSize = 11.sp)
                    Text(metrics.resolutionLabel.ifBlank { "—" }, color = TvNeonBlue, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(metrics.codecLabel.ifBlank { "Video" }, color = Color.LightGray, fontSize = 10.sp)
                }
                if (metrics.isGamingModeActive) {
                    Box(Modifier.background(TvNeonGreen.copy(alpha = 0.2f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                        Text("GAME MODE", color = TvNeonGreen, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TvFocusableButton(onClick = onCycleDisplayMode, isSelected = true, focusRequester = firstControlFocus) { focused ->
                    Text("DISPLAY: ${displayMode.label}", color = if (focused) Color.Black else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                TvFocusableButton(onClick = onCycleColorFilter) { focused ->
                    Text("COLOR: ${colorFilter.label}", color = if (focused) Color.Black else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                if (metrics.targetBitrateMbps > 0f) Text("TARGET ${String.format("%.0f", metrics.targetBitrateMbps)}M", color = Color.LightGray, fontSize = 10.sp)
                TvFocusableButton(onClick = onToggleCompactHud) { focused ->
                    Text("MINI HUD", color = if (focused) Color.Black else Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                TvFocusableButton(onClick = onToggleSettings) { focused ->
                    Text(if (settingsVisible) "SETTINGS ✕" else "SETTINGS ⚙", color = if (focused) Color.Black else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        error?.takeIf { it.isNotBlank() }?.let {
            Text(it, color = Color(0xFFFF8A80), fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
    }
}
