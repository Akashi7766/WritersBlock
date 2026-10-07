package com.example.writersblock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.writersblock.theme.Amethyst
import com.example.writersblock.theme.Blood
import com.example.writersblock.theme.Book
import com.example.writersblock.theme.Corner
import com.example.writersblock.theme.Dusk
import com.example.writersblock.theme.Ember
import com.example.writersblock.theme.EmberDeep
import com.example.writersblock.theme.Gap
import com.example.writersblock.theme.Gold
import com.example.writersblock.theme.Ink
import com.example.writersblock.theme.InkFaded
import com.example.writersblock.theme.Lavender
import com.example.writersblock.theme.Medieval
import com.example.writersblock.theme.Mist
import com.example.writersblock.theme.Moon
import com.example.writersblock.theme.Night
import com.example.writersblock.theme.NightDeep
import com.example.writersblock.theme.Outline
import com.example.writersblock.theme.Parchment
import com.example.writersblock.theme.ParchmentEdge
import com.example.writersblock.theme.ParchmentShade
import com.example.writersblock.theme.Pen
import com.example.writersblock.theme.Star
import com.example.writersblock.theme.Stone
import com.example.writersblock.theme.StoneRaised
import com.example.writersblock.theme.StoneWall
import com.example.writersblock.theme.Wood
import com.example.writersblock.theme.WoodLight
import kotlin.math.roundToInt
import kotlin.random.Random

// The castle kit: every themed shape in the app is drawn here, with Canvas primitives rather than
// image assets, so it stays crisp at any density and costs nothing in APK size.

// ── Backdrop ──────────────────────────────────────────────────────────────────────────────────────

private class StarSpec(val x: Float, val y: Float, val radius: Float, val alpha: Float)

/** Fixed seed: the sky is the same every time the screen is opened, not a new one per visit. */
private val Stars: List<StarSpec> = Random(1411).let { rnd ->
    List(70) {
        StarSpec(
            x = rnd.nextFloat(),
            y = rnd.nextFloat() * 0.62f,
            radius = 0.5f + rnd.nextFloat() * 1.3f,
            alpha = 0.3f + rnd.nextFloat() * 0.6f,
        )
    }
}

/**
 * Night sky over a castle wall, behind a whole screen.
 *
 * The wall sits along the bottom edge. Scrolling content passes over it, so lists should leave
 * [CastleWallHeight] of bottom padding if their last item must not end up in front of the towers.
 */
@Composable
fun CastleBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind { drawCastleNight() },
        content = content,
    )
}

val CastleWallHeight = 120.dp

private fun DrawScope.drawCastleNight() {
    val w = size.width
    val h = size.height
    val d = density

    drawRect(Brush.verticalGradient(0f to NightDeep, 0.55f to Night, 1f to Dusk))

    Stars.forEach { s ->
        drawCircle(Star.copy(alpha = s.alpha), radius = s.radius * d, center = Offset(s.x * w, s.y * h))
    }

    // A crescent rising behind the wall: low enough to stay clear of every screen's header controls.
    val moonCenter = Offset(w * 0.34f, h - 150f * d)
    val moonR = 34f * d
    drawCircle(
        Brush.radialGradient(
            listOf(Moon.copy(alpha = 0.18f), Color.Transparent),
            center = moonCenter,
            radius = moonR * 3.2f,
        ),
        radius = moonR * 3.2f,
        center = moonCenter,
    )
    val crescent = Path().apply {
        op(
            Path().apply { addOval(Rect(moonCenter, moonR)) },
            Path().apply { addOval(Rect(moonCenter + Offset(moonR * 0.45f, -moonR * 0.25f), moonR * 0.9f)) },
            PathOperation.Difference,
        )
    }
    drawPath(crescent, Moon)

    drawCastleWall(w, h, d)
}

