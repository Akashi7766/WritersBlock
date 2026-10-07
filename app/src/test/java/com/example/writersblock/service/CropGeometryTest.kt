package com.example.writersblock.service

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The crop mapping.
 *
 * These are the numbers that decide which part of the screen ends up in the note. Every one of them is
 * invisible when it is wrong — the crop still succeeds, it just contains the wrong pixels — so they are
 * checked here rather than by looking at a capture and hoping it looks right.
 */
class CropGeometryTest {

    /** A typical phone: the screenshot is the same shape as the view, so nothing is letterboxed. */
    private val phone = CropGeometry(
        viewWidth = 1080f,
        viewHeight = 2000f,
        bitmapWidth = 1080,
        bitmapHeight = 2000,
    )

    @Test
    fun `a same-shape screenshot fills the view exactly`() {
        assertEquals(1f, phone.scale, 0.0001f)
        assertEquals(0f, phone.originX, 0.0001f)
        assertEquals(0f, phone.originY, 0.0001f)
        assertEquals(1080f, phone.drawnWidth, 0.0001f)
        assertEquals(2000f, phone.drawnHeight, 0.0001f)
    }

    @Test
    fun `a wider screenshot is letterboxed top and bottom and centred`() {
        // A 2400x1080 landscape screenshot in a 1080x2000 portrait view.
        val geometry = CropGeometry(1080f, 2000f, 2400, 1080)

        assertEquals(0.45f, geometry.scale, 0.0001f)
        assertEquals(1080f, geometry.drawnWidth, 0.0001f)
        assertEquals(486f, geometry.drawnHeight, 0.0001f)
        assertEquals(0f, geometry.originX, 0.0001f)
        // (2000 - 486) / 2
        assertEquals(757f, geometry.originY, 0.0001f)
    }

    @Test
    fun `a taller screenshot is letterboxed left and right and centred`() {
        val geometry = CropGeometry(1080f, 1000f, 1000, 2000)

        assertEquals(0.5f, geometry.scale, 0.0001f)
        assertEquals(500f, geometry.drawnWidth, 0.0001f)
        assertEquals(1000f, geometry.drawnHeight, 0.0001f)
        assertEquals(290f, geometry.originX, 0.0001f)
        assertEquals(0f, geometry.originY, 0.0001f)
    }

    @Test
    fun `dragging the drawn image corner to corner selects the whole screenshot`() {
        val geometry = CropGeometry(1080f, 2000f, 2400, 1080)

        val selection = geometry.selectionBetween(
            Offset(geometry.originX, geometry.originY),
            Offset(geometry.originX + geometry.drawnWidth, geometry.originY + geometry.drawnHeight),
        )

        assertEquals(0f, selection.left, 0.01f)
        assertEquals(0f, selection.top, 0.01f)
        assertEquals(2400f, selection.right, 0.01f)
        assertEquals(1080f, selection.bottom, 0.01f)
    }

    @Test
    fun `select-all produces a drag that selects the whole screenshot`() {
        val geometry = CropGeometry(1080f, 2000f, 2400, 1080)

        val (from, to) = geometry.wholeImageDrag()
        val selection = geometry.selectionBetween(from, to)

        assertEquals(0f, selection.left, 0.01f)
        assertEquals(0f, selection.top, 0.01f)
        assertEquals(2400f, selection.right, 0.01f)
        assertEquals(1080f, selection.bottom, 0.01f)
    }

    @Test
    fun `the letterboxed margin maps to the edge of the image rather than past it`() {
        val geometry = CropGeometry(1080f, 2000f, 2400, 1080)

        // A touch above the drawn image, in the black bar.
        val inTheBar = geometry.toBitmap(Offset(500f, 10f))
        assertEquals(0f, inTheBar.y, 0.01f)

        // And one below it.
        val belowTheImage = geometry.toBitmap(Offset(500f, 1999f))
        assertEquals(1080f, belowTheImage.y, 0.01f)
    }

    @Test
    fun `a drag up and to the left is normalised into a positive rectangle`() {
        val selection = phone.selectionBetween(Offset(800f, 1500f), Offset(200f, 300f))

        assertEquals(200f, selection.left, 0.01f)
        assertEquals(300f, selection.top, 0.01f)
        assertEquals(800f, selection.right, 0.01f)
        assertEquals(1500f, selection.bottom, 0.01f)
    }

    @Test
    fun `a selection maps back to where it was drawn`() {
        val geometry = CropGeometry(1080f, 2000f, 2400, 1080)
        val original = Rect(100f, 200f, 900f, 600f)

        // Drawn where it should be, then dragged over at exactly those screen points: the full trip
        // the crop screen makes, screenshot pixels to screen coordinates and back.
        val onScreen = geometry.toScreen(original)
        val roundTripped = geometry.selectionBetween(
            Offset(onScreen.left, onScreen.top),
            Offset(onScreen.right, onScreen.bottom),
        )

        assertEquals(original.left, roundTripped.left, 0.01f)
        assertEquals(original.top, roundTripped.top, 0.01f)
        assertEquals(original.right, roundTripped.right, 0.01f)
        assertEquals(original.bottom, roundTripped.bottom, 0.01f)
    }

    @Test
    fun `a point in the middle of the screen maps to the middle of the image`() {
        val centre = phone.toBitmap(Offset(540f, 1000f))

        assertEquals(540f, centre.x, 0.01f)
        assertEquals(1000f, centre.y, 0.01f)
    }

    @Test
    fun `the whole image is always reachable, never clipped by the view`() {
        val geometry = CropGeometry(1080f, 2000f, 2400, 1080)

        // The fitted image has to fit: otherwise part of the screenshot could not be dragged over.
        assertTrue(geometry.drawnWidth <= geometry.viewWidth + 0.01f)
        assertTrue(geometry.drawnHeight <= geometry.viewHeight + 0.01f)
        assertTrue(geometry.originX >= 0f)
        assertTrue(geometry.originY >= 0f)
    }
}
