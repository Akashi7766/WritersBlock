package com.example.writersblock.service

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.max
import kotlin.math.min

/**
 * The mapping between the crop screen's coordinates and the screenshot's.
 *
 * The screenshot is drawn scaled to fit the screen and centred, so a point the user touches and the
 * pixel it refers to are not the same number. Getting this wrong does not crash: it crops the wrong
 * region, or a region of nothing, and the capture looks like it simply did not work. That is why it
 * lives here — a plain value class of arithmetic — instead of inline in the composable, where the only
 * way to check it would be to run it on a device, look at the picture and guess.
 *
 * Both axes are fitted, so a wide screenshot on a tall phone screen is letterboxed rather than clipped.
 *
 * @param viewWidth the crop screen's width in pixels.
 * @param viewHeight the crop screen's height in pixels.
 */
data class CropGeometry(
    val viewWidth: Float,
    val viewHeight: Float,
    val bitmapWidth: Int,
    val bitmapHeight: Int,
) {
    /**
     * How many screen pixels one screenshot pixel occupies.
     *
     * Never upscales past 1 unless both dimensions say so: `min` of the two ratios means the whole
     * screenshot always fits, which is the property the crop screen depends on — every part of the
     * image has to be reachable by a drag.
     */
    val scale: Float = min(
        viewWidth / bitmapWidth.toFloat(),
        viewHeight / bitmapHeight.toFloat(),
    )

    /** The size the screenshot is drawn at. */
    val drawnWidth: Float = bitmapWidth * scale
    val drawnHeight: Float = bitmapHeight * scale

    /** Where the drawn screenshot starts. Positive on the axis that is letterboxed. */
    val originX: Float = (viewWidth - drawnWidth) / 2f
    val originY: Float = (viewHeight - drawnHeight) / 2f

    /**
     * A point on screen to the screenshot pixel it refers to, clamped to the image.
     *
     * Takes and returns a point; both are in *different* units, which is the whole reason this class
     * exists.
     */
    fun toBitmap(screenPoint: Offset): Offset = Offset(
        ((screenPoint.x - originX) / scale).coerceIn(0f, bitmapWidth.toFloat()),
        ((screenPoint.y - originY) / scale).coerceIn(0f, bitmapHeight.toFloat()),
    )

    /** A selection in screenshot pixels to where it should be drawn, so it sits under the finger. */
    fun toScreen(bitmapRect: Rect): Rect = Rect(
        left = originX + bitmapRect.left * scale,
        top = originY + bitmapRect.top * scale,
        right = originX + bitmapRect.right * scale,
        bottom = originY + bitmapRect.bottom * scale,
    )

    /**
     * The drag that selects the whole screenshot.
     *
     * Returned in screen coordinates, ready to be used exactly like a drag the user made — selecting
     * everything is not a special case anywhere downstream, so it cannot drift from the drag path.
     */
    fun wholeImageDrag(): Pair<Offset, Offset> = Offset(originX, originY) to
            Offset(originX + drawnWidth, originY + drawnHeight)

    /**
     * The selection between two dragged points — **screen** coordinates in, screenshot pixels out.
     *
     * A drag up and to the left gives a "bottom" above its "top"; the rectangle is normalised here so
     * nothing downstream has to know which way the finger went.
     */
    fun selectionBetween(screenStart: Offset, screenEnd: Offset): Rect {
        val a = toBitmap(screenStart)
        val b = toBitmap(screenEnd)
        return Rect(
            left = min(a.x, b.x),
            top = min(a.y, b.y),
            right = max(a.x, b.x),
            bottom = max(a.y, b.y),
        )
    }
}
