package com.example.writersblock.theme

import androidx.compose.ui.unit.dp

/**
 * One spacing scale, so unrelated screens do not each invent their own rhythm.
 *
 * The app is a dense, technical, monospace thing; the steps are small and the top of the scale is
 * used sparingly. Anything that needs a one-off value should still be rounded to one of these.
 */
object Gap {
    /** Between a label and the thing it labels. */
    val hairline = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 28.dp

    /** Screen-edge padding. Wide, because the panels are full-bleed and the text is dense. */
    val screenH = 18.dp
    val screenV = 14.dp
}

/**
 * Corner radii.
 *
 * Material's defaults are rounder than this app wants, but the original 4.dp everywhere read as
 * unfinished rather than as a choice. These are deliberately short of pill-shaped.
 */
object Corner {
    val none = 0.dp

    /** Chips, badges, tiny controls. */
    val small = 6.dp

    /** Buttons and inputs. */
    val medium = 8.dp

    /** Cards and panels. */
    val large = 14.dp

    /** Sheets and anything that reads as a surface laid over another. */
    val sheet = 18.dp
}