private fun DrawScope.drawCastleWall(w: Float, h: Float, d: Float) {
    val wallTop = h - 74f * d
    val merlonW = 20f * d
    val merlonGap = 14f * d
    val merlonH = 12f * d

    // Towers first, so the wall's crenellations overlap their feet.
    drawTower(left = w * 0.06f, width = 58f * d, top = h - 128f * d, bottom = h, d = d, roof = false)
    drawTower(left = w * 0.76f, width = 66f * d, top = h - 150f * d, bottom = h, d = d, roof = true)

    val wall = Path().apply {
        moveTo(0f, h)
        lineTo(0f, wallTop)
        var x = 0f
        while (x < w) {
            lineTo(x, wallTop - merlonH)
            lineTo(x + merlonW, wallTop - merlonH)
            lineTo(x + merlonW, wallTop)
            lineTo(x + merlonW + merlonGap, wallTop)
            x += merlonW + merlonGap
        }
        lineTo(w, wallTop)
        lineTo(w, h)
        close()
    }
    drawPath(wall, StoneWall)

    // A few courses of brick, staggered: enough to read as masonry, not enough to be busy.
    val course = 16f * d
    val brick = 34f * d
    var row = 0
    var y = wallTop + course
    while (y < h) {
        drawLine(Dusk.copy(alpha = 0.55f), Offset(0f, y), Offset(w, y), strokeWidth = 1f * d)
        var x = if (row % 2 == 0) 0f else brick / 2f
        while (x < w) {
            drawLine(Dusk.copy(alpha = 0.45f), Offset(x, y - course), Offset(x, y), strokeWidth = 1f * d)
            x += brick
        }
        y += course
        row++
    }
}

private fun DrawScope.drawTower(left: Float, width: Float, top: Float, bottom: Float, d: Float, roof: Boolean) {
    drawRect(StoneWall, topLeft = Offset(left, top), size = Size(width, bottom - top))

    // Crenellated top for the plain tower; a pointed roof and a banner for the tall one.
    if (!roof) {
        val m = width / 5f
        for (i in 0 until 5 step 2) {
            drawRect(StoneWall, topLeft = Offset(left + i * m, top - 10f * d), size = Size(m, 10f * d))
        }
    } else {
        val roofPath = Path().apply {
            moveTo(left - 6f * d, top)
            lineTo(left + width / 2f, top - 46f * d)
            lineTo(left + width + 6f * d, top)
            close()
        }
        drawPath(roofPath, StoneWall)
        val poleTop = top - 46f * d - 26f * d
        val poleX = left + width / 2f
        drawLine(Outline, Offset(poleX, top - 46f * d), Offset(poleX, poleTop), strokeWidth = 2f * d)
        val flag = Path().apply {
            moveTo(poleX, poleTop)
            lineTo(poleX + 26f * d, poleTop + 4f * d)
            lineTo(poleX + 18f * d, poleTop + 8f * d)
            lineTo(poleX + 26f * d, poleTop + 12f * d)
            lineTo(poleX, poleTop + 14f * d)
            close()
        }
        drawPath(flag, Ember)
    }

    // One warm window: the castle is lived in.
    val winW = 10f * d
    val winH = 16f * d
    val winLeft = left + (width - winW) / 2f
    val winTop = top + 22f * d
    drawRoundRect(
        Gold,
        topLeft = Offset(winLeft, winTop),
        size = Size(winW, winH),
        cornerRadius = CornerRadius(winW / 2f, winW / 2f),
    )
}

// ── Banners ───────────────────────────────────────────────────────────────────────────────────────

/**
 * A ribbon banner with swallowtail ends, for screen titles and the crop screen's instructions.
 *
 * The tails sit a little lower than the face and in a deeper shade, which is all it takes for the
 * ribbon to read as folded behind itself.
 */
