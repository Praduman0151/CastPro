package com.castpro.tv.ui.screens

import android.view.TextureView
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout
import android.view.Gravity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.castpro.core.common.protocol.Constants
import com.castpro.tv.ui.components.TvGamerHudOverlay
import com.castpro.tv.ui.components.TvFocusableButton
import com.castpro.tv.ui.components.TvNeonBlue
import com.castpro.tv.ui.components.TvNeonGreen
import com.castpro.tv.ui.components.TvWarning
import com.castpro.tv.viewmodel.TvColorFilter
import com.castpro.tv.viewmodel.TvDisplayMode
import com.castpro.tv.viewmodel.TvUiState

@Composable
fun TvReceiverScreen(
    state: TvUiState,
    onSurfaceCreated: (TextureView) -> Unit,
    useSurfaceView: Boolean = false,
    onSurfaceViewCreated: (SurfaceView) -> Unit = {},
    onDismissCrash: () -> Unit = {},
    onDisplayModeChanged: (TvDisplayMode) -> Unit = {},
    onColorFilterChanged: (TvColorFilter) -> Unit = {},
    onCycleDisplayMode: () -> Unit = {},
    onCycleColorFilter: () -> Unit = {},
    onToggleSettings: () -> Unit = {},
    onToggleCompactHud: () -> Unit = {},
    onResetServer: () -> Unit = {}
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clipToBounds(),
        contentAlignment = Alignment.Center
    ) {
        val density = LocalDensity.current
        val containerW = constraints.maxWidth.toFloat()
        val containerH = constraints.maxHeight.toFloat()
        val videoWidth = state.videoWidth.takeIf { it > 0 } ?: state.streamConfig.width
        val videoHeight = state.videoHeight.takeIf { it > 0 } ?: state.streamConfig.height
        val videoAspect = if (videoWidth > 0 && videoHeight > 0) videoWidth.toFloat() / videoHeight.toFloat() else 16f / 9f
        val containerAspect = if (containerH > 0f) containerW / containerH else 16f / 9f
        val (targetW, targetH) = when (state.displayMode) {
            TvDisplayMode.FIT -> if (videoAspect > containerAspect) containerW to (containerW / videoAspect) else (containerH * videoAspect) to containerH
            TvDisplayMode.FILL -> containerW to containerH
            TvDisplayMode.STRETCH -> containerW to containerH
        }
        val videoModifier = Modifier.requiredSize(
            with(density) { targetW.toDp() },
            with(density) { targetH.toDp() }
        )
        if (useSurfaceView) {
            AndroidView(
                factory = { context ->
                    SurfaceView(context).apply {
                        val w = targetW.toInt().coerceAtLeast(1)
                        val h = targetH.toInt().coerceAtLeast(1)
                        layoutParams = FrameLayout.LayoutParams(w, h, Gravity.CENTER)
                        onSurfaceViewCreated(this)
                    }
                },
                update = { view ->
                    val w = targetW.toInt().coerceAtLeast(1)
                    val h = targetH.toInt().coerceAtLeast(1)
                    val lp = (view.layoutParams as? FrameLayout.LayoutParams)
                        ?: FrameLayout.LayoutParams(w, h, Gravity.CENTER)
                    lp.width = w
                    lp.height = h
                    lp.gravity = Gravity.CENTER
                    view.layoutParams = lp
                    view.requestLayout()
                },
                modifier = videoModifier
            )
        } else {
            AndroidView(
                factory = { context ->
                    TextureView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        onSurfaceCreated(this)
                    }
                },
                modifier = videoModifier
            )
        }

        // GPU-composited colour treatment. Placed above the video surface but
        // below the HUD so telemetry stays readable.
        if (state.isStreaming && state.colorFilter != TvColorFilter.STANDARD) {
            val filter = state.colorFilter
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(
                    color = filter.tint ?: Color.Transparent,
                    alpha = filter.alpha,
                    blendMode = filter.blendMode
                )
            }
        }

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
                            text = "CASTPRO TV",
                            color = Color.White,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "WIRELESS DISPLAY RECEIVER",
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

                        TvResetButton(
                            onClick = onResetServer,
                            modifier = Modifier.padding(top = 4.dp)
                        )

                        Text(
                            text = "DISPLAY MODE",
                            color = TvNeonBlue,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TvDisplayMode.values().forEach { mode ->
                                TvChip(
                                    label = mode.label,
                                    selected = state.displayMode == mode,
                                    onClick = { onDisplayModeChanged(mode) }
                                )
                            }
                        }

                        Text(
                            text = "COLOR FILTER",
                            color = TvNeonBlue,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TvColorFilter.values().take(5).forEach { filter ->
                                TvChip(
                                    label = filter.label,
                                    selected = state.colorFilter == filter,
                                    onClick = { onColorFilterChanged(filter) }
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TvColorFilter.values().drop(5).forEach { filter ->
                                TvChip(
                                    label = filter.label,
                                    selected = state.colorFilter == filter,
                                    onClick = { onColorFilterChanged(filter) }
                                )
                            }
                        }

                        state.connectionError?.let { error ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = error,
                                color = Color(0xFFFF8A80),
                                fontSize = 11.sp
                            )
                        }

                        state.lastCrash?.let { trace ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Last crash (tap to clear):",
                                color = Color(0xFFFFB300),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable { onDismissCrash() }
                            )
                            Text(
                                text = trace,
                                color = Color(0xFFFF8A80),
                                fontSize = 10.sp,
                                lineHeight = 13.sp,
                                modifier = Modifier.heightIn(max = 180.dp)
                            )
                        }
                    }
                }
            }
        } else {
            if (state.showHudOverlay) {
                TvGamerHudOverlay(
                    metrics = state.metrics,
                    displayMode = state.displayMode,
                    colorFilter = state.colorFilter,
                    onCycleDisplayMode = onCycleDisplayMode,
                    onCycleColorFilter = onCycleColorFilter,
                    onToggleSettings = onToggleSettings,
                    onToggleCompactHud = onToggleCompactHud,
                    compact = state.compactHud,
                    settingsVisible = state.showSettingsPanel,
                    error = state.connectionError,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(24.dp)
                )
            }
            if (state.showSettingsPanel) {
                TvCastSettingsPanel(
                    state = state,
                    onDisplayModeChanged = onDisplayModeChanged,
                    onColorFilterChanged = onColorFilterChanged,
                    onToggleCompactHud = onToggleCompactHud,
                    onResetServer = onResetServer,
                    onClose = onToggleSettings,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
    }
}

@Composable
private fun TvCastSettingsPanel(
    state: TvUiState,
    onDisplayModeChanged: (TvDisplayMode) -> Unit,
    onColorFilterChanged: (TvColorFilter) -> Unit,
    onToggleCompactHud: () -> Unit,
    onResetServer: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val firstControlFocus = remember { FocusRequester() }
    val settingsAspect = if (state.streamConfig.height > 0) {
        state.streamConfig.width.toFloat() / state.streamConfig.height
    } else {
        16f / 9f
    }
    LaunchedEffect(Unit) {
        runCatching { firstControlFocus.requestFocus() }
    }
    Column(
        modifier = modifier
            .background(Color(0xE60B1018), RoundedCornerShape(18.dp))
            .border(2.dp, TvNeonGreen.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
            .padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "CAST SETTINGS",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            TvFocusableButton(onClick = onClose) { focused ->
                Text("CLOSE", color = if (focused) Color.Black else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }

        Text(
            text = "DISPLAY MODE  ·  ${state.displayMode.label}: ${state.displayMode.description}",
            color = TvNeonBlue,
            fontSize = 13.sp
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TvDisplayMode.values().forEachIndexed { index, mode ->
                TvChip(
                    label = mode.label,
                    selected = state.displayMode == mode,
                    onClick = { onDisplayModeChanged(mode) },
                    modifier = if (index == 0) Modifier.focusRequester(firstControlFocus) else Modifier
                )
            }
        }

        Text(text = "COLOR FILTER", color = TvNeonBlue, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TvColorFilter.values().take(5).forEach { filter ->
                TvChip(
                    label = filter.label,
                    selected = state.colorFilter == filter,
                    onClick = { onColorFilterChanged(filter) }
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TvColorFilter.values().drop(5).forEach { filter ->
                TvChip(
                    label = filter.label,
                    selected = state.colorFilter == filter,
                    onClick = { onColorFilterChanged(filter) }
                )
            }
        }

        Text(
            text = "STREAM QUALITY",
            color = TvNeonBlue,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = buildString {
                append(state.metrics.resolutionLabel.ifBlank { "—" })
                append("  ·  ")
                append(state.metrics.codecLabel.ifBlank { "Video" })
                append("  ·  ")
                append("${state.metrics.currentFps} FPS")
                append("  ·  ")
                append("${String.format("%.1f", state.metrics.bitrateMbps)} Mb/s")
                if (state.metrics.targetBitrateMbps > 0f) {
                    append("  ·  target ${String.format("%.0f", state.metrics.targetBitrateMbps)}M")
                }
            },
            color = Color.LightGray,
            fontSize = 13.sp
        )
        Text(
            text = "Stream shape ${String.format("%.2f", settingsAspect)}:1 · " +
                "Fit preserves the whole frame; Full and Stretch fill the TV without cropping.",
            color = Color.Gray,
            fontSize = 11.sp
        )
        Text(text = "HUD MODE", color = TvNeonBlue, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TvChip(
                label = "Mini HUD",
                selected = state.compactHud,
                onClick = onToggleCompactHud
            )
            TvChip(
                label = "Full HUD",
                selected = !state.compactHud,
                onClick = onToggleCompactHud
            )
        }

        TvResetButton(
            onClick = onResetServer,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun TvChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TvFocusableButton(onClick = onClick, modifier = modifier, isSelected = selected) { focused ->
        Text(if (selected) "● $label" else label, color = if (focused) Color.Black else Color.White,
            fontSize = 11.sp, fontWeight = if (selected || focused) FontWeight.ExtraBold else FontWeight.Medium)
    }
}

@Composable
private fun TvResetButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    TvFocusableButton(onClick = onClick, modifier = modifier) { focused ->
        Text("RESET SERVER", color = if (focused) Color.Black else Color(0xFFFF8A80), fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}
