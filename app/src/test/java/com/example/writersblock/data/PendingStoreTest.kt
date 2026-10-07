package com.example.writersblock.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val NOTE = "Meeting notes.md"

/**
 * The overlay drawer's draft, as it survives a round trip through storage.
 *
 * The drawer holds unsaved work that exists nowhere else — prose typed into a floating window and
 * pictures staged on disk — so the codec is the one place where a bug loses something the user cannot
 * get back. These are plain JVM tests: the codec is pure string work and none of it needs a device.
 */
class PendingStoreTest {

    private fun roundTrip(blocks: List<NoteBlock>) = decodePending(encodePending(blocks))

    /**
     * The blocks' content, without their identities.
     *
     * [NoteBlock.id] is generated fresh every time a block is built, so a decoded block never equals
     * the one that was encoded even when it holds exactly the same thing. Identity is checked
     * separately, where it actually matters.
     */
    private fun List<NoteBlock>.content(): List<String> = map { block ->
        when (block) {
            is NoteBlock.Text -> "text:${block.text}"
            is NoteBlock.Image -> "image:${block.ref}|${block.alt}"
        }
    }

    private fun assertSameContent(expected: List<NoteBlock>, actual: List<NoteBlock>) {
        assertEquals(expected.content(), actual.content())
    }