@Composable
fun Banner(
    text: String,
    modifier: Modifier = Modifier,
    face: Color = Ember,
    fold: Color = EmberDeep,
    textColor: Color = Outline,
    style: TextStyle = MaterialTheme.typography.titleMedium,
) {
    Box(
        modifier = modifier
            .drawBehind { drawRibbon(face, fold) }
            .padding(horizontal = 34.dp)
            .padding(top = 6.dp, bottom = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = style, color = textColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun DrawScope.drawRibbon(face: Color, fold: Color) {
    val d = density
    val w = size.width
    val h = size.height
    val drop = 6f * d
    val tail = 26f * d
    val notch = 9f * d
    val outline = Stroke(width = Pen.outline.toPx())

    fun tailPath(left: Boolean): Path = Path().apply {
        if (left) {
            moveTo(0f, drop)
            lineTo(tail + 6f * d, drop)
            lineTo(tail + 6f * d, h)
            lineTo(0f, h)
            lineTo(notch, (drop + h) / 2f)
        } else {
            moveTo(w, drop)
            lineTo(w - tail - 6f * d, drop)
            lineTo(w - tail - 6f * d, h)
            lineTo(w, h)
            lineTo(w - notch, (drop + h) / 2f)
        }
        close()
    }

    listOf(tailPath(true), tailPath(false)).forEach {
        drawPath(it, fold)
        drawPath(it, Outline, style = outline)
    }

    val faceRect = Rect(tail, 0f, w - tail, h - drop)
    drawRect(face, topLeft = faceRect.topLeft, size = faceRect.size)
    // A thin highlight along the top edge gives the cloth a little body.
    drawRect(Color.White.copy(alpha = 0.18f), topLeft = faceRect.topLeft, size = Size(faceRect.width, 3f * d))
    drawRect(Outline, topLeft = faceRect.topLeft, size = faceRect.size, style = outline)
}

/** A small pennant and a label: the eyebrow over a section. */
@Composable
fun PennantLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Gold,
    textColor: Color = Mist,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(width = 12.dp, height = 14.dp)
                .drawBehind {
                    val p = Path().apply {
                        moveTo(0f, 0f)
                        lineTo(size.width, size.height * 0.5f)
                        lineTo(0f, size.height)
                        close()
                    }
                    drawPath(p, color)
                }
        )
        Spacer(Modifier.width(Gap.sm))
        Text(text = text, style = MaterialTheme.typography.labelSmall, color = textColor)
    }
}

// ── Stone surfaces ────────────────────────────────────────────────────────────────────────────────

private val CardShape = RoundedCornerShape(Corner.large)

/**
 * A stone card: purple fill, cartoon outline, and a hard drop shadow under it rather than a blur.
 */
@Composable
fun StoneCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    fill: Color = Stone,
    contentPadding: Dp = Gap.md,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .drawBehind {
                drawRoundRect(
                    Outline,
                    topLeft = Offset(0f, 4.dp.toPx()),
                    size = size,
                    cornerRadius = CornerRadius(Corner.large.toPx()),
                )
            }
            .clip(CardShape)
            .background(fill)
            .border(Pen.outline, Outline, CardShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(contentPadding),
        content = content,
    )
}

// ── Buttons ───────────────────────────────────────────────────────────────────────────────────────

enum class ButtonKind { Primary, Stone, Parchment, Danger }

private class ButtonColors(val face: Color, val base: Color, val text: Color)

private fun colorsFor(kind: ButtonKind, enabled: Boolean): ButtonColors = when {
    !enabled -> ButtonColors(Stone, Outline, Mist.copy(alpha = 0.6f))
    kind == ButtonKind.Primary -> ButtonColors(Ember, EmberDeep, Outline)
    kind == ButtonKind.Stone -> ButtonColors(StoneRaised, Outline, Lavender)
    kind == ButtonKind.Parchment -> ButtonColors(ParchmentShade, ParchmentEdge, Ink)
    else -> ButtonColors(Blood, Color(0xFF7E2418), Parchment)
}

/**
 * The app's one button: a chunky cartoon block that sinks when pressed.
 *
 * The darker base under the face is what sells it as a physical thing. Pressing moves the face down
 * onto the base, which is better feedback than a ripple on a flat colour.
 */
