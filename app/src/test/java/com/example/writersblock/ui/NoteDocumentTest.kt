package com.example.writersblock.ui

import androidx.compose.runtime.mutableStateListOf
import com.example.writersblock.data.NoteBlock
import com.example.writersblock.data.parseNote
import com.example.writersblock.data.serializeNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val NOTE = "Meeting notes.md"
private const val REF = "Meeting notes.assets/a.png"

/**
 * The editing contract behind [NoteDocument].
 *
 * These are the parts of the shared editor that decide what actually lands on disk, and they hold
 * independently of any device, so they run as plain JVM tests rather than instrumented ones. What
 * needs a device — that blocks draw in order and an image resolves — is covered separately.
 */
class NoteDocumentTest {

    // ── Starting a document ───────────────────────────────────────────────────────────────────

    @Test
    fun `seeding an empty document yields one block that serializes back to empty`() {
        val blocks = emptyNoteBlocks()

        assertEquals(1, blocks.size)
        assertEquals("", serializeNote(blocks))
    }

    @Test
    fun `seeding leaves a document that already has blocks alone`() {
        val blocks = mutableStateListOf<NoteBlock>()
        blocks.addAll(parseNote("existing\n", NOTE))

        blocks.seedIfEmpty()

        assertEquals(1, blocks.size)
        assertEquals("existing\n", serializeNote(blocks))
    }

    @Test
    fun `typing into a seeded document produces the typed text`() {
        val blocks = emptyNoteBlocks()
        val id = blocks[0].id

        blocks.replaceText(id, "hello")

        assertEquals("hello", serializeNote(blocks))
        // Identity is preserved, so the text field is not recreated and the caret stays put.
        assertEquals(id, blocks[0].id)
    }

    // ── Editing ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `editing one block leaves the others untouched`() {
        val blocks = mutableStateListOf<NoteBlock>()
        blocks.addAll(parseNote("first\n![cap]($REF)\nthird\n", NOTE))
        val idsBefore = blocks.map { it.id }

        blocks.replaceText((blocks[0] as NoteBlock.Text).id, "FIRST\n")

