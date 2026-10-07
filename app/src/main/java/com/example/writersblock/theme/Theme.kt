package com.example.writersblock.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val CastleColors = darkColorScheme(
    primary = Ember,
    onPrimary = Outline,
    secondary = Amethyst,
    onSecondary = Lavender,
    background = Night,
    onBackground = Lavender,
    surface = Stone,
    onSurface = Lavender,
    surfaceContainer = Stone,
    outline = Outline,
    error = Blood,
)

/** One fixed look: the castle does not follow the system's light or dynamic colours. */
@Composable
fun WritersBlockTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = CastleColors, typography = Typography, content = content)
}