@Composable
fun RoyalButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Stone,
    enabled: Boolean = true,
    height: Dp = 46.dp,
    icon: (@Composable (Color) -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val colors = colorsFor(kind, enabled)
    val depth = 4.dp
    val sink = if (pressed && enabled) depth - 1.dp else 0.dp

    Box(
        modifier = modifier
            .height(height)
            .semantics { contentDescription = label }
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .drawBehind {
                val r = CornerRadius(Corner.medium.toPx())
                val depthPx = depth.toPx()
                val sinkPx = sink.toPx()
                val faceSize = Size(size.width, size.height - depthPx)
                val stroke = Stroke(Pen.outline.toPx())
                drawRoundRect(colors.base, topLeft = Offset(0f, depthPx), size = faceSize, cornerRadius = r)
                drawRoundRect(Outline, topLeft = Offset(0f, depthPx), size = faceSize, cornerRadius = r, style = stroke)
                drawRoundRect(colors.face, topLeft = Offset(0f, sinkPx), size = faceSize, cornerRadius = r)
                drawRoundRect(Outline, topLeft = Offset(0f, sinkPx), size = faceSize, cornerRadius = r, style = stroke)
            }
            .padding(bottom = depth)
            .offset { IntOffset(0, sink.roundToPx()) }
            .padding(horizontal = Gap.md),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (icon != null) {
                icon(colors.text)
                Spacer(Modifier.width(Gap.sm))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A round icon-only button. [tint] is applied to the glyph. */
@Composable
fun IconAction(
    label: String,
    onClick: () -> Unit,
    size: Dp = 42.dp,
    tint: Color = Lavender,
    icon: @Composable (Color) -> Unit,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(Corner.medium))
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        icon(tint)
    }
}

// ── The scroll ────────────────────────────────────────────────────────────────────────────────────

/**
 * A parchment scroll: a wooden roller top and bottom, parchment between.
 *
 * [unroll] is read only while laying out and drawing, never during composition, so an animated
 * unroll repaints the scroll without recomposing whatever is written on it. At 0 the scroll is rolled
 * up: the parchment is clipped away and the bottom roller sits against the top one.
 */
@Composable
fun ScrollPanel(
    modifier: Modifier = Modifier,
    unroll: () -> Float = { 1f },
    content: @Composable ColumnScope.() -> Unit,
) {
    var bodyHeight by remember { mutableIntStateOf(0) }

    Column(modifier = modifier) {
        Roller(Modifier.fillMaxWidth())
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 6.dp)
                .onSizeChanged { bodyHeight = it.height }
                .drawWithContent {
                    clipRect(bottom = size.height * unroll().coerceIn(0f, 1f)) {
                        this@drawWithContent.drawContent()
                    }
                }
                .drawBehind { drawParchment() },
            content = content,
        )
        Roller(
            Modifier
                .fillMaxWidth()
                .offset { IntOffset(0, -((1f - unroll().coerceIn(0f, 1f)) * bodyHeight).roundToInt()) }
        )
    }
}

/** The same scroll, sized to its content, for dialogs. */
@Composable
fun ScrollCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier) {
        Roller(Modifier.fillMaxWidth())
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp)
                .drawBehind { drawParchment() }
                .padding(horizontal = Gap.lg, vertical = Gap.md),
            content = content,
        )
        Roller(Modifier.fillMaxWidth())
    }
}

private fun DrawScope.drawParchment() {
    drawRect(Parchment)
    // Darker toward the edges, the way old paper browns where it is handled.
    val edge = 14f * density
    drawRect(
        Brush.horizontalGradient(0f to ParchmentShade, 1f to Parchment.copy(alpha = 0f), endX = edge),
        size = Size(edge, size.height),
    )
    drawRect(
        Brush.horizontalGradient(0f to Parchment.copy(alpha = 0f), 1f to ParchmentShade, startX = size.width - edge, endX = size.width),
        topLeft = Offset(size.width - edge, 0f),
        size = Size(edge, size.height),
    )
    val stroke = Pen.outline.toPx()
    drawLine(ParchmentEdge, Offset(stroke / 2, 0f), Offset(stroke / 2, size.height), strokeWidth = stroke)
    drawLine(ParchmentEdge, Offset(size.width - stroke / 2, 0f), Offset(size.width - stroke / 2, size.height), strokeWidth = stroke)
}

/** A rolled-up end of the scroll: a parchment cylinder on a wooden rod with knobbed ends. */
@Composable
fun Roller(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(22.dp)
            .drawBehind {
                val d = density
                val knobR = 8f * d
                val stroke = Stroke(Pen.outline.toPx())
                val cy = size.height / 2f

                drawLine(Wood, Offset(knobR, cy), Offset(size.width - knobR, cy), strokeWidth = 5f * d)

                val rollLeft = knobR + 4f * d
                val rollSize = Size(size.width - 2 * rollLeft, size.height - 2f * d)
                val rollTop = Offset(rollLeft, 1f * d)
                val r = CornerRadius(rollSize.height / 2f)
                drawRoundRect(
                    Brush.verticalGradient(
                        0f to ParchmentEdge,
                        0.35f to Parchment,
                        0.55f to Parchment,
                        1f to ParchmentEdge,
                        startY = rollTop.y,
                        endY = rollTop.y + rollSize.height,
                    ),
                    topLeft = rollTop,
                    size = rollSize,
                    cornerRadius = r,
                )
                drawRoundRect(Outline, topLeft = rollTop, size = rollSize, cornerRadius = r, style = stroke)

                listOf(knobR, size.width - knobR).forEach { x ->
                    drawCircle(WoodLight, radius = knobR, center = Offset(x, cy))
                    drawCircle(Wood, radius = knobR * 0.45f, center = Offset(x - knobR * 0.15f, cy + knobR * 0.15f))
                    drawCircle(Outline, radius = knobR, center = Offset(x, cy), style = stroke)
                }
            }
    )
}

