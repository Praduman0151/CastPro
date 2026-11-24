package com.castpro.phone.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.castpro.core.common.model.DiscoveredReceiver
import com.castpro.phone.ui.components.LatencyHud
import com.castpro.phone.ui.theme.*
import com.castpro.phone.viewmodel.PhoneUiState
import com.castpro.phone.viewmodel.StreamStatus

private fun gamerStatusColor(state: PhoneUiState): Color = when {
    state.status == StreamStatus.STREAMING && state.isReceiverConnected -> GamerNeonGreen
    state.status == StreamStatus.ERROR -> Color(0xFFFF1744)
    state.status == StreamStatus.CONNECTING || state.status == StreamStatus.STREAMING -> GamerAccent
    state.status == StreamStatus.IDLE && state.connectionStatus == "Connected" -> GamerNeonGreen
    else -> GamerTextSecondary
}

@Composable
fun PhoneDashboardScreen(
    state: PhoneUiState,
    onToggleGamingMode: (Boolean) -> Unit,
    onToggleWebServer: (Boolean) -> Unit,
    onToggleAutoConnect: (Boolean) -> Unit,
    onSelectReceiver: (DiscoveredReceiver) -> Unit,
    onManualIpChanged: (String) -> Unit,
    onRefreshDiscovery: () -> Unit,
    onScanQrClicked: () -> Unit,
    onStartCastClicked: () -> Unit,
    onStopCastClicked: () -> Unit,
    onDismissError: () -> Unit = {}
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GamerDarkBg)
            .padding(16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (state.status == StreamStatus.STREAMING) {
            LatencyHud(metrics = state.metrics)
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = GamerCardBg),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, gamerStatusColor(state), RoundedCornerShape(16.dp))
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("STATUS", color = GamerTextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = state.connectionStatus,
                    color = gamerStatusColor(state),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        if (state.status == StreamStatus.STREAMING) {
            val settings = state.settings
            Text(
                text = "Quality: ${settings.resolution.width}x${settings.resolution.height} · " +
                    "${settings.framerate.fps} fps · ${settings.codec.name} · " +
                    "${String.format("%.1f", settings.bitrate.bps / 1_000_000f)} Mb/s",
                color = GamerAccent,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }

        state.errorMessage?.let { message ->
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF3A1520)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFFFF5252), RoundedCornerShape(12.dp))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFF8A80))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = message,
                        color = Color(0xFFFFCDD2),
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismissError, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = Color(0xFFFFCDD2))
                    }
                }
            }
        }

        // 1. TV connection / automatic pairing
        Card(
            colors = CardDefaults.cardColors(containerColor = GamerCardBg),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    1.dp,
                    if (state.isReceiverConnected) GamerNeonGreen.copy(alpha = 0.6f) else GamerAccent.copy(alpha = 0.4f),
                    RoundedCornerShape(16.dp)
                )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Default.Tv, contentDescription = null, tint = GamerAccent)
                        Column {
                            Text("TV Receiver", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text(
                                text = state.connectionStatus,
                                color = if (state.isReceiverConnected) GamerNeonGreen else GamerTextSecondary,
                                fontSize = 11.sp
                            )
                        }
                    }
                    Switch(
                        checked = state.settings.autoConnect,
                        onCheckedChange = onToggleAutoConnect,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.Black,
                            checkedTrackColor = GamerNeonGreen,
                            uncheckedThumbColor = Color.Gray,
                            uncheckedTrackColor = Color(0xFF21262D)
                        )
                    )
                }

                Text(
                    text = if (state.settings.autoConnect) {
                        "Auto-connect is on: the first TV found is selected for you."
                    } else {
                        "Auto-connect is off: tap a receiver below or scan its QR."
                    },
                    color = GamerTextSecondary,
                    fontSize = 10.sp
                )

                val selected = state.selectedReceiver
                if (selected != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF0D1117), RoundedCornerShape(10.dp))
                            .border(1.dp, Color(0xFF30363D), RoundedCornerShape(10.dp))
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(selected.deviceName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(
                                "${selected.hostAddress}:${selected.port}",
                                color = GamerAccent,
                                fontSize = 12.sp
                            )
                        }
                        Icon(Icons.Default.CastConnected, contentDescription = null, tint = GamerNeonGreen)
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF0D1117), RoundedCornerShape(10.dp))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (state.isScanning) {
                            CircularProgressIndicator(
                                color = GamerAccent,
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                        }
                        Text(
                            text = if (state.isScanning) "Looking for Cast Pro TV on your Wi-Fi…" else "No TV selected",
                            color = GamerTextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onScanQrClicked,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null, tint = GamerAccent, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Scan TV QR", color = Color.White, fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = onRefreshDiscovery,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, tint = GamerAccent, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Refresh", color = Color.White, fontSize = 12.sp)
                    }
                }
            }
        }

        // 2. Universal web receiver
        Card(
            colors = CardDefaults.cardColors(containerColor = GamerCardBg),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, GamerAccent.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Default.Language, contentDescription = null, tint = GamerAccent)
                        Column {
                            Text("Universal Web Receiver", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text("Browser preview for PC, Mac, iPad & TV", color = GamerTextSecondary, fontSize = 11.sp)
                        }
                    }
                    Switch(
                        checked = state.settings.enableWebServer,
                        onCheckedChange = onToggleWebServer,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.Black,
                            checkedTrackColor = GamerAccent,
                            uncheckedThumbColor = Color.Gray,
                            uncheckedTrackColor = Color(0xFF21262D)
                        )
                    )
                }

                if (state.settings.enableWebServer) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF0D1117), RoundedCornerShape(10.dp))
                            .border(1.dp, Color(0xFF30363D), RoundedCornerShape(10.dp))
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text("OPEN IN A BROWSER:", color = GamerTextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Text(
                                state.webServerUrl.ifBlank { "Detecting network…" },
                                color = GamerAccent,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "Starts when you tap Start Stream. No app needed.",
                                color = Color.Gray,
                                fontSize = 10.sp
                            )
                        }

                        if (state.webQrBitmap != null) {
                            Image(
                                bitmap = state.webQrBitmap.asImageBitmap(),
                                contentDescription = "Web Receiver QR Code",
                                modifier = Modifier
                                    .size(72.dp)
                                    .background(Color.White, RoundedCornerShape(6.dp))
                                    .padding(4.dp)
                            )
                        }
                    }
                }
            }
        }

        // 3. Discovered receivers
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Discovered TV Receivers",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            IconButton(onClick = onRefreshDiscovery) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = GamerAccent)
            }
        }

        if (state.discoveredReceivers.isEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = GamerCardBg),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, GamerCardBorder, RoundedCornerShape(12.dp))
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.Tv, contentDescription = null, tint = GamerTextSecondary, modifier = Modifier.size(36.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "No receiver yet. Open Cast Pro TV on the same Wi-Fi.",
                        color = GamerTextSecondary,
                        fontSize = 13.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.discoveredReceivers.forEach { receiver ->
                    val isSelected = state.selectedReceiver?.hostAddress == receiver.hostAddress
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectReceiver(receiver) }
                            .border(
                                1.dp,
                                if (isSelected) GamerNeonGreen else GamerCardBorder,
                                RoundedCornerShape(12.dp)
                            ),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) GamerNeonGreen.copy(alpha = 0.15f) else GamerCardBg
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    Icons.Default.Tv,
                                    contentDescription = null,
                                    tint = if (isSelected) GamerNeonGreen else GamerTextSecondary
                                )
                                Column {
                                    Text(receiver.deviceName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text("${receiver.hostAddress}:${receiver.port}", color = GamerTextSecondary, fontSize = 12.sp)
                                }
                            }
                            if (isSelected) {
                                Icon(Icons.Default.CastConnected, contentDescription = "Selected", tint = GamerNeonGreen)
                            }
                        }
                    }
                }
            }
        }

        // 4. Manual address
        Card(
            colors = CardDefaults.cardColors(containerColor = GamerCardBg),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, GamerCardBorder, RoundedCornerShape(14.dp))
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Direct IP Connect", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = state.manualIp,
                    onValueChange = onManualIpChanged,
                    placeholder = { Text("e.g. 192.168.1.50:8998 (shown on TV)", color = Color.Gray, fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = GamerAccent,
                        unfocusedBorderColor = GamerCardBorder,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )
            }
        }

        // 5. Start / stop
        if (state.status == StreamStatus.STREAMING) {
            Button(
                onClick = onStopCastClicked,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF1744)),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null, tint = Color.White)
                    Text("Stop Gaming Cast", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
        } else {
            Button(
                onClick = onStartCastClicked,
                colors = ButtonDefaults.buttonColors(containerColor = GamerNeonGreen),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(12.dp),
                enabled = state.selectedReceiver != null || state.manualIp.isNotBlank() || state.settings.enableWebServer
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.SportsEsports, contentDescription = null, tint = Color.Black)
                    Text(
                        if (state.selectedReceiver != null || state.manualIp.isNotBlank()) {
                            "Start Stream to TV"
                        } else {
                            "Start Web Cast Only"
                        },
                        color = Color.Black,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
    }
}
