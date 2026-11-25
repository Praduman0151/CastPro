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
    primaryContainer = GamerNeonGreen.copy(alpha = 0.18f),
    onPrimaryContainer = GamerTextPrimary,
    secondary = GamerAccent,
    onSecondary = GamerDarkBg,
    secondaryContainer = GamerAccent.copy(alpha = 0.16f),
    onSecondaryContainer = GamerTextPrimary,
    background = GamerDarkBg,
    onBackground = GamerTextPrimary,
    surface = GamerSurface,
    onSurface = GamerTextPrimary,
    surfaceVariant = GamerCardBg,
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