// ── Text on the scroll ────────────────────────────────────────────────────────────────────────────

/** Small caps heading inside a scroll. */
@Composable
fun ScrollLabel(text: String, modifier: Modifier = Modifier, color: Color = InkFaded) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** A single-line field written on parchment: ink on an underline, no box. */
@Composable
fun InkField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    onDone: (() -> Unit)? = null,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ink),
        cursorBrush = SolidColor(Ink),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Sentences,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        modifier = modifier,
        decorationBox = { inner ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        val y = size.height - 1.dp.toPx()
                        drawLine(InkFaded, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.5f.dp.toPx())
                    }
                    .padding(vertical = Gap.sm)
            ) {
                if (value.isEmpty()) {
                    Text(hint, style = MaterialTheme.typography.bodyLarge, color = InkFaded.copy(alpha = 0.7f))
                }
                inner()
            }
        },
    )
}

// ── Dialogs ───────────────────────────────────────────────────────────────────────────────────────

/** A dialog written on a small scroll. Used by the in-app screens; the overlay draws its own sheets. */
@Composable
fun ScrollDialog(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        ScrollCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = Ink,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Gap.md))
            content()
        }
    }
}

/** A single-line name prompt: new note, new folder, save-as. */
@Composable
fun NameDialog(
    title: String,
    hint: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    initial: String = "",
) {
    var name by remember { mutableStateOf(initial) }
    ScrollDialog(title = title, onDismiss = onDismiss) {
        InkField(
            value = name,
            onValueChange = { name = it },
            hint = hint,
            onDone = { if (name.isNotBlank()) onConfirm(name.trim()) },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Gap.lg))
        DialogButtons(
            confirmLabel = confirmLabel,
            confirmEnabled = name.isNotBlank(),
            onConfirm = { onConfirm(name.trim()) },
            onDismiss = onDismiss,
        )
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    destructive: Boolean = false,
) {
    ScrollDialog(title = title, onDismiss = onDismiss) {
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = Ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Gap.lg))
        DialogButtons(
            confirmLabel = confirmLabel,
            confirmKind = if (destructive) ButtonKind.Danger else ButtonKind.Primary,
            onConfirm = onConfirm,
            onDismiss = onDismiss,
        )
    }
}

@Composable
fun DialogButtons(
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmEnabled: Boolean = true,
    confirmKind: ButtonKind = ButtonKind.Primary,
    dismissLabel: String = "Cancel",
) {
    Row(horizontalArrangement = Arrangement.spacedBy(Gap.sm)) {
        RoyalButton(
            label = dismissLabel,
            onClick = onDismiss,
            kind = ButtonKind.Parchment,
            modifier = Modifier.weight(1f),
        )
        RoyalButton(
            label = confirmLabel,
            onClick = onConfirm,
            kind = confirmKind,
            enabled = confirmEnabled,
            modifier = Modifier.weight(1f),
        )
    }
}

// ── Small pieces ──────────────────────────────────────────────────────────────────────────────────

/** A lit or unlit lantern dot: something is on, or needs attention. */
@Composable
fun StatusDot(active: Boolean, size: Dp = 8.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .background(if (active) Gold else Blood, RoundedCornerShape(size / 2))
            .border(1.dp, Outline, RoundedCornerShape(size / 2))
    )
}

@Composable
fun EmptyState(
    title: String,
    hint: String,
    modifier: Modifier = Modifier,
    titleColor: Color = Lavender,
    hintColor: Color = Mist,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Gap.xxl, horizontal = Gap.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium, color = titleColor, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Gap.xs))
        Text(text = hint, style = MaterialTheme.typography.bodySmall, color = hintColor, textAlign = TextAlign.Center)
    }
}

/** A thin rule. */
@Composable
fun Rule(modifier: Modifier = Modifier, color: Color = Outline) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(color)
    )
}