    // ── Round trips ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `an empty document round trips`() {
        assertEquals(emptyList<NoteBlock>(), roundTrip(emptyList()))
    }

    @Test
    fun `a single text block round trips`() {
        val blocks = listOf(NoteBlock.Text(text = "hello"))

        assertSameContent(blocks, roundTrip(blocks))
    }

    @Test
    fun `text keeps its newlines`() {
        val blocks = listOf(NoteBlock.Text(text = "first line\nsecond line\n\nfourth"))

        val decoded = roundTrip(blocks)

        assertSameContent(blocks, decoded)
        assertEquals("first line\nsecond line\n\nfourth", (decoded[0] as NoteBlock.Text).text)
    }

    @Test
    fun `text that looks like the wire format round trips unchanged`() {
        // The whole reason the format is length-prefixed: every one of these is legitimate prose, and
        // a line-based format would have to escape it. The field after this one is the real test — if
        // the decoder read any of this as structure, that field would come back wrong.
        val awkward = "T\nI\nWB1\n5\nabc\n0\n\n"
        val blocks = listOf(
            NoteBlock.Text(text = awkward),
            NoteBlock.Text(text = "after"),
        )

        val decoded = roundTrip(blocks)

        assertEquals(awkward, (decoded[0] as NoteBlock.Text).text)
        assertEquals("after", (decoded[1] as NoteBlock.Text).text)
    }

    @Test
    fun `a block whose text is only a digit round trips`() {
        // "3" as text is indistinguishable from a length prefix unless the length is read first.
        val blocks = listOf(
            NoteBlock.Text(text = "3"),
            NoteBlock.Text(text = "x"),
        )

        assertSameContent(blocks, roundTrip(blocks))
    }

    @Test
    fun `an empty text block round trips`() {
        val blocks = listOf(NoteBlock.Text(text = ""))

        val decoded = roundTrip(blocks)

        assertEquals(1, decoded.size)
        assertEquals("", (decoded[0] as NoteBlock.Text).text)
    }

    @Test
    fun `an image round trips with its reference and alt text`() {
        val blocks = listOf(NoteBlock.Image(alt = "capture", ref = "/staging/abc.png"))

        assertSameContent(blocks, roundTrip(blocks))
    }

    @Test
    fun `an image's alt text is not confused with its reference`() {
        // ref is written before alt. A decoder that swapped them would still round trip on any pair
        // of equal strings, so this uses a pair that differs.
        val blocks = listOf(NoteBlock.Image(alt = "second", ref = "first"))

        val decoded = roundTrip(blocks)

        assertEquals("first", (decoded[0] as NoteBlock.Image).ref)
        assertEquals("second", (decoded[0] as NoteBlock.Image).alt)
    }

    @Test
    fun `an image with an empty alt round trips`() {
        val blocks = listOf(NoteBlock.Image(alt = "", ref = "/staging/abc.png"))

        assertSameContent(blocks, roundTrip(blocks))
    }

    @Test
    fun `an interleaved document keeps its order`() {
        val blocks = listOf(
            NoteBlock.Text(text = "before\n"),
            NoteBlock.Image(alt = "one", ref = "/staging/1.png"),
            NoteBlock.Text(text = ""),
            NoteBlock.Image(alt = "two", ref = "/staging/2.png"),
            NoteBlock.Text(text = "after"),
        )

        val decoded = roundTrip(blocks)

        assertSameContent(blocks, decoded)
        assertTrue(decoded[0] is NoteBlock.Text)
        assertTrue(decoded[1] is NoteBlock.Image)
        assertTrue(decoded[3] is NoteBlock.Image)
        assertTrue(decoded[4] is NoteBlock.Text)
    }

    @Test
    fun `a decoded draft writes back as the note it was`() {
        // The codec only has to be lossless with respect to the blocks, but the blocks are what the
        // note is written from — so the real guarantee is that the note text survives the detour.
        val blocks = parseNote("Some prose.\n![cap](Note.assets/a.png)\nMore.\n", NOTE)
        val decoded = decodePending(encodePending(blocks))

        assertEquals("Some prose.\n![cap](Note.assets/a.png)\nMore.\n", serializeNote(decoded))
    }

    @Test
    fun `text outside the basic plane round trips`() {
        // Length is counted in chars on both sides. Anything counted in bytes would split a surrogate
        // pair and corrupt the block after it.
        val blocks = listOf(
            NoteBlock.Text(text = "note 📝 🇯🇵"),
            NoteBlock.Text(text = "after"),
        )

        val decoded = roundTrip(blocks)

        assertSameContent(blocks, decoded)
        assertEquals("after", (decoded[1] as NoteBlock.Text).text)
    }

    @Test
    fun `a document with no blocks at all is written and read as one`() {
        assertTrue(roundTrip(emptyList()).isEmpty())
    }

    // ── Damaged input ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `nothing decodes to an empty document rather than a crash`() {
        assertEquals(emptyList<NoteBlock>(), decodePending(""))
    }

    @Test
    fun `a document that is not this app's decodes to an empty document`() {
        // A pref read by an older build, or anything else that happened to be at the key.
        assertEquals(emptyList<NoteBlock>(), decodePending("{\"some\":\"json\"}"))
    }

    @Test
    fun `a truncated header decodes to an empty document`() {
        assertEquals(emptyList<NoteBlock>(), decodePending("WB1"))
        assertEquals(emptyList<NoteBlock>(), decodePending("WB"))
    }

    @Test
    fun `a header with no newline decodes to an empty document`() {
        assertEquals(emptyList<NoteBlock>(), decodePending("WB1T3\nabc\n"))
    }

    @Test
    fun `a header alone decodes to an empty document`() {
        assertEquals(emptyList<NoteBlock>(), decodePending("WB1\n"))
    }

    @Test
    fun `a truncated write keeps the blocks that were complete`() {
        // The process died mid-write. Whatever was already flushed is readable, and the half-written
        // block at the end must not throw.
        val encoded = encodePending(
            listOf(
                NoteBlock.Text(text = "kept"),
                NoteBlock.Text(text = "lost"),
            )
        )

        val decoded = decodePending(encoded.substring(0, encoded.length - 6))

        assertEquals("kept", (decoded[0] as NoteBlock.Text).text)
    }

    @Test
    fun `a field longer than what was written decodes to an empty field`() {
        // The length claims five characters, the file holds two. Trusting the length would run off the
        // end of the string.
        val decoded = decodePending("WB1\nT5\nab\n")

        assertTrue(decoded.isNotEmpty())
        assertEquals("", (decoded[0] as NoteBlock.Text).text)
    }

    @Test
    fun `a block type from a newer version keeps everything before it`() {
        // Forward compatibility: a future build's block type is skipped, not crashed on, and not
        // allowed to poison the blocks that this build does understand.
        val decoded = decodePending("WB1\nT2\nhi\nZ9\nwhatever\n")

        assertEquals(1, decoded.size)
        assertEquals("hi", (decoded[0] as NoteBlock.Text).text)
    }

    // ── Committing to a note ──────────────────────────────────────────────────────────────────

    @Test
    fun `materializing rewrites resolved references`() {
        val blocks = listOf(NoteBlock.Image(alt = "cap", ref = "/staging/abc.png"))

        val result = materializeBlocks(blocks) { ref ->
            if (ref == "/staging/abc.png") "Note.assets/abc.png" else null
        }

        assertSameContent(
            listOf(NoteBlock.Image(alt = "cap", ref = "Note.assets/abc.png")),
            result,
        )
    }

    @Test
    fun `materializing keeps the block it rewrote, so open editors are not rebuilt`() {
        val blocks = listOf(NoteBlock.Image(alt = "cap", ref = "/staging/abc.png"))

        val result = materializeBlocks(blocks) { "Note.assets/abc.png" }

        assertEquals(blocks[0].id, result[0].id)
    }

    @Test
    fun `materializing drops an image whose file could not be moved`() {
        // A reference to a file that is not there renders as a permanently broken image box. Losing
        // the picture is the lesser failure.
        val blocks = listOf(
            NoteBlock.Text(text = "before"),
            NoteBlock.Image(alt = "gone", ref = "/staging/missing.png"),
            NoteBlock.Text(text = "after"),
        )

        val result = materializeBlocks(blocks) { null }

        assertEquals(2, result.size)
        assertTrue(result.none { it is NoteBlock.Image })
        assertEquals("before", (result[0] as NoteBlock.Text).text)
        assertEquals("after", (result[1] as NoteBlock.Text).text)
    }

    @Test
    fun `materializing leaves text alone`() {
        val blocks = listOf(NoteBlock.Text(text = "![not really](a.png)\n"))

        assertSameContent(blocks, materializeBlocks(blocks) { "should not be called" })
    }

    @Test
    fun `materializing an empty document yields an empty document`() {
        assertTrue(materializeBlocks(emptyList()) { "x" }.isEmpty())
    }

    @Test
    fun `a materialized document round trips back through the codec`() {
        // Resolved refs are note-relative, and those are still just strings as far as the codec is
        // concerned — the staging path is never special-cased, so a committing draft is not a special
        // case either.
        val resolved = materializeBlocks(
            listOf(NoteBlock.Image(alt = "cap", ref = "/staging/abc.png"))
        ) { "Note.assets/abc.png" }

        assertSameContent(resolved, roundTrip(resolved))
    }

    // ── Appending to an existing note ─────────────────────────────────────────────────────────

    @Test
    fun `appending to an empty note writes just the addition`() {
        assertEquals("new", joinForAppend("", "new"))
    }

    @Test
    fun `appending nothing leaves the note as it was`() {
        assertEquals("existing\n", joinForAppend("existing\n", ""))
    }

    @Test
    fun `appending to a note that ends in a newline adds no blank line`() {
        assertEquals("existing\nnew", joinForAppend("existing\n", "new"))
    }

    @Test
    fun `appending to a note that does not end in a newline starts a new line`() {
        // Without this the addition is welded onto the last sentence.
        assertEquals("existing\nnew", joinForAppend("existing", "new"))
    }

    @Test
    fun `an appended image reference lands on its own line`() {
        // A reference only counts as one when it occupies a whole line; joined to prose it is
        // literal text that would be displayed as-is.
        val joined = joinForAppend("Some prose.", "![cap](Note.assets/a.png)")

        assertTrue(joined.endsWith("\n![cap](Note.assets/a.png)"))
    }

    @Test
    fun `appending to an empty note keeps a leading blank line out`() {
        // An empty note is the empty string, not a single newline, so nothing is inserted before the
        // addition.
        assertEquals("![cap](Note.assets/a.png)", joinForAppend("", "![cap](Note.assets/a.png)"))
    }
}
