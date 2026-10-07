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

    fun createFolder(context: Context, parentPath: String?, folderName: String): File? {
        val parent = if (parentPath.isNullOrEmpty()) getNotesRoot(context) else File(parentPath)
        val newFolder = File(parent, folderName)
        return if (newFolder.exists() || newFolder.mkdirs()) {
            newFolder
        } else {
            null
        }
    }

    fun createFile(context: Context, parentPath: String?, fileName: String, content: String = ""): File? {
        val parent = if (parentPath.isNullOrEmpty()) getNotesRoot(context) else File(parentPath)
        val cleanName = if (NOTE_EXTENSIONS.any { fileName.endsWith(".$it") }) {
            fileName
        } else {
            "$fileName.$DEFAULT_NOTE_EXTENSION"
        }
        val newFile = File(parent, cleanName)
        return try {
            if (newFile.createNewFile() || newFile.exists()) {
                newFile.writeText(content)
                newFile
            } else {
                null
            }
        } catch (e: Exception) {
            null
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
