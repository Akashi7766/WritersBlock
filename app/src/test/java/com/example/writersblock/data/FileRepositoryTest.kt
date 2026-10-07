package com.example.writersblock.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `a free name is used as is`() {
        val dir = tmp.newFolder("notes")
        assertEquals("Ideas.md", FileRepository.uniqueChild(dir, "Ideas.md").name)
    }

    @Test
    fun `creating a note never overwrites one with the same name`() {
        // Saving a second "Castle Notes" used to rewrite the first one's contents.
        val dir = tmp.newFolder("notes")
        val first = FileRepository.createFileIn(dir, "Castle Notes", "the original")!!

        val second = FileRepository.createFileIn(dir, "Castle Notes", "the newcomer")!!

        assertNotEquals(first, second)
        assertEquals("the original", first.readText())
        assertEquals("Castle Notes (2).md", second.name)
    }

    @Test
    fun `numbering skips names already taken`() {
        val dir = tmp.newFolder("notes")
        FileRepository.createFileIn(dir, "A", "")
        FileRepository.createFileIn(dir, "A", "")

        assertEquals("A (3).md", FileRepository.uniqueChild(dir, "A.md").name)
    }

    @Test
    fun `folders are numbered too`() {
        val dir = tmp.newFolder("notes")
        FileRepository.createFolderIn(dir, "Trip")

        assertEquals("Trip (2)", FileRepository.createFolderIn(dir, "Trip")!!.name)
    }
}
