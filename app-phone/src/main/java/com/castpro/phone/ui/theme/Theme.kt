package com.castpro.phone.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val CastProColorScheme = darkColorScheme(
    primary = GamerNeonGreen,
    onPrimary = GamerDarkBg,
    primaryContainer = GamerAccent.copy(alpha = 0.20f),
    onPrimaryContainer = GamerTextPrimary,
    secondary = GamerAccent,
    onSecondary = GamerDarkBg,
    secondaryContainer = Color(0xFF572033),
    onSecondaryContainer = GamerTextPrimary,
    background = GamerDarkBg,
    onBackground = GamerTextPrimary,
    surface = GamerSurface,
    onSurface = GamerTextPrimary,
    surfaceVariant = Color(0xFF241720),
    onSurfaceVariant = GamerTextSecondary,
    outline = GamerCardBorder,
    error = GamerWarning
)

private val CastProShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun CastProTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CastProColorScheme,
        shapes = CastProShapes,
        content = content
    )
}