        assertEquals(idsBefore, blocks.map { it.id })
        assertEquals("FIRST\n![cap]($REF)\nthird\n", serializeNote(blocks))
    }

    @Test
    fun `replaceText ignores an unknown id`() {
        val blocks = mutableStateListOf<NoteBlock>()
        blocks.addAll(parseNote("text\n", NOTE))

        blocks.replaceText("no-such-block", "changed")

        assertEquals("text\n", serializeNote(blocks))
    }

    @Test
    fun `replaceText ignores an image block`() {
        // Images are addressed by the same id space as text, so a mis-targeted edit must not be able
        // to overwrite a reference with prose.
        val blocks = mutableStateListOf<NoteBlock>()
        blocks.addAll(parseNote("![cap]($REF)\n", NOTE))

        blocks.replaceText(blocks[0].id, "changed")

        assertTrue(blocks[0] is NoteBlock.Image)
        assertEquals("![cap]($REF)\n", serializeNote(blocks))
    }

    @Test
    fun `clearing every block is not a dirtying edit`() {
        // Backspacing a whole note out must leave the file byte-identical, or the 30-second auto-save
        // rewrites it and the BackHandler guard fires over nothing.
        val original = "Some prose.\n"
        val blocks = mutableStateListOf<NoteBlock>()
        blocks.addAll(parseNote(original, NOTE))

        blocks.replaceText((blocks[0] as NoteBlock.Text).id, "")

        assertEquals("", serializeNote(blocks))
    }

    // ── The dirty check ───────────────────────────────────────────────────────────────────────

    @Test
    fun `an unedited interleaved document serializes back to its original`() {
        val original = "Some prose.\n![cap]($REF)\nMore.\n"
        val blocks = mutableStateListOf<NoteBlock>()
        blocks.addAll(parseNote(original, NOTE))

        blocks.seedIfEmpty()

        assertEquals(original, serializeNote(blocks))
    }

    // ── The overlay drawer's document ─────────────────────────────────────────────────────────
    //
    // The drawer starts as scratch and gains blocks from pastes and captures. It ends up written with
    // serializeNote just like a note does, so the same rule applies: an image reference is only a
    // reference when it owns its whole line.

    @Test
    fun `appending text continues the trailing text block`() {
        // Pasting into a half-written sentence must continue it, not start a paragraph below it.
        val blocks = emptyNoteBlocks()
        val id = blocks[0].id
        blocks.replaceText(id, "half a ")

        blocks.appendText("sentence")

        assertEquals("half a sentence", serializeNote(blocks))
        assertEquals(1, blocks.size)
        // Identity preserved: rebuilding the block would recreate the field and drop the caret.
        assertEquals(id, blocks[0].id)
    }

    @Test
    fun `appending text after an image starts a new block`() {
        // The trailing block here is the image, so the text has nowhere to merge into.
        val blocks = mutableStateListOf<NoteBlock>()
        blocks.addAll(parseNote("![cap]($REF)\n", NOTE))

        blocks.appendText("pasted")

        assertEquals(2, blocks.size)
        assertTrue(blocks[1] is NoteBlock.Text)
        assertEquals("![cap]($REF)\npasted", serializeNote(blocks))
    }

    @Test
    fun `appending text to nothing creates the first block`() {
        val blocks = mutableStateListOf<NoteBlock>()

        blocks.appendText("first")

        assertEquals(1, blocks.size)
        assertEquals("first", serializeNote(blocks))
    }

    @Test
    fun `appending an image leaves a block for its text to land in`() {
        val blocks = mutableStateListOf<NoteBlock>()

        blocks.appendImage(alt = "cap", ref = "Note.assets/a.png")

        assertEquals(2, blocks.size)
        assertTrue(blocks[1] is NoteBlock.Text)
        assertEquals("", (blocks[1] as NoteBlock.Text).text)
    }

    @Test
    fun `recognised text continues the block the capture left open`() {
        // Otherwise the OCR text arrives as a sibling below the picture that the user has to find and
        // click into before they can correct a misread word.
        val blocks = mutableStateListOf<NoteBlock>()
        blocks.appendImage(alt = "cap", ref = "Note.assets/a.png")
        val openBlockId = blocks.last().id

        blocks.appendText("recognised")

        assertEquals(2, blocks.size)
        assertEquals(openBlockId, blocks.last().id)
        assertEquals("recognised", (blocks.last() as NoteBlock.Text).text)
    }

    @Test
    fun `capturing into a half-written sentence keeps the picture`() {
        // The hazard the format has no way to warn about: weld the reference onto prose and it stops
        // being a reference. Re-parsing is the test — a welded line comes back as literal text.
        val blocks = emptyNoteBlocks()
        blocks.replaceText(blocks[0].id, "Look at this: ")

        blocks.appendImage(alt = "cap", ref = REF)
        val written = serializeNote(blocks)

        assertEquals("Look at this: \n![cap]($REF)\n", written)
        assertTrue(parseNote(written, NOTE)[1] is NoteBlock.Image)
    }

    @Test
    fun `capturing into an empty document keeps the picture`() {
        // The leading empty block the drawer always has contributes no text, so the picture is the
        // first block of the note that would be written.
        val blocks = emptyNoteBlocks()

        blocks.appendImage(alt = "cap", ref = REF)

        assertTrue(parseNote(serializeNote(blocks), NOTE)[0] is NoteBlock.Image)
    }

    @Test
    fun `capturing into an empty document leaves no blank line above the picture`() {
        // The seeded empty block showed up as an orphaned "start typing" line above every first scan.
        val blocks = emptyNoteBlocks()

        blocks.appendImage(alt = "cap", ref = REF)

        assertTrue(blocks[0] is NoteBlock.Image)
        assertEquals(2, blocks.size)
    }

    @Test
    fun `two captures in a row do not leave an empty block between them`() {
        val blocks = emptyNoteBlocks()

        blocks.appendImage(alt = "one", ref = REF)
        blocks.appendImage(alt = "two", ref = REF)

        assertEquals(listOf(true, true, false), blocks.map { it is NoteBlock.Image })
    }

    @Test
    fun `a text block that already ends in a newline is not given a second one`() {
        // A blank line between the prose and the picture would come back as an empty paragraph.
        val blocks = emptyNoteBlocks()
        blocks.replaceText(blocks[0].id, "Some prose.\n")

        blocks.appendImage(alt = "cap", ref = "Note.assets/a.png")

        assertEquals("Some prose.\n![cap](Note.assets/a.png)\n", serializeNote(blocks))
    }

    @Test
    fun `removing a block drops it and leaves the rest in order`() {
        val blocks = mutableStateListOf<NoteBlock>()
        blocks.addAll(parseNote("before\n![cap]($REF)\nafter\n", NOTE))
        val imageId = (blocks[1] as NoteBlock.Image).id

        blocks.removeBlock(imageId)

        assertEquals(2, blocks.size)
        assertTrue(blocks.none { it is NoteBlock.Image })
        assertEquals("before\nafter\n", serializeNote(blocks))
    }

    @Test
    fun `removing an unknown block changes nothing`() {
        val blocks = emptyNoteBlocks()

        blocks.removeBlock("no-such-block")

        assertEquals(1, blocks.size)
    }

    @Test
    fun `a full drawer session writes back as one coherent note`() {
        // Typing, pasting, a capture, and the OCR text that came with it — the whole path end to end,
        // checked by re-parsing what would have been written.
        val blocks = mutableStateListOf<NoteBlock>()
        blocks.appendText("Shopping list")
        blocks.appendImage(alt = "cap", ref = REF)
        blocks.appendText("milk")

        val written = serializeNote(blocks)

        assertEquals("Shopping list\n![cap]($REF)\nmilk", written)
        assertTrue(parseNote(written, NOTE)[1] is NoteBlock.Image)
        assertEquals(3, parseNote(written, NOTE).size)
    }
}