/** A note or folder row, shared by the home screen, the browser and the settings screen. */
@Composable
fun NoteRow(
    title: String,
    snippet: String,
    trailing: String?,
    isFolder: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    end: (@Composable () -> Unit)? = null,
) {
    StoneCard(modifier = modifier.fillMaxWidth(), onClick = onClick, contentPadding = 0.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp)
                .padding(start = Gap.md, end = Gap.xs, top = Gap.sm, bottom = Gap.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isFolder) IconFolder(tint = Gold, size = 22.dp) else IconNote(tint = Amethyst, size = 22.dp)
            Spacer(Modifier.width(Gap.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title.removeSuffix(".md"),
                    style = MaterialTheme.typography.titleSmall,
                    color = Lavender,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (snippet.isNotEmpty()) {
                    Text(
                        text = snippet,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = Book),
                        color = Mist,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(Gap.sm))
                Text(text = trailing, style = MaterialTheme.typography.labelSmall.copy(fontFamily = Medieval), color = Mist)
            }
            if (end != null) end() else Spacer(Modifier.width(Gap.sm))
        }
    }
}

/** Back button, a banner with the screen's name, and room for one action on the right. */
@Composable
fun ScreenHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    backLabel: String = "Back",
    action: (@Composable () -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = Gap.sm, bottom = Gap.md),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.align(Alignment.CenterStart)) {
            IconAction(label = backLabel, onClick = onBack) { tint -> IconBack(tint = tint, size = 22.dp) }
        }
        Banner(text = title, modifier = Modifier.padding(horizontal = 48.dp))
        if (action != null) {
            Box(Modifier.align(Alignment.CenterEnd)) { action() }
        }
    }
}

/** One text label for a screen reader, on a control that is only a drawn shape. */
fun Modifier.semanticsLabel(label: String): Modifier = this.semantics { contentDescription = label }

/**
 * The floating handle: the drawer, rolled up, tied with an orange ribbon and propped on the screen
 * edge. Drawn as one canvas because it lives in its own tiny overlay window.
 */
@Composable
fun ScrollHandle(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.drawBehind {
            val d = density
            val w = size.width
            val h = size.height
            val stroke = Stroke(Pen.outline.toPx())
            val knobR = w * 0.26f
            val cx = w / 2f

            drawLine(Wood, Offset(cx, knobR), Offset(cx, h - knobR), strokeWidth = 5f * d)

            val rollTop = Offset(w * 0.1f, knobR * 1.4f)
            val rollSize = Size(w * 0.8f, h - knobR * 2.8f)
            val r = CornerRadius(rollSize.width / 2f)
            drawRoundRect(
                Brush.horizontalGradient(
                    0f to ParchmentEdge,
                    0.4f to Parchment,
                    0.6f to Parchment,
                    1f to ParchmentEdge,
                    startX = rollTop.x,
                    endX = rollTop.x + rollSize.width,
                ),
                topLeft = rollTop,
                size = rollSize,
                cornerRadius = r,
            )
            drawRoundRect(Outline, topLeft = rollTop, size = rollSize, cornerRadius = r, style = stroke)

            // The ribbon: a band round the middle and a short tail hanging off it.
            val bandH = 9f * d
            val bandTop = h / 2f - bandH / 2f
            val tail = Path().apply {
                moveTo(cx - 2f * d, bandTop + bandH)
                lineTo(cx - 9f * d, bandTop + bandH + 16f * d)
                lineTo(cx - 4f * d, bandTop + bandH + 13f * d)
                lineTo(cx + 1f * d, bandTop + bandH + 18f * d)
                lineTo(cx + 3f * d, bandTop + bandH)
                close()
            }
            drawPath(tail, EmberDeep)
            drawPath(tail, Outline, style = stroke)
            drawRect(Ember, topLeft = Offset(rollTop.x, bandTop), size = Size(rollSize.width, bandH))
            drawRect(Outline, topLeft = Offset(rollTop.x, bandTop), size = Size(rollSize.width, bandH), style = stroke)

            listOf(knobR, h - knobR).forEach { y ->
                drawCircle(WoodLight, radius = knobR, center = Offset(cx, y))
                drawCircle(Outline, radius = knobR, center = Offset(cx, y), style = stroke)
            }
        }
    )
}
