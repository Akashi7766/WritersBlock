package com.example.writersblock.data

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * A note is an ordered list of blocks: runs of text interleaved with captured images.
 *
 * The on-disk form is an ordinary text file, so notes stay greppable and portable. Images live in a
 * sibling `<Name>.assets/` directory and are referenced one per line:
 *
 * ```
 * Some prose the user wrote.
 * ![capture 14:30](Meeting notes.assets/2026-09-25_14-30-12.png)
 * The OCR text that came with that capture.
 * ```
 *
 * The reference line is storage only — [parseNote] turns it into a [NoteBlock.Image] and the UI
 * renders the picture in position, never the raw syntax.
 */
sealed interface NoteBlock {
    /** Stable identity for Compose keys. Not persisted; regenerated on every parse. */
    val id: String

    /** A run of literal text, including any newlines it contains. */
    data class Text(
        override val id: String = newBlockId(),
        val text: String,
    ) : NoteBlock

    /**
     * A captured image living in the note's own assets directory.
     *
     * [trailingNewline] exists only so a round-trip is byte-identical. An image reference occupies a
     * whole line, so it owns the newline that terminates that line — and a reference on the final
     * line of a file that has no trailing newline owns none. Without this, saving a note that ends in
     * an image would silently append a newline and make the editor think the file had changed.
     */
    data class Image(
        override val id: String = newBlockId(),
        val alt: String,
        val ref: String,
        val trailingNewline: Boolean = true,
    ) : NoteBlock
}

/** An image reference: the alt text and the relative path it points at. */
data class ImageRef(val alt: String, val ref: String)

const val ASSETS_SUFFIX = ".assets"

private fun newBlockId(): String = UUID.randomUUID().toString()

/**
 * Matches a whole line of the form `![alt](target)`. Alt text may be empty.
 *
 * The target may contain whitespace: it has to, because a note called `Meeting notes.md` owns an
 * assets directory called `Meeting notes.assets`. Which whitespace is acceptable is decided by
 * [isImageRefFor], not by this pattern.
 */
private val IMAGE_REF = Regex("""^!\[([^\]]*)\]\(([^)]+)\)$""")

/** Extensions whose note file name maps to the bare `<Name>.assets` directory. */
private val NOTE_EXTENSIONS = setOf("md")

/**
 * `Note.md` -> `Note.assets`. A file whose extension is not a note extension keeps it, so
 * `Capture_2026-09-25.txt` -> `Capture_2026-09-25.txt.assets`.
 *
 * Keeping the extension matters: stripping it unconditionally would map both `Notes.md` and a
 * legacy `Notes.txt` onto the same `Notes.assets`, and the two would silently share captures.
 */
fun assetsDirName(noteFileName: String): String {
    val dot = noteFileName.lastIndexOf('.')
    if (dot <= 0) return noteFileName + ASSETS_SUFFIX
    val extension = noteFileName.substring(dot + 1).lowercase()
    if (extension !in NOTE_EXTENSIONS) return noteFileName + ASSETS_SUFFIX
    return noteFileName.substring(0, dot) + ASSETS_SUFFIX
}

/** True for directories like `Note.assets`, which hold captures and are never listed as items. */
fun isAssetsDirName(name: String): Boolean = name.endsWith(ASSETS_SUFFIX)

/**
 * Whether [target] points into [noteFileName]'s own assets directory.
 *
 * This is the guard that makes escaping unnecessary: a line is an image reference only if it targets
 * this note's assets folder. Any other line that merely looks like `![x](y)` — an absolute path, a
 * `..` traversal, or a path into some other note's assets — stays literal text.
 */
fun isImageRefFor(target: String, noteFileName: String): Boolean {
    if (target.startsWith('/')) return false
    if (target.contains("..")) return false

    val slash = target.indexOf('/')
    if (slash <= 0) return false
    if (target.substring(0, slash) != assetsDirName(noteFileName)) return false

    // Refs are always written with generated names, so a space in the file part means this is prose
    // that happens to look like a reference — `![the diagram](fig 3)`. A space before the slash is
    // fine, and necessary: it is part of the note's own name.
    val fileName = target.substring(slash + 1)
    return fileName.isNotEmpty() && fileName.none { it.isWhitespace() }
}

/** The alt/ref pair if [line] is an image reference belonging to this note, else null. */
fun imageRefIn(line: String, noteFileName: String): ImageRef? {
    val match = IMAGE_REF.matchEntire(line) ?: return null
    val target = match.groupValues[2]
    if (!isImageRefFor(target, noteFileName)) return null
    return ImageRef(alt = match.groupValues[1], ref = target)
}

/** Whether [line] is an image reference belonging to this note. */
fun isImageRefLine(line: String, noteFileName: String): Boolean =
    imageRefIn(line, noteFileName) != null

/**
 * Splits note [content] into blocks.
 *
 * [serializeNote] of the result is byte-identical to [content] — that identity is what lets the
 * editor use a simple `content != originalContent` dirty check, so it is pinned by tests.
 *
 * Pass the note's file name (not its path) as [noteFileName] so image references can be validated
 * without touching the filesystem.
 */
fun parseNote(content: String, noteFileName: String): List<NoteBlock> {
    if (content.isEmpty()) return emptyList()

    val lines = content.split('\n')
    val lastIndex = lines.lastIndex
    val blocks = mutableListOf<NoteBlock>()
    val buffer = StringBuilder()

    fun flushText() {
        if (buffer.isNotEmpty()) {
            blocks += NoteBlock.Text(text = buffer.toString())
            buffer.clear()
        }
    }

    lines.forEachIndexed { index, line ->
        val hasNewline = index != lastIndex
        val ref = imageRefIn(line, noteFileName)

        if (ref != null) {
            flushText()
            blocks += NoteBlock.Image(alt = ref.alt, ref = ref.ref, trailingNewline = hasNewline)
        } else {
            buffer.append(line)
            if (hasNewline) buffer.append('\n')
        }
    }

    flushText()
    return blocks
}

/** Renders [blocks] back to the on-disk text form. Inverse of [parseNote]. */
fun serializeNote(blocks: List<NoteBlock>): String = buildString {
    blocks.forEach { block ->
        when (block) {
            is NoteBlock.Text -> append(block.text)
            is NoteBlock.Image -> {
                append("![").append(block.alt).append("](").append(block.ref).append(')')
                if (block.trailingNewline) append('\n')
            }
        }
    }
}

/**
 * Preview text for list rows: the first [maxLines] non-blank prose lines, joined and truncated.
 *
 * Image references are skipped so previews never show raw `![…](…)` syntax.
 */
fun snippetOf(lines: Sequence<String>, noteFileName: String, maxLines: Int = 2): String =
    lines.filter { !isImageRefLine(it, noteFileName) }
        .filter { it.isNotBlank() }
        .take(maxLines)
        .joinToString(" ")
        .trim()
        .take(100)

/** [snippetOf] over an in-memory string. */
fun snippetOf(content: String, noteFileName: String, maxLines: Int = 2): String =
    snippetOf(content.lineSequence(), noteFileName, maxLines)

/** The assets directory that sits beside [noteFile]. Not created; callers create on demand. */
fun assetsDirFor(noteFile: File): File = File(noteFile.parentFile, assetsDirName(noteFile.name))

/** Resolves an image [ref] against the directory containing [noteFile]. */
fun resolveAsset(noteFile: File, ref: String): File = File(noteFile.parentFile, ref)

/** Human-readable caption for a capture, used as its alt text. */
fun captionForCapture(): String =
    "capture " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
