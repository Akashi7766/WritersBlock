package com.example.writersblock.data

import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes a note as a Word document (.docx) with its pictures embedded.
 *
 * A .docx is a zip of a few XML parts, and a note needs only paragraphs and inline pictures, so this
 * writes the minimum package Word, Google Docs and LibreOffice all open, rather than pulling in a
 * document library for it. Pure JVM, so it is unit-tested.
 *
 * @param imageSize the picture's pixel size, or null when it cannot be read. Supplied by the caller
 *   because reading it needs `BitmapFactory`, which a JVM test does not have.
 */
object DocxWriter {

    /** 6 inches: the text width of a Letter or A4 page with one-inch margins. */
    private const val MAX_WIDTH_EMU = 5_486_400L

    /** 8.5 inches: a little under the text height of the page, so a tall screenshot still fits on one. */
    private const val MAX_HEIGHT_EMU = 7_772_400L

    /** EMUs per pixel at 96 dpi. */
    private const val EMU_PER_PX = 9_525L

    fun write(
        title: String,
        blocks: List<NoteBlock>,
        resolveImage: (String) -> File?,
        imageSize: (File) -> Pair<Int, Int>?,
        out: OutputStream,
    ) {
        val images = mutableListOf<Pair<String, File>>() // zip name to source file
        val body = StringBuilder()

        body.append(paragraph(run(title, bold = true, halfPoints = 36)))

        blocks.forEach { block ->
            when (block) {
                is NoteBlock.Text -> paragraphsOf(block.text).forEach { line ->
                    body.append(paragraph(if (line.isEmpty()) "" else run(line)))
                }
                is NoteBlock.Image -> {
                    val file = resolveImage(block.ref)?.takeIf { it.isFile }
                    val size = file?.let(imageSize)
                    if (file == null || size == null || size.first <= 0 || size.second <= 0) {
                        body.append(paragraph(run("[picture unavailable: ${block.alt}]", italic = true)))
                    } else {
                        val n = images.size + 1
                        val ext = file.extension.lowercase().ifEmpty { "png" }
                        images += "image$n.$ext" to file
                        body.append(paragraph(drawing(n, "rIdImg$n", size.first, size.second)))
                    }
                }
            }
        }

        ZipOutputStream(out).use { zip ->
            zip.entry("[Content_Types].xml", contentTypes())
            zip.entry("_rels/.rels", ROOT_RELS)
            zip.entry("word/document.xml", document(body.toString()))
            zip.entry("word/_rels/document.xml.rels", documentRels(images.map { it.first }))
            images.forEach { (name, file) ->
                zip.putNextEntry(ZipEntry("word/media/$name"))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /** A text block as paragraphs: one per line, without the empty one a trailing newline implies. */
    internal fun paragraphsOf(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val lines = text.split('\n')
        return if (text.endsWith('\n')) lines.dropLast(1) else lines
    }

    /** XML-escapes [text] and drops the control characters XML 1.0 cannot carry at all. */
    internal fun escape(text: String): String = buildString {
        text.forEach { c ->
            when {
                c == '&' -> append("&amp;")
                c == '<' -> append("&lt;")
                c == '>' -> append("&gt;")
                c == '"' -> append("&quot;")
                c == '\t' -> append(c)
                c < ' ' -> Unit
                else -> append(c)
            }
        }
    }

    private fun paragraph(content: String) = "<w:p>$content</w:p>"

    private fun run(text: String, bold: Boolean = false, italic: Boolean = false, halfPoints: Int? = null): String {
        val props = buildString {
            if (bold) append("<w:b/>")
            if (italic) append("<w:i/>")
            if (halfPoints != null) append("<w:sz w:val=\"$halfPoints\"/>")
        }
        val rPr = if (props.isEmpty()) "" else "<w:rPr>$props</w:rPr>"
        return "<w:r>$rPr<w:t xml:space=\"preserve\">${escape(text)}</w:t></w:r>"
    }

    /** The picture's size in EMUs, shrunk (never stretched) to fit the page's width and height. */
    internal fun fitToPage(widthPx: Int, heightPx: Int): Pair<Long, Long> {
        var cx = widthPx * EMU_PER_PX
        var cy = heightPx * EMU_PER_PX
        if (cx > MAX_WIDTH_EMU) {
            cy = cy * MAX_WIDTH_EMU / cx
            cx = MAX_WIDTH_EMU
        }
        if (cy > MAX_HEIGHT_EMU) {
            cx = cx * MAX_HEIGHT_EMU / cy
            cy = MAX_HEIGHT_EMU
        }
        return cx to cy
    }

    private fun drawing(id: Int, relId: String, widthPx: Int, heightPx: Int): String {
        val (cx, cy) = fitToPage(widthPx, heightPx)
        return "<w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">" +
            "<wp:extent cx=\"$cx\" cy=\"$cy\"/><wp:docPr id=\"$id\" name=\"Picture $id\"/>" +
            "<a:graphic xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\">" +
            "<a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
            "<pic:pic xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
            "<pic:nvPicPr><pic:cNvPr id=\"$id\" name=\"Picture $id\"/><pic:cNvPicPr/></pic:nvPicPr>" +
            "<pic:blipFill><a:blip r:embed=\"$relId\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>" +
            "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm>" +
            "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic>" +
            "</a:graphicData></a:graphic></wp:inline></w:drawing></w:r>"
    }

    private fun document(body: String) =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" " +
            "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" " +
            "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\">" +
            "<w:body>$body<w:sectPr/></w:body></w:document>"

    private fun documentRels(imageNames: List<String>) =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            imageNames.mapIndexed { i, name ->
                "<Relationship Id=\"rIdImg${i + 1}\" " +
                    "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" " +
                    "Target=\"media/$name\"/>"
            }.joinToString("") +
            "</Relationships>"

    private fun contentTypes() =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Default Extension=\"png\" ContentType=\"image/png\"/>" +
            "<Default Extension=\"jpg\" ContentType=\"image/jpeg\"/>" +
            "<Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/>" +
            "<Default Extension=\"webp\" ContentType=\"image/webp\"/>" +
            "<Override PartName=\"/word/document.xml\" " +
            "ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
            "</Types>"

    private const val ROOT_RELS =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" " +
            "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" " +
            "Target=\"word/document.xml\"/></Relationships>"

    private fun ZipOutputStream.entry(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }
}
