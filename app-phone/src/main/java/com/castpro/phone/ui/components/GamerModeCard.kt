package com.castpro.phone.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.castpro.phone.ui.theme.GamerAccent
import com.castpro.phone.ui.theme.GamerNeonGreen
import com.castpro.phone.ui.theme.GamerSurface
import com.castpro.phone.ui.theme.GamerTextSecondary

@Composable
fun GamerModeCard(
    isGamingMode: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = GamerSurface),
        border = BorderStroke(
            width = 1.5.dp,
            color = if (isGamingMode) GamerNeonGreen else GamerAccent.copy(alpha = 0.3f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SportsEsports,
                    contentDescription = "Gamer Mode",
                    tint = if (isGamingMode) GamerNeonGreen else GamerTextSecondary,
                    modifier = Modifier.size(32.dp)
                )
                Column {
                    Text(
                        text = "Gaming Mode (Ultra-Low Latency)",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (isGamingMode) "60 FPS • 0-Buffer • Sub-35ms Target" else "Standard Latency Profile",
                        style = MaterialTheme.typography.bodySmall,
                        color = GamerTextSecondary
                    )
                }
            }

            Switch(
                checked = isGamingMode,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = GamerNeonGreen,
                    checkedTrackColor = GamerNeonGreen.copy(alpha = 0.3f)
                )
            )
        }
    }
}
