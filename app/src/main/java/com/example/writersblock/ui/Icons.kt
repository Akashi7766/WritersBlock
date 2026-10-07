package com.example.writersblock.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.writersblock.theme.Lavender

/**
 * The app's icons, drawn rather than imported.
 *
 * They were emoji — 📁 📄 ⚙ ✏️ — which render in full colour on Android and sat badly against a
 * strictly monochrome, monospace interface. Pulling in `material-icons-core` would fix the colour but
 * adds a dependency for eight shapes, and the shapes would then be Material's rather than this app's.
 * These are a few strokes each and match the weight of the text around them.
 *
 * Every icon is drawn in a unit square and scaled, so one stroke width scales with the size and the
 * set stays optically consistent at any of them.
 */
private val DefaultIconSize = 18.dp

@Composable
private fun Glyph(
    tint: Color,
    size: Dp,
    modifier: Modifier = Modifier,
    draw: DrawScope.(unit: Float) -> Unit,
) {
    Canvas(modifier = modifier.size(size)) {
        // Sized off the smaller edge, so a clipped or non-square slot still yields a square glyph.
        draw(this.size.minDimension)
    }
}

/** A line between two points of the unit square. */
private fun DrawScope.line(
    unit: Float,
    x1: Float, y1: Float, x2: Float, y2: Float,
    tint: Color,
    weight: Float,
) {
    drawLine(
        color = tint,
        start = Offset(x1 * unit, y1 * unit),
        end = Offset(x2 * unit, y2 * unit),
        strokeWidth = unit * weight,
        cap = StrokeCap.Round,
    )
}

private const val W = 0.085f

/** A left-pointing chevron with a shaft. The back affordance on every screen that has one. */
@Composable
fun IconBack(
    modifier: Modifier = Modifier,
    tint: Color = Lavender,
    size: Dp = DefaultIconSize,
) = Glyph(tint, size, modifier) { u ->
    line(u, 0.62f, 0.18f, 0.24f, 0.5f, tint, W)
    line(u, 0.24f, 0.5f, 0.62f, 0.82f, tint, W)
    line(u, 0.26f, 0.5f, 0.82f, 0.5f, tint, W)
}

/** A cross. Dismiss, delete, clear. */
@Composable
fun IconClose(
    modifier: Modifier = Modifier,
    tint: Color = Lavender,
    size: Dp = DefaultIconSize,
) = Glyph(tint, size, modifier) { u ->
    line(u, 0.24f, 0.24f, 0.76f, 0.76f, tint, W)
    line(u, 0.76f, 0.24f, 0.24f, 0.76f, tint, W)
}

/** A plus. Creating a file or a folder. */
@Composable
fun IconPlus(
    modifier: Modifier = Modifier,
    tint: Color = Lavender,
    size: Dp = DefaultIconSize,
) = Glyph(tint, size, modifier) { u ->
    line(u, 0.5f, 0.2f, 0.5f, 0.8f, tint, W)
    line(u, 0.2f, 0.5f, 0.8f, 0.5f, tint, W)
}

/** A folder, as an outline with a tab. */
@Composable
fun IconFolder(
    modifier: Modifier = Modifier,
    tint: Color = Lavender,
    size: Dp = DefaultIconSize,
) = Glyph(tint, size, modifier) { u ->
    val stroke = Stroke(width = u * W, join = StrokeJoin.Round)
    val path = Path().apply {
        moveTo(0.14f * u, 0.26f * u)
        lineTo(0.42f * u, 0.26f * u)
        lineTo(0.50f * u, 0.36f * u)
        lineTo(0.86f * u, 0.36f * u)
        lineTo(0.86f * u, 0.78f * u)
        lineTo(0.14f * u, 0.78f * u)
        close()
    }
    drawPath(path, color = tint, style = stroke)
}

/** A page, as an outline with a folded corner and two ruled lines. */
@Composable
fun IconNote(
    modifier: Modifier = Modifier,
    tint: Color = Lavender,
    size: Dp = DefaultIconSize,
) = Glyph(tint, size, modifier) { u ->
    val stroke = Stroke(width = u * W, join = StrokeJoin.Round)
    val path = Path().apply {
        moveTo(0.24f * u, 0.14f * u)
        lineTo(0.58f * u, 0.14f * u)
        lineTo(0.78f * u, 0.34f * u)
        lineTo(0.78f * u, 0.86f * u)
        lineTo(0.24f * u, 0.86f * u)
        close()
    }
    drawPath(path, color = tint, style = stroke)
    val inner = Stroke(width = u * W * 0.7f, cap = StrokeCap.Round)
    drawLine(tint, Offset(0.58f * u, 0.14f * u), Offset(0.58f * u, 0.34f * u), inner.width)
    drawLine(tint, Offset(0.58f * u, 0.34f * u), Offset(0.78f * u, 0.34f * u), inner.width)
}

/** A camera frame with a dot: the scan action. */
@Composable
fun IconScan(
    modifier: Modifier = Modifier,
    tint: Color = Lavender,
    size: Dp = DefaultIconSize,
) = Glyph(tint, size, modifier) { u ->
    val corner = 0.22f
    // Four corner brackets, which read as a viewfinder rather than as a box.
    line(u, 0.14f, 0.32f, 0.14f, 0.16f, tint, W); line(u, 0.14f, 0.16f, 0.32f, 0.16f, tint, W)
    line(u, 0.68f, 0.16f, 0.86f, 0.16f, tint, W); line(u, 0.86f, 0.16f, 0.86f, 0.32f, tint, W)
    line(u, 0.86f, 0.68f, 0.86f, 0.84f, tint, W); line(u, 0.86f, 0.84f, 0.68f, 0.84f, tint, W)
    line(u, 0.32f, 0.84f, 0.14f, 0.84f, tint, W); line(u, 0.14f, 0.84f, 0.14f, 0.68f, tint, W)
    drawCircle(tint, radius = corner * u * 0.34f, center = Offset(0.5f * u, 0.5f * u))
}

