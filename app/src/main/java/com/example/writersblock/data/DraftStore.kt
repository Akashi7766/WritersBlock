package com.example.writersblock.data

import java.io.File

/**
 * Crash-safe autosave for the in-app editor: the note as it is being edited, written on every change.
 *
 * This is not the note. Saving is still an explicit act that writes the note file; a draft only exists
 * so that a crash, a kill, or a dead battery does not cost the words typed since the last Save. Opening
 * a note that has a draft restores the draft, marked unsaved.
 *
 * One file per note, keyed by the note's path (or `untitled` for a note that has no file yet). The
 * path is stored inside the draft as well, so a hash collision cannot hand one note another's words.
 */
object DraftStore {

    private const val UNTITLED = "untitled"

    fun save(dir: File, notePath: String?, blocks: List<NoteBlock>) {
        runCatching {
            dir.mkdirs()
            fileFor(dir, notePath).writeText((notePath ?: "") + "\n" + encodePending(blocks))
        }
    }

    /** The draft for [notePath], or null when there is none (or it belongs to another note). */
    fun load(dir: File, notePath: String?): List<NoteBlock>? {
        val file = fileFor(dir, notePath)
        if (!file.isFile) return null
        val content = runCatching { file.readText() }.getOrNull() ?: return null
        val newline = content.indexOf('\n')
        if (newline < 0 || content.substring(0, newline) != (notePath ?: "")) return null
        return decodePending(content.substring(newline + 1))
    }

    fun clear(dir: File, notePath: String?) {
        runCatching { fileFor(dir, notePath).delete() }
    }

    private fun fileFor(dir: File, notePath: String?): File =
        File(dir, if (notePath == null) "$UNTITLED.draft" else Integer.toHexString(notePath.hashCode()) + ".draft")
}
