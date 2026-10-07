package com.example.writersblock.theme

import androidx.compose.ui.unit.dp

/** One spacing scale, so unrelated screens do not each invent their own rhythm. */
object Gap {
    /** Between a label and the thing it labels. */
    val hairline = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 28.dp

    val screenH = 18.dp
    val screenV = 14.dp
}

/** Corner radii. Soft enough to read as cartoon, short of pill-shaped. */
object Corner {
    val small = 6.dp
    val medium = 10.dp
    val large = 16.dp
}

/** Stroke widths for the cartoon outline. Thick enough to read as drawn, not as a hairline border. */
object Pen {
    val outline = 2.dp
    val bold = 3.dp
}
