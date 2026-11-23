package com.castpro.phone.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

@Composable
fun PhoneDashboardScreen(
    state: PhoneUiState,
    onToggleGamingMode: (Boolean) -> Unit,
    onToggleWebServer: (Boolean) -> Unit,
    onSelectReceiver: (DiscoveredReceiver) -> Unit,
    onManualIpChanged: (String) -> Unit,
    onRefreshDiscovery: () -> Unit,
    onStartCastClicked: () -> Unit,
    onStopCastClicked: () -> Unit
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
        // Active streaming HUD
        if (state.status == StreamStatus.STREAMING) {
            LatencyHud(metrics = state.metrics)
        }

        // 1. Universal Web Browser Casting Card
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
                        Icon(imageVector = Icons.Default.Language, contentDescription = null, tint = GamerAccent)
                        Column {
                            Text("Universal Web Receiver", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text("Zero-install cast for PC, Mac, iPad & TV", color = GamerTextSecondary, fontSize = 11.sp)
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
                            Text("OPEN BROWSER AT:", color = GamerTextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Text(state.webServerUrl, color = GamerAccent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Text("Any device on local Wi-Fi can view live stream", color = Color.Gray, fontSize = 10.sp)
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

        // 2. Local TV Receivers Section
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
                    Text("Searching for Cast Pro TV receiver...", color = GamerTextSecondary, fontSize = 13.sp)
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

        // 3. Direct TV IP Manual Connect
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
                    placeholder = { Text("e.g. 192.168.1.50 (shown on TV)", color = Color.Gray, fontSize = 12.sp) },
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

        // 4. Start / Stop Stream Action Button
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
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.SportsEsports, contentDescription = null, tint = Color.Black)
                    Text(
                        if (state.manualIp.isNotBlank()) "Start Stream to TV & Web" else "Start Web Cast Only",
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
