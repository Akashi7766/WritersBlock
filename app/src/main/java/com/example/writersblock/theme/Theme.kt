package com.example.writersblock.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = PureWhite,
    onPrimary = PureBlack,
    secondary = TextGray,
    onSecondary = SlateBlack,
    background = SlateBlack,
    onBackground = TextWhite,
    surface = SlateDark,
    onSurface = TextWhite,
    outline = BorderGray
)

@Composable
fun WritersBlockTheme(
    darkTheme: Boolean = true, // Force dark theme
    dynamicColor: Boolean = false, // Force monochrome scheme
    content: @Composable () -> Unit
) {
    // We enforce our strict Minimalist Monochrome theme regardless of system settings
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
