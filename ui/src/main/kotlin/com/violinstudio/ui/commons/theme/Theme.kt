package com.violinstudio.ui.commons.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val ViolinDarkColors = darkColorScheme(
    primary = PrimaryPurple,
    onPrimary = OnPrimaryPurple,
    primaryContainer = PrimaryContainer,
    onPrimaryContainer = OnPrimaryContainer,
    secondary = SecondaryLav,
    background = DarkBg,
    surface = DarkSurface,
    surfaceVariant = DarkSurfaceVariant,
    onBackground = TextLight,
    onSurface = TextLight,
    onSurfaceVariant = TextMuted,
    outline = DarkSurfaceVariant,
    error = ErrorRed
)

/** Tema único oscuro (decisión heredada de Violin-master); sin color dinámico. */
@Composable
fun ViolinStudioTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ViolinDarkColors, content = content)
}
