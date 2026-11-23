package com.castpro.phone.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val GamerColorScheme = darkColorScheme(
    primary = GamerNeonGreen,
    secondary = GamerAccent,
    background = GamerDarkBg,
    surface = GamerSurface,
    error = GamerWarning,
    onPrimary = GamerDarkBg,
    onBackground = GamerTextPrimary,
    onSurface = GamerTextPrimary
)

@Composable
fun CastProTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = GamerColorScheme,
        content = content
    )
}
