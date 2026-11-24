package com.castpro.phone.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.castpro.core.common.model.LatencyMetrics
import com.castpro.phone.ui.theme.GamerAccent
import com.castpro.phone.ui.theme.GamerNeonGreen
import com.castpro.phone.ui.theme.GamerWarning

@Composable
fun LatencyHud(
    metrics: LatencyMetrics,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MetricItem(label = "FPS", value = "${metrics.currentFps}", highlightColor = GamerNeonGreen)
        MetricItem(
            label = "TV LINK",
            value = if (metrics.latencyMs < 0f) "—" else "LIVE",
            highlightColor = if (metrics.latencyMs < 0f) GamerWarning else GamerNeonGreen
        )
        MetricItem(label = "BITRATE", value = "${String.format("%.1f", metrics.bitrateMbps)} Mb/s", highlightColor = GamerAccent)
    }
}

@Composable
private fun MetricItem(label: String, value: String, highlightColor: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        Text(text = value, style = MaterialTheme.typography.bodyMedium, color = highlightColor)
    }
}