/** A tick, for a granted permission. */
@Composable
fun IconCheck(
    modifier: Modifier = Modifier,
    tint: Color = Lavender,
    size: Dp = DefaultIconSize,
) = Glyph(tint, size, modifier) { u ->
    line(u, 0.2f, 0.52f, 0.42f, 0.74f, tint, W)
    line(u, 0.42f, 0.74f, 0.8f, 0.28f, tint, W)
}

/** Two chevrons stacked: the settings affordance, as a monochrome mark rather than a colour gear. */
@Composable
fun IconSliders(
    modifier: Modifier = Modifier,
    tint: Color = Lavender,
    size: Dp = DefaultIconSize,
) = Glyph(tint, size, modifier) { u ->
    val w = W * 0.9f
    line(u, 0.16f, 0.32f, 0.84f, 0.32f, tint, w)
    line(u, 0.16f, 0.68f, 0.84f, 0.68f, tint, w)
    drawCircle(tint, radius = 0.11f * u, center = Offset(0.34f * u, 0.32f * u))
    drawCircle(tint, radius = 0.11f * u, center = Offset(0.66f * u, 0.68f * u))
}

/** A downward chevron: "this opens something". */
@Composable
fun IconChevronDown(
    modifier: Modifier = Modifier,
    tint: Color = Lavender,
    size: Dp = DefaultIconSize,
) = Glyph(tint, size, modifier) { u ->
    line(u, 0.22f, 0.38f, 0.5f, 0.66f, tint, W)
    line(u, 0.5f, 0.66f, 0.78f, 0.38f, tint, W)
}

/** A right-pointing chevron: a row that leads somewhere. */
@Composable
fun IconChevronRight(
    modifier: Modifier = Modifier,
    tint: Color = Lavender,
    size: Dp = DefaultIconSize,
) = Glyph(tint, size, modifier) { u ->
    line(u, 0.4f, 0.24f, 0.68f, 0.5f, tint, W)
    line(u, 0.68f, 0.5f, 0.4f, 0.76f, tint, W)
}

/** Three dots: an overflow menu. */
@Composable
fun IconMore(
    modifier: Modifier = Modifier,
    tint: Color = Lavender,
    size: Dp = DefaultIconSize,
) = Glyph(tint, size, modifier) { u ->
    drawCircle(tint, radius = 0.085f * u, center = Offset(0.5f * u, 0.24f * u))
    drawCircle(tint, radius = 0.085f * u, center = Offset(0.5f * u, 0.5f * u))
    drawCircle(tint, radius = 0.085f * u, center = Offset(0.5f * u, 0.76f * u))
}

/** A tick inside a rounded square: the bound-note indicator. */
@Composable
fun IconLink(
    modifier: Modifier = Modifier,
    tint: Color = Lavender,
    size: Dp = DefaultIconSize,
) = Glyph(tint, size, modifier) { u ->
    val stroke = Stroke(width = u * W, cap = StrokeCap.Round)
    drawRoundRect(
        color = tint,
        topLeft = Offset(0.12f * u, 0.12f * u),
        size = Size(0.76f * u, 0.76f * u),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(0.18f * u),
        style = stroke,
    )
    line(u, 0.34f, 0.52f, 0.46f, 0.64f, tint, W * 0.9f)
    line(u, 0.46f, 0.64f, 0.68f, 0.38f, tint, W * 0.9f)
}

/** A feather quill: writing something new. */
@Composable
fun IconQuill(
    modifier: Modifier = Modifier,
    tint: Color = Lavender,
    size: Dp = DefaultIconSize,
) = Glyph(tint, size, modifier) { u ->
    val vane = Path().apply {
        moveTo(0.86f * u, 0.12f * u)
        quadraticTo(0.42f * u, 0.18f * u, 0.34f * u, 0.66f * u)
        quadraticTo(0.80f * u, 0.56f * u, 0.86f * u, 0.12f * u)
        close()
    }
    drawPath(vane, tint, style = Stroke(width = u * W, join = StrokeJoin.Round))
    line(u, 0.80f, 0.20f, 0.40f, 0.60f, tint, W * 0.7f)
    line(u, 0.34f, 0.66f, 0.16f, 0.86f, tint, W)
}

/** Three linked dots: send to another app. */
@Composable
fun IconShare(
    modifier: Modifier = Modifier,
    tint: Color = Lavender,
    size: Dp = DefaultIconSize,
) = Glyph(tint, size, modifier) { u ->
    line(u, 0.30f, 0.50f, 0.70f, 0.26f, tint, W)
    line(u, 0.30f, 0.50f, 0.70f, 0.74f, tint, W)
    listOf(Offset(0.28f, 0.50f), Offset(0.72f, 0.24f), Offset(0.72f, 0.76f)).forEach { c ->
        drawCircle(tint, radius = 0.11f * u, center = Offset(c.x * u, c.y * u))
    }
}
