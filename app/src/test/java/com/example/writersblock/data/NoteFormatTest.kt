package com.example.writersblock.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val NOTE = "Meeting notes.md"
private const val REF = "Meeting notes.assets/2026-09-25_14-30-12.png"

class NoteFormatTest {

    // ── Round-trip identity ───────────────────────────────────────────────────────────────────
    // serializeNote(parseNote(x)) == x. The editor's dirty check compares serialized content, so a
    // failure here means merely opening and saving a note would rewrite it.

    @Test
    fun `empty note round-trips`() = assertRoundTrips("")

    @Test
    fun `text only round-trips`() = assertRoundTrips("hello world\n")

    @Test
    fun `text without trailing newline round-trips`() = assertRoundTrips("hello world")

    @Test
    fun `blank lines round-trip`() = assertRoundTrips("a\n\n\nb\n")

    @Test
    fun `image alone with trailing newline round-trips`() = assertRoundTrips("![cap]($REF)\n")

    @Test
    fun `image alone without trailing newline round-trips`() = assertRoundTrips("![cap]($REF)")

    @Test
    fun `interleaved text and image round-trips`() =
        assertRoundTrips("Some prose.\n![cap]($REF)\nThe OCR text.\n")

    @Test
    fun `interleaved with image on the final line round-trips`() =
        assertRoundTrips("Some prose.\n![cap]($REF)")

    @Test
    fun `leading image round-trips`() = assertRoundTrips("![cap]($REF)\nFollowing text\n")

    @Test
    fun `consecutive images round-trip`() =
        assertRoundTrips("![a]($REF)\n![b]($REF)\n")

    @Test
    fun `image followed by blank line round-trips`() =
        assertRoundTrips("![cap]($REF)\n\nAfter a gap\n")

    @Test
    fun `empty alt text round-trips`() = assertRoundTrips("![]($REF)\n")

    // ── The guard: only this note's own assets are images ─────────────────────────────────────

    @Test
    fun `ref to another note's assets stays literal text`() {
        assertRemainsText("![x](Other note.assets/a.png)\n")
    }

    @Test
    fun `absolute path ref stays literal text`() {
        assertRemainsText("![x](/sdcard/$REF)\n")
    }

    @Test
    fun `parent traversal ref stays literal text`() {
        assertRemainsText("![x](../${assetsDirName(NOTE)}/a.png)\n")
    }

    @Test
    fun `bare assets dir with no file stays literal text`() {
        assertRemainsText("![x](${assetsDirName(NOTE)})\n")
    }

    @Test
    fun `ref with whitespace in target stays literal text`() {
        assertRemainsText("![x](${assetsDirName(NOTE)}/a b.png)\n")
    }

    @Test
    fun `a line that merely looks like a ref but is not one stays literal text`() {
        assertRemainsText("see ![the diagram](fig 3) for details\n")
    }

    @Test
    fun `a note whose name contains a space still finds its own images`() {
        // Regression: the target pattern used to forbid whitespace outright, so every reference in a
        // note called "Meeting notes.md" was read as literal text and the note never drew an image.
        val blocks = parseNote("![cap]($REF)\n", NOTE)

        assertTrue(blocks.size == 1 && blocks[0] is NoteBlock.Image)
    }

    // ── Block structure ───────────────────────────────────────────────────────────────────────

    @Test
    fun `interleaved content yields text image text in order`() {
        val blocks = parseNote("Some prose.\n![cap]($REF)\nThe OCR text.\n", NOTE)

        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is NoteBlock.Text)
        assertTrue(blocks[1] is NoteBlock.Image)
        assertTrue(blocks[2] is NoteBlock.Text)
        assertEquals("Some prose.\n", (blocks[0] as NoteBlock.Text).text)
        assertEquals("The OCR text.\n", (blocks[2] as NoteBlock.Text).text)
        assertEquals(REF, (blocks[1] as NoteBlock.Image).ref)
        assertEquals("cap", (blocks[1] as NoteBlock.Image).alt)
    }

    @Test
    fun `text blocks never start with a stray newline after an image`() {
        // Regression guard: the newline terminating an image ref belongs to the image block, not to
        // the text that follows it. Otherwise the cursor lands on a blank first line when editing.
        val blocks = parseNote("![cap]($REF)\nFollowing text\n", NOTE)

        assertEquals(2, blocks.size)
        assertEquals("Following text\n", (blocks[1] as NoteBlock.Text).text)
    }

    @Test
    fun `consecutive images produce no empty text block between them`() {
        val blocks = parseNote("![a]($REF)\n![b]($REF)\n", NOTE)

        assertEquals(2, blocks.size)
        assertTrue(blocks.all { it is NoteBlock.Image })
    }

    @Test
    fun `blocks keep their order and ids are unique`() {
        val blocks = parseNote("t1\n![a]($REF)\nt2\n![b]($REF)\nt3\n", NOTE)

        assertEquals(5, blocks.size)
        assertEquals(5, blocks.map { it.id }.toSet().size)
    }

    // ── Path helpers ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `assets dir name strips a note extension and keeps any other`() {
        assertEquals("Meeting notes.assets", assetsDirName("Meeting notes.md"))
        assertEquals("Capture_2026-09-25.txt.assets", assetsDirName("Capture_2026-09-25.txt"))
        assertEquals("no extension.assets", assetsDirName("no extension"))
    }

    @Test
    fun `a legacy txt capture does not share assets with a same-named note`() {
        // Stripping the extension unconditionally would map both of these onto "Notes.assets".
        assertFalse(assetsDirName("Notes.md") == assetsDirName("Notes.txt"))
    }

    @Test
    fun `assets directories are recognised by suffix`() {
        assertTrue(isAssetsDirName("Meeting notes.assets"))
        assertFalse(isAssetsDirName("Meeting notes.md"))
    }

    @Test
    fun `asset refs resolve against the note's directory`() {
        val note = java.io.File("/notes/Meeting notes.md")

        assertEquals(
            java.io.File("/notes/Meeting notes.assets/a.png"),
            resolveAsset(note, "Meeting notes.assets/a.png"),
        )
    }

    // ── Previews ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `snippet skips image references instead of showing raw syntax`() {
        val snippet = snippetOf("![cap]($REF)\nReal prose here.\nmore prose\n", NOTE)

        assertEquals("Real prose here. more prose", snippet)
    }

    @Test
    fun `snippet skips blank lines`() {
        assertEquals("first second", snippetOf("first\n\n\nsecond\nthird\n", NOTE))
    }

    @Test
    fun `snippet is empty for an image-only note`() {
        assertEquals("", snippetOf("![cap]($REF)\n", NOTE))
    }

    @Test
    fun `snippet is truncated to 100 characters`() {
        assertEquals(100, snippetOf("x".repeat(500), NOTE).length)
    }

    @Test
    fun `snippet works over a line sequence`() {
        assertEquals("one two", snippetOf(sequenceOf("one", "two", "three"), NOTE))
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────────────────

    private fun assertRoundTrips(content: String) {
        val serialized = serializeNote(parseNote(content, NOTE))
        assertEquals("round-trip changed the content", content, serialized)
    }

    private fun assertRemainsText(line: String) {
        val blocks = parseNote(line, NOTE)
        assertTrue(
            "expected literal text, got ${blocks.map { it::class.simpleName }}",
            blocks.size == 1 && blocks[0] is NoteBlock.Text,
        )
        assertEquals(line, serializeNote(blocks))
    }
}
