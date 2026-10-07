package com.example.writersblock.data

import android.content.Context
import java.io.File

data class NoteItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val lastModified: Long,
    val contentSnippet: String = ""
)

object FileRepository {

    /** Extensions listed and opened as notes. `.txt` is kept so earlier captures still appear. */
    val NOTE_EXTENSIONS = listOf("md", "txt")

    /** Extension given to newly created notes. */
    const val DEFAULT_NOTE_EXTENSION = "md"

    fun isNoteFile(file: File): Boolean =
        !file.isDirectory && NOTE_EXTENSIONS.any { file.name.endsWith(".$it") }

    fun getNotesRoot(context: Context): File {
        val root = File(context.filesDir, "notes")
        if (!root.exists()) {
            root.mkdirs()
        }
        return root
    }

    fun listItems(context: Context, folderPath: String? = null): List<NoteItem> {
        val folder = if (folderPath.isNullOrEmpty()) {
            getNotesRoot(context)
        } else {
            File(folderPath)
        }

        if (!folder.exists() || !folder.isDirectory) return emptyList()

        val files = folder.listFiles() ?: return emptyList()
        return files
            .filterNot { it.isDirectory && isAssetsDirName(it.name) }
            .map { file ->
                NoteItem(
                    name = file.name,
                    path = file.absolutePath,
                    isDirectory = file.isDirectory,
                    lastModified = file.lastModified(),
                    contentSnippet = snippetFor(file)
                )
            }
            .sortedWith(compareByDescending<NoteItem> { it.isDirectory }.thenByDescending { it.lastModified })
    }

    fun getRecentNotes(context: Context, limit: Int = 5): List<NoteItem> {
        val root = getNotesRoot(context)
        val allFiles = mutableListOf<File>()
        findFilesRecursively(root, allFiles)

        return allFiles
            .sortedByDescending { it.lastModified() }
            .take(limit)
            .map { file ->
                NoteItem(
                    name = file.name,
                    path = file.absolutePath,
                    isDirectory = false,
                    lastModified = file.lastModified(),
                    contentSnippet = snippetFor(file)
                )
            }
    }

    private fun findFilesRecursively(file: File, result: MutableList<File>) {
        if (file.isDirectory) {
            // Capture assets are never notes; skip the whole subtree.
            if (isAssetsDirName(file.name)) return
            file.listFiles()?.forEach { findFilesRecursively(it, result) }
        } else if (isNoteFile(file)) {
            result.add(file)
        }
    }

    /** Preview text for a note, with image references stripped. Empty for directories. */
    private fun snippetFor(file: File): String {
        if (file.isDirectory) return ""
        return try {
            file.useLines { lines -> snippetOf(lines, file.name) }
        } catch (e: Exception) {
            ""
        }
    }

    fun createFolder(context: Context, parentPath: String?, folderName: String): File? =
        createFolderIn(if (parentPath.isNullOrEmpty()) getNotesRoot(context) else File(parentPath), folderName)

    /** Creates a new folder in [parent]; a taken name gets a number rather than reusing the folder. */
    fun createFolderIn(parent: File, folderName: String): File? {
        val folder = uniqueChild(parent, folderName.trim())
        return if (folder.mkdirs()) folder else null
    }

    fun createFile(context: Context, parentPath: String?, fileName: String, content: String = ""): File? =
        createFileIn(if (parentPath.isNullOrEmpty()) getNotesRoot(context) else File(parentPath), fileName, content)

    /**
     * Creates a new note in [parent] holding [content].
     *
     * Never touches an existing file: a taken name becomes "Name (2).md". It used to open the existing
     * file and write over it, so saving a second note under the same name destroyed the first.
     */
    fun createFileIn(parent: File, fileName: String, content: String = ""): File? {
        val trimmed = fileName.trim()
        val withExtension = if (NOTE_EXTENSIONS.any { trimmed.endsWith(".$it") }) {
            trimmed
        } else {
            "$trimmed.$DEFAULT_NOTE_EXTENSION"
        }
        return try {
            parent.mkdirs()
            val file = uniqueChild(parent, withExtension)
            if (file.createNewFile()) {
                file.writeText(content)
                file
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** [name] inside [parent], or "name (2)", "name (3)"… when it is taken. The extension is kept. */
    fun uniqueChild(parent: File, name: String): File {
        val first = File(parent, name)
        if (!first.exists()) return first
        val dot = name.lastIndexOf('.')
        val (stem, ext) = if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
        var n = 2
        while (true) {
            val candidate = File(parent, "$stem ($n)$ext")
            if (!candidate.exists()) return candidate
            n++
        }
    }

    fun readFile(filePath: String): String {
        return try {
            File(filePath).readText()
        } catch (e: Exception) {
            ""
        }
    }

    fun writeFile(filePath: String, content: String): Boolean {
        return try {
            val file = File(filePath)
            val parent = file.parentFile
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }
            file.writeText(content)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Deletes an item, along with the sibling assets directory if it is a note. */
    fun deleteItem(filePath: String): Boolean {
        return try {
            val file = File(filePath)
            val deleted = if (file.isDirectory) {
                file.deleteRecursively()
            } else {
                val assets = assetsDirFor(file)
                if (assets.exists()) assets.deleteRecursively()
                file.delete()
            }
            deleted
        } catch (e: Exception) {
            false
        }
    }

    /** Creates [noteFile]'s assets directory on demand and returns it. */
    fun ensureAssetsDir(noteFile: File): File? {
        val assets = assetsDirFor(noteFile)
        return when {
            assets.isDirectory -> assets
            assets.mkdirs() -> assets
            else -> null
        }
    }

    /**
     * Moves a staged capture into [noteFile]'s assets directory.
     *
     * A rename is attempted first; staging lives under `cacheDir` and the notes tree under
     * `filesDir`, which are not guaranteed to share a filesystem.
     */
    fun moveIntoAssets(noteFile: File, stagedFile: File): File? {
        val assets = ensureAssetsDir(noteFile) ?: return null
        val destination = File(assets, stagedFile.name)
        return try {
            if (stagedFile.renameTo(destination)) {
                destination
            } else {
                stagedFile.copyTo(destination, overwrite = true)
                stagedFile.delete()
                destination
            }
        } catch (e: Exception) {
            null
        }
    }

    /** The relative reference to use in a note for a file inside its assets directory. */
    fun refFor(noteFile: File, assetFile: File): String =
        "${assetsDirName(noteFile.name)}/${assetFile.name}"
}
