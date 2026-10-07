package com.example.writersblock.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class DocxWriterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Writes [blocks] and returns the package's entries as name to text. */
    private fun export(blocks: List<NoteBlock>, resolve: (String) -> java.io.File? = { null }): Map<String, String> {
        val bytes = ByteArrayOutputStream()
        DocxWriter.write("My Note", blocks, resolve, { 800 to 400 }, bytes)
        val entries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        return entries
    }

    @Test
    fun `the package has the parts Word needs`() {
        val entries = export(listOf(NoteBlock.Text(text = "hello")))

        assertTrue(entries.containsKey("[Content_Types].xml"))
        assertTrue(entries.containsKey("_rels/.rels"))
        assertTrue(entries.containsKey("word/document.xml"))
        assertTrue(entries.containsKey("word/_rels/document.xml.rels"))
    }

    @Test
    fun `markup characters in a note cannot break the document`() {
        val doc = export(listOf(NoteBlock.Text(text = "a & b <c> \"d\""))).getValue("word/document.xml")

        assertTrue(doc.contains("a &amp; b &lt;c&gt; &quot;d&quot;"))
        assertFalse(doc.contains("<c>"))
    }

    @Test
    fun `each line is its own paragraph and a trailing newline adds none`() {
        assertEquals(listOf("one", "", "three"), DocxWriter.paragraphsOf("one\n\nthree\n"))
    }

    @Test
    fun `a picture is embedded and referenced`() {
        val png = tmp.newFile("capture.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        val entries = export(
            listOf(NoteBlock.Image(alt = "cap", ref = "Note.assets/capture.png")),
            resolve = { png },
        )

        assertTrue(entries.containsKey("word/media/image1.png"))
        assertTrue(entries.getValue("word/document.xml").contains("r:embed=\"rIdImg1\""))
        assertTrue(entries.getValue("word/_rels/document.xml.rels").contains("Target=\"media/image1.png\""))
    }

    @Test
    fun `a missing picture becomes a visible placeholder`() {
        val doc = export(listOf(NoteBlock.Image(alt = "cap", ref = "Note.assets/gone.png"))).getValue("word/document.xml")

        assertTrue(doc.contains("picture unavailable"))
    }

    @Test
    fun `control characters are dropped`() {
        assertEquals("ab", DocxWriter.escape("a\u0001b"))
    }

    @Test
    fun `a tall screenshot is shrunk to fit one page, keeping its shape`() {
        val (cx, cy) = DocxWriter.fitToPage(1080, 2400)

        assertTrue(cy <= 7_772_400L)
        assertTrue(cx <= 5_486_400L)
        assertEquals(1080.0 / 2400.0, cx.toDouble() / cy.toDouble(), 0.01)
    }

    @Test
    fun `a small picture is not stretched`() {
        assertEquals(100L * 9_525L to 50L * 9_525L, DocxWriter.fitToPage(100, 50))
    }
}
