package com.example.writersblock.theme

import androidx.compose.ui.graphics.Color

// Minimalist Monochrome Palette
val SlateBlack = Color(0xFF121212)    // Main background
val SlateDark = Color(0xFF1E1E1E)     // Card/surface background
val SlateRaised = Color(0xFF272727)   // A surface on a surface: rows inside cards, sheet headers
val BorderGray = Color(0xFF2C2C2C)    // Card/button stroke borders
val BorderBright = Color(0xFF3F3F3F)  // Hover/pressed borders, dividers that need to be seen
val StrokeWhite = Color(0xFFFFFFFF)   // Selected/highlight stroke borders
val TextWhite = Color(0xFFFFFFFF)     // High-contrast primary text
val TextGray = Color(0xFFE0E0E0)      // Medium-contrast secondary text
val TextMuted = Color(0xFF888888)     // Low-contrast captions and hints

val PureWhite = Color(0xFFFFFFFF)
val PureBlack = Color(0xFF000000)

/**
 * The one non-monochrome colour, and only for destruction.
 *
 * Reserved rather than general: it is the only thing in the app that reads as a warning, which is
 * what makes it work as one.
 */
val Danger = Color(0xFFEF5350)
