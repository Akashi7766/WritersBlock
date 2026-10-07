package com.example.writersblock.theme

import androidx.compose.ui.graphics.Color

// A castle at night: purple sky and stone, orange banners, a parchment scroll for writing on.

// ── Sky ──────────────────────────────────────────────────────────────────────────────────────────
val NightDeep = Color(0xFF110920)   // top of the sky, status bar
val Night = Color(0xFF1D1133)       // the sky behind most content
val Dusk = Color(0xFF34205C)        // the glow along the horizon
val Moon = Color(0xFFFFF0C8)
val Star = Color(0xFFE9DDFF)

// ── Stone (surfaces on the dark screens) ─────────────────────────────────────────────────────────
val Stone = Color(0xFF2B1A4A)       // card fill
val StoneRaised = Color(0xFF3B2765) // a surface on a surface, pressed states
val StoneWall = Color(0xFF1A0F30)   // the castle wall silhouette
val Outline = Color(0xFF0B0616)     // the cartoon outline every shape gets

// ── Text on stone ────────────────────────────────────────────────────────────────────────────────
val Lavender = Color(0xFFEDE4FF)    // primary text
val Mist = Color(0xFFA796C9)        // secondary text, hints
val Amethyst = Color(0xFF9B6BE0)    // links, focus, the "on" state

// ── Banners ──────────────────────────────────────────────────────────────────────────────────────
val Ember = Color(0xFFF28A2E)       // the banner orange; primary actions
val EmberDeep = Color(0xFFB4561A)   // banner folds and button bases
val Gold = Color(0xFFFFC85E)        // trim, highlights, the selection corners

// ── The scroll ───────────────────────────────────────────────────────────────────────────────────
val Parchment = Color(0xFFF5E6C4)
val ParchmentShade = Color(0xFFE6CC98)
val ParchmentEdge = Color(0xFFC7A26A)
val Ink = Color(0xFF3A2315)
val InkFaded = Color(0xFF8C6B4B)
val Wood = Color(0xFF5E3519)
val WoodLight = Color(0xFF9A6233)

/** Reserved for destruction, so it keeps meaning "careful". */
val Blood = Color(0xFFE2503C)
