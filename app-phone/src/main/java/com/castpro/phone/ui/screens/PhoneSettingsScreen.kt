package com.castpro.phone.ui.screens

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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.castpro.core.common.model.*
import com.castpro.phone.ui.theme.*

@Composable
fun PhoneSettingsScreen(
    settings: StreamingSettings,
    onSettingsChanged: (StreamingSettings) -> Unit
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
        Text(
            text = "Gamer Stream Settings",
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )

        // 1. Resolution
        SettingsSectionCard(title = "Target Resolution", icon = Icons.Default.Tv) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Resolution.values().forEach { res ->
                    OptionChip(
                        label = res.displayName,
                        isSelected = settings.resolution == res,
                        modifier = Modifier.weight(1f),
                        onClick = { onSettingsChanged(settings.copy(resolution = res)) }
                    )
                }
            }
        }

        // 2. Framerate
        SettingsSectionCard(title = "Target Framerate", icon = Icons.Default.Speed) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Framerate.values().forEach { fps ->
                    OptionChip(
                        label = "${fps.fps} FPS",
                        isSelected = settings.framerate == fps,
                        modifier = Modifier.weight(1f),
                        onClick = { onSettingsChanged(settings.copy(framerate = fps)) }
                    )
                }
            }
        }

        // 3. Bitrate
        SettingsSectionCard(title = "Video Bitrate", icon = Icons.Default.NetworkCheck) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OptionChip(
                        label = BitratePreset.BALANCED_8M.displayName,
                        isSelected = settings.bitrate == BitratePreset.BALANCED_8M,
                        modifier = Modifier.weight(1f),
                        onClick = { onSettingsChanged(settings.copy(bitrate = BitratePreset.BALANCED_8M)) }
                    )
                    OptionChip(
                        label = BitratePreset.GAMING_15M.displayName,
                        isSelected = settings.bitrate == BitratePreset.GAMING_15M,
                        modifier = Modifier.weight(1f),
                        onClick = { onSettingsChanged(settings.copy(bitrate = BitratePreset.GAMING_15M)) }
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OptionChip(
                        label = BitratePreset.ULTRA_25M.displayName,
                        isSelected = settings.bitrate == BitratePreset.ULTRA_25M,
                        modifier = Modifier.weight(1f),
                        onClick = { onSettingsChanged(settings.copy(bitrate = BitratePreset.ULTRA_25M)) }
                    )
                    OptionChip(
                        label = BitratePreset.EXTREME_40M.displayName,
                        isSelected = settings.bitrate == BitratePreset.EXTREME_40M,
                        modifier = Modifier.weight(1f),
                        onClick = { onSettingsChanged(settings.copy(bitrate = BitratePreset.EXTREME_40M)) }
                    )
                }
            }
        }

        // 4. Video Codec
        SettingsSectionCard(title = "Hardware Video Codec", icon = Icons.Default.SettingsSuggest) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                VideoCodec.values().forEach { codec ->
                    OptionChip(
                        label = codec.name,
                        isSelected = settings.codec == codec,
                        modifier = Modifier.weight(1f),
                        onClick = { onSettingsChanged(settings.copy(codec = codec)) }
                    )
                }
            }
        }

        // 5. Audio mode
        SettingsSectionCard(title = "Audio Source", icon = Icons.Default.VolumeUp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AudioMode.values().forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSettingsChanged(settings.copy(audioMode = mode)) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = settings.audioMode == mode,
                            onClick = { onSettingsChanged(settings.copy(audioMode = mode)) },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = GamerNeonGreen,
                                unselectedColor = GamerTextSecondary
                            )
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(mode.displayName, color = Color.White, fontSize = 13.sp)
                            Text(
                                when (mode) {
                                    AudioMode.INTERNAL_GAME -> "Captures game/media audio (Android 10+)"
                                    AudioMode.MICROPHONE -> "Uses the phone microphone (needs permission)"
                                    AudioMode.MUTED -> "Video only"
                                },
                                color = GamerTextSecondary,
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }
        }

        // 6. Switches (Auto-connect, Overlay, WakeLock, WebServer)
        SettingsSectionCard(title = "Advanced Enhancements", icon = Icons.Default.Tune) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingSwitchRow(
                    title = "Automatic TV Connection",
                    subtitle = "Select the first TV found and reconnect automatically after a drop",
                    checked = settings.autoConnect,
                    onCheckedChange = { onSettingsChanged(settings.copy(autoConnect = it)) }
                )
                HorizontalDivider(color = GamerCardBorder.copy(alpha = 0.5f))
                SettingSwitchRow(
                    title = "In-Game Floating Overlay HUD",
                    subtitle = "Shows live FPS & latency pill with quick pause/stop controls",
                    checked = settings.enableFloatingOverlay,
                    onCheckedChange = { onSettingsChanged(settings.copy(enableFloatingOverlay = it)) }
                )
                HorizontalDivider(color = GamerCardBorder.copy(alpha = 0.5f))
                SettingSwitchRow(
                    title = "Keep Screen Awake (WakeLock)",
                    subtitle = "Prevents screen dimming or sleep during intense gaming",
                    checked = settings.enableKeepAwake,
                    onCheckedChange = { onSettingsChanged(settings.copy(enableKeepAwake = it)) }
                )
                HorizontalDivider(color = GamerCardBorder.copy(alpha = 0.5f))
                SettingSwitchRow(
                    title = "Universal Web Browser Casting",
                    subtitle = "Host stream on http://<ip>:8080 for PC, Mac, iPhone & Smart TVs",
                    checked = settings.enableWebServer,
                    onCheckedChange = { onSettingsChanged(settings.copy(enableWebServer = it)) }
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
    }
}

@Composable
fun SettingsSectionCard(
    title: String,
    icon: ImageVector,
    content: @Composable () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = GamerCardBg),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, GamerCardBorder, RoundedCornerShape(16.dp))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = GamerAccent, modifier = Modifier.size(20.dp))
                Text(text = title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            content()
        }
    }
}

@Composable
fun OptionChip(
    label: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .background(
                if (isSelected) GamerAccent.copy(alpha = 0.2f) else Color(0xFF161B22),
                RoundedCornerShape(8.dp)
            )
            .border(
                1.dp,
                if (isSelected) GamerAccent else Color(0xFF30363D),
                RoundedCornerShape(8.dp)
            )
            .clickable { onClick() }
            .padding(vertical = 10.dp, horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (isSelected) GamerAccent else Color.White,
            fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(text = title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(text = subtitle, color = GamerTextSecondary, fontSize = 11.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black,
                checkedTrackColor = GamerNeonGreen,
                uncheckedThumbColor = Color.Gray,
                uncheckedTrackColor = Color(0xFF21262D)
            )
        )
    }
}
