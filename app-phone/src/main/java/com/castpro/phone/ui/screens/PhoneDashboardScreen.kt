package com.castpro.phone.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.castpro.core.common.model.DiscoveredReceiver
import com.castpro.phone.ui.components.GamerModeCard
import com.castpro.phone.ui.components.LatencyHud
import com.castpro.phone.ui.theme.GamerAccent
import com.castpro.phone.ui.theme.GamerNeonGreen
import com.castpro.phone.ui.theme.GamerSurface
import com.castpro.phone.ui.theme.GamerTextSecondary
import com.castpro.phone.viewmodel.PhoneUiState
import com.castpro.phone.viewmodel.StreamStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneDashboardScreen(
    state: PhoneUiState,
    onToggleGamingMode: (Boolean) -> Unit,
    onSelectReceiver: (DiscoveredReceiver) -> Unit,
    onManualIpChanged: (String) -> Unit,
    onRefreshDiscovery: () -> Unit,
    onStartCastClicked: () -> Unit,
    onStopCastClicked: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cast Pro Gamer Suite") },
                actions = {
                    IconButton(onClick = onRefreshDiscovery) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh Discovery", tint = GamerAccent)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = GamerNeonGreen
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Gaming Mode Toggle
            GamerModeCard(
                isGamingMode = state.isGamingMode,
                onToggle = onToggleGamingMode
            )

            if (state.status == StreamStatus.STREAMING) {
                // Active streaming HUD
                LatencyHud(metrics = state.metrics)
            }

            // TV Discovery Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Local TV Receivers (Offline NSD)",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "${state.discoveredReceivers.size} found",
                    style = MaterialTheme.typography.bodySmall,
                    color = GamerTextSecondary
                )
            }

            // Receivers list
            if (state.discoveredReceivers.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = GamerSurface)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.Tv, contentDescription = null, tint = GamerTextSecondary, modifier = Modifier.size(40.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Searching for Cast Pro TV on local network...", color = GamerTextSecondary)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.discoveredReceivers) { receiver ->
                        val isSelected = state.selectedReceiver?.hostAddress == receiver.hostAddress
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectReceiver(receiver) },
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) GamerNeonGreen.copy(alpha = 0.15f) else GamerSurface
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
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
                                        Text(receiver.deviceName, style = MaterialTheme.typography.bodyLarge)
                                        Text("${receiver.hostAddress}:${receiver.port}", style = MaterialTheme.typography.bodySmall, color = GamerTextSecondary)
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

            // Manual IP Fallback
            OutlinedTextField(
                value = state.manualIp,
                onValueChange = onManualIpChanged,
                label = { Text("TV IP Address (or Hotspot Gateway)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = GamerNeonGreen,
                    unfocusedBorderColor = GamerTextSecondary.copy(alpha = 0.5f)
                )
            )

            if (state.errorMessage != null) {
                Text(
                    text = state.errorMessage,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // Action Button
            if (state.status == StreamStatus.STREAMING) {
                Button(
                    onClick = onStopCastClicked,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Disconnect & Stop Casting", style = MaterialTheme.typography.titleMedium)
                }
            } else {
                Button(
                    onClick = onStartCastClicked,
                    enabled = state.manualIp.isNotBlank() && state.status != StreamStatus.CONNECTING,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GamerNeonGreen,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(Icons.Default.Cast, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        if (state.status == StreamStatus.CONNECTING) "Connecting..." else "Start Low-Latency Cast",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }
    }
}
