package com.example.writersblock.data

import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.File
import java.io.OutputStream

/**
 * Writes a note as an A4 PDF with Android's own [PdfDocument]: a title, then the prose and pictures in
 * order, flowing onto as many pages as it takes. Text is wrapped with [StaticLayout] and laid out one
 * line at a time, so a long paragraph breaks across pages instead of being cut off.
 */
object PdfWriter {

    private const val PAGE_W = 595 // A4 in points
    private const val PAGE_H = 842
    private const val MARGIN = 54

    fun write(title: String, blocks: List<NoteBlock>, resolveImage: (String) -> File?, out: OutputStream) {
        val doc = PdfDocument()
        val width = PAGE_W - 2 * MARGIN
        val bottom = PAGE_H - MARGIN

        val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.SERIF
            textSize = 12f
        }
        val heading = TextPaint(body).apply {
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            textSize = 20f
        }

        var pageNumber = 0
        var page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, ++pageNumber).create())
        var y = MARGIN.toFloat()

        fun newPage() {
            doc.finishPage(page)
            page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, ++pageNumber).create())
            y = MARGIN.toFloat()
        }

        fun drawText(text: String, paint: TextPaint, spaceAfter: Float) {
            if (text.isEmpty()) return
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.25f)
                .build()
            for (line in 0 until layout.lineCount) {
                val lineHeight = (layout.getLineBottom(line) - layout.getLineTop(line)).toFloat()
                if (y + lineHeight > bottom) newPage()
                val baseline = y + (layout.getLineBaseline(line) - layout.getLineTop(line))
                val segment = text.substring(layout.getLineStart(line), layout.getLineEnd(line)).trimEnd('\n')
                page.canvas.drawText(segment, MARGIN.toFloat(), baseline, paint)
                y += lineHeight
            }
            y += spaceAfter
        }

        drawText(title, heading, 12f)

        blocks.forEach { block ->
            when (block) {
                is NoteBlock.Text -> drawText(block.text.trimEnd('\n'), body, 6f)
                is NoteBlock.Image -> {
                    val file = resolveImage(block.ref)?.takeIf { it.isFile }
                    val bitmap = file?.let { BitmapFactory.decodeFile(it.absolutePath) }
                    if (bitmap == null) {
                        drawText("[picture unavailable: ${block.alt}]", body, 6f)
                    } else {
                        // Fit to the text width, and never taller than one page.
                        var w = width.toFloat()
                        var h = bitmap.height * w / bitmap.width
                        val maxH = (bottom - MARGIN).toFloat()
                        if (h > maxH) {
                            w = w * maxH / h
                            h = maxH
                        }
                        if (y + h > bottom) newPage()
                        page.canvas.drawBitmap(
                            bitmap,
                            null,
                            Rect(MARGIN, y.toInt(), (MARGIN + w).toInt(), (y + h).toInt()),
                            Paint(Paint.FILTER_BITMAP_FLAG),
                        )
                        bitmap.recycle()
                        y += h + 10f
                    }
                }
            }
        }

        doc.finishPage(page)
        doc.writeTo(out)
        doc.close()
    }
}
