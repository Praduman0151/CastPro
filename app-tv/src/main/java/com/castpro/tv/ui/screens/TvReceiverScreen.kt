package com.castpro.tv.ui.screens

import android.view.SurfaceView
import android.view.ViewGroup
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.castpro.core.common.protocol.Constants
import com.castpro.tv.ui.components.TvGamerHudOverlay
import com.castpro.tv.ui.components.TvNeonBlue
import com.castpro.tv.ui.components.TvNeonGreen
import com.castpro.tv.ui.components.TvWarning
import com.castpro.tv.viewmodel.TvColorFilter
import com.castpro.tv.viewmodel.TvDisplayMode
import com.castpro.tv.viewmodel.TvUiState

@Composable
fun TvReceiverScreen(
    state: TvUiState,
    onSurfaceCreated: (SurfaceView) -> Unit,
    onDismissCrash: () -> Unit = {},
    onDisplayModeChanged: (TvDisplayMode) -> Unit = {},
    onColorFilterChanged: (TvColorFilter) -> Unit = {},
    onCycleDisplayMode: () -> Unit = {},
    onCycleColorFilter: () -> Unit = {},
    onToggleSettings: () -> Unit = {},
    onToggleCompactHud: () -> Unit = {},
    onResetServer: () -> Unit = {}
) {
    val tvAspect = 16f / 9f
    // The encoder always emits streamConfig.width x streamConfig.height. Use
    // that stable value for layout; phone sensor geometry is portrait and made
    // the whole cast jump sideways whenever quality was reconfigured.
    val streamAspect = if (state.streamConfig.height > 0) {
        (state.streamConfig.width.toFloat() / state.streamConfig.height).coerceIn(0.4f, 3f)
    } else {
        16f / 9f
    }

    // Keep the decoder surface inside the panel for Fit, but let Full/Stretch/
    // Zoom use the whole window. Never derive this from phone sensor geometry.
    val surfaceModifier = when (state.displayMode) {
        TvDisplayMode.FIT -> Modifier
            .aspectRatio(streamAspect)
            .fillMaxSize()
        TvDisplayMode.FULL -> Modifier.fillMaxSize()
        TvDisplayMode.STRETCH -> {
            val scaleX = if (streamAspect < tvAspect) tvAspect / streamAspect else 1f
            val scaleY = if (streamAspect > tvAspect) streamAspect / tvAspect else 1f
            Modifier
                .fillMaxSize()
                .graphicsLayer(scaleX = scaleX, scaleY = scaleY, clip = true)
        }
        TvDisplayMode.ZOOM -> Modifier
            .fillMaxSize()
            .graphicsLayer(scaleX = 1.18f, scaleY = 1.18f, clip = true)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
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
            modifier = surfaceModifier
        )

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
            Box(
                modifier = Modifier
                    .background(Color(0xFF202830), RoundedCornerShape(8.dp))
                    .clickable { onClose() }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(text = "CLOSE", color = TvNeonGreen, fontSize = 13.sp, fontWeight = FontWeight.Bold)
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
            text = "Stream shape ${String.format("%.2f", streamAspect)}:1 · " +
                "Full crops the edges, Fit keeps black bars, Stretch forces 16:9.",
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
private fun TvChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    val accent = when {
        selected -> TvNeonGreen
        focused -> TvNeonBlue
        else -> Color(0xFF30363D)
    }
    Box(
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .then(
                if (selected) Modifier.shadow(
                    elevation = 8.dp,
                    ambientColor = TvNeonGreen.copy(alpha = 0.6f),
                    spotColor = TvNeonGreen.copy(alpha = 0.6f),
                    shape = shape
                ) else Modifier
            )
            .background(
                when {
                    selected -> TvNeonGreen.copy(alpha = 0.30f)
                    focused -> TvNeonBlue.copy(alpha = 0.18f)
                    else -> Color(0xFF161B22)
                },
                shape
            )
            .border(
                if (selected || focused) 2.dp else 1.dp,
                accent,
                shape
            )
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            text = if (selected) "● $label" else label,
            color = when {
                selected -> Color.White
                focused -> TvNeonBlue
                else -> Color.LightGray
            },
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Medium
        )
    }
}

@Composable
private fun TvResetButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .background(
                if (focused) TvWarning.copy(alpha = 0.30f) else Color(0xFF2A141B),
                shape
            )
            .border(
                if (focused) 2.dp else 1.dp,
                if (focused) TvNeonBlue else TvWarning.copy(alpha = 0.8f),
                shape
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(
            text = "RESET SERVER",
            color = if (focused) TvNeonBlue else Color(0xFFFF8A80),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
