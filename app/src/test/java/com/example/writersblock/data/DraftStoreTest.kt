package com.example.writersblock.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DraftStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun texts(blocks: List<NoteBlock>?) = blocks?.map { (it as NoteBlock.Text).text }

    @Test
    fun `a draft comes back exactly as it was saved`() {
        val dir = tmp.newFolder("drafts")
        DraftStore.save(dir, "/notes/a.md", listOf(NoteBlock.Text(text = "half a sentence\nand a line")))

        assertEquals(listOf("half a sentence\nand a line"), texts(DraftStore.load(dir, "/notes/a.md")))
    }

    @Test
    fun `each note has its own draft`() {
        val dir = tmp.newFolder("drafts")
        DraftStore.save(dir, "/notes/a.md", listOf(NoteBlock.Text(text = "a")))
        DraftStore.save(dir, "/notes/b.md", listOf(NoteBlock.Text(text = "b")))
        DraftStore.save(dir, null, listOf(NoteBlock.Text(text = "untitled")))

        assertEquals(listOf("a"), texts(DraftStore.load(dir, "/notes/a.md")))
        assertEquals(listOf("b"), texts(DraftStore.load(dir, "/notes/b.md")))
        assertEquals(listOf("untitled"), texts(DraftStore.load(dir, null)))
    }

    @Test
    fun `a cleared draft is gone`() {
        val dir = tmp.newFolder("drafts")
        DraftStore.save(dir, "/notes/a.md", listOf(NoteBlock.Text(text = "a")))

        DraftStore.clear(dir, "/notes/a.md")

        assertNull(DraftStore.load(dir, "/notes/a.md"))
    }

    @Test
    fun `no draft means null, not an empty document`() {
        assertNull(DraftStore.load(tmp.newFolder("drafts"), "/notes/never.md"))
    }
}
