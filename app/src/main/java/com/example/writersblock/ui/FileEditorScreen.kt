package com.example.writersblock.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.writersblock.data.DraftStore
import com.example.writersblock.data.FileRepository
import com.example.writersblock.data.NoteBlock
import com.example.writersblock.data.parseNote
import com.example.writersblock.data.resolveAsset
import com.example.writersblock.data.serializeNote
import com.example.writersblock.service.OverlayService
import com.example.writersblock.theme.*
import kotlinx.coroutines.delay
import java.io.File

@Composable
fun FileEditorScreen(
    filePath: String?,
    folderPath: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val draftsDir = remember { File(context.filesDir, "drafts") }

    var currentFilePath by remember { mutableStateOf(filePath) }
    var fileName by remember { mutableStateOf(currentFilePath?.let { File(it).nameWithoutExtension } ?: "") }

    // The document, as blocks. Edits land here directly; the file form is derived from it.
    val blocks = remember { mutableStateListOf<NoteBlock>() }

    /** The note exactly as it was when last loaded or saved, for the dirty check. */
    var originalContent by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }

    var showSaveAsDialog by remember { mutableStateOf(false) }
    var showUnsavedDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    // Where a new or copied note goes by default: beside the file being edited when there is one.
    val destinationFolder = currentFilePath?.let { File(it).parentFile?.absolutePath }
        ?: folderPath
        ?: FileRepository.getNotesRoot(context).absolutePath

    // Load the note, and lay out an empty document for a new one. A draft left by a crash or a kill
    // wins over the file: it is the newer version, and it comes back marked unsaved.
    LaunchedEffect(currentFilePath) {
        val path = currentFilePath
        val content = path?.let { FileRepository.readFile(it) } ?: ""
        val noteFileName = path?.let { File(it).name } ?: ""

        blocks.clear()
        val draft = DraftStore.load(draftsDir, path)
        if (draft != null && serializeNote(draft) != content) {
            blocks.addAll(draft)
            Toast.makeText(context, "Restored your unsaved words", Toast.LENGTH_SHORT).show()
        } else {
            blocks.addAll(parseNote(content, noteFileName))
        }
        // A new note parses to nothing; it still needs one block for the caret to live in.
        blocks.seedIfEmpty()

        originalContent = content
        loaded = true
    }

    // The file form of the document. Derived rather than recomputed inline so it is only recalculated
    // when a block actually changes, not on every unrelated recomposition.
    val serialized by remember { derivedStateOf { serializeNote(blocks) } }

    val isChanged = serialized != originalContent

    // The autosave: every change goes to the crash-safe draft, never to the note itself. Saving the
    // note is still the Save button's job; this only means a crash cannot cost the words since then.
    LaunchedEffect(serialized, currentFilePath, loaded) {
        if (!loaded) return@LaunchedEffect
        delay(400)
        if (isChanged) DraftStore.save(draftsDir, currentFilePath, blocks.toList()) else DraftStore.clear(draftsDir, currentFilePath)
    }

    /** Writes the document back over the file it came from. */
    fun writeToCurrentFile(): Boolean {
        val path = currentFilePath
        if (path == null) return false
        return if (FileRepository.writeFile(path, serialized)) {
            originalContent = serialized
            DraftStore.clear(draftsDir, path)
            Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
            true
        } else {
            Toast.makeText(context, "Could not save", Toast.LENGTH_SHORT).show()
            false
        }
    }

    /**
     * Writes the document to a new note called [name] in [folder], and edits that from now on.
     *
     * Pictures are copied into the new note's own assets folder and their references rewritten: a
     * reference only counts when it points into the note's own folder, so copying the old references
     * as they were would turn every picture in the copy into a line of raw text.
     */
    fun saveAs(name: String, folder: String): Boolean {
        val nameToUse = name.trim()
        if (nameToUse.isEmpty()) {
            Toast.makeText(context, "Give the note a name first", Toast.LENGTH_SHORT).show()
            return false
        }

        val created = FileRepository.createFile(context, folder, nameToUse, "")
        if (created == null) {
            Toast.makeText(context, "Could not save \"$nameToUse\"", Toast.LENGTH_SHORT).show()
            return false
        }
        val oldFile = currentFilePath?.let { File(it) }
        val copied = blocks.toList().mapNotNull { block ->
            if (block !is NoteBlock.Image || oldFile == null) return@mapNotNull block
            val source = resolveAsset(oldFile, block.ref)
            val assets = FileRepository.ensureAssetsDir(created) ?: return@mapNotNull null
            val target = File(assets, source.name)
            runCatching { source.copyTo(target, overwrite = true) }.getOrNull()
                ?.let { block.copy(ref = FileRepository.refFor(created, it)) }
        }
        val content = serializeNote(copied)
        FileRepository.writeFile(created.absolutePath, content)
        DraftStore.clear(draftsDir, currentFilePath)

        blocks.clear()
        blocks.addAll(parseNote(content, created.name))
        blocks.seedIfEmpty()
        originalContent = content
        currentFilePath = created.absolutePath
        fileName = created.nameWithoutExtension
        Toast.makeText(context, "Saved as ${created.nameWithoutExtension}", Toast.LENGTH_SHORT).show()
        return true
    }

    /** The Save button: overwrite in place, and ask for a name and folder if it has never been saved. */
    fun save() {
        if (currentFilePath == null) showSaveAsDialog = true else writeToCurrentFile()
    }

    /** Saves, then points the overlay drawer at this note and opens it. */
    fun sendToOverlay() {
        val path = currentFilePath
        if (path == null) {
            Toast.makeText(context, "Save the note first", Toast.LENGTH_SHORT).show()
            return
        }
        if (isChanged && !writeToCurrentFile()) return

        if (!OverlayService.isRunning) {
            Toast.makeText(context, "Wake the drawer in Settings first", Toast.LENGTH_LONG).show()
            return
        }
        OverlayService.openDrawer(context, path)
    }

    // Intercept back button to warn of unsaved changes
    BackHandler {
        if (isChanged) {
            showUnsavedDialog = true
        } else {
            onBack()
        }
    }

    // Metrics describe the prose, not the reference lines: counting an image's path as words would
    // make a note's word count jump every time a screenshot was dropped into it.
    val prose = blocks.filterIsInstance<NoteBlock.Text>().joinToString("") { it.text }
    val imageCount = blocks.count { it is NoteBlock.Image }
    val wordCount = remember(prose) {
        prose.trim().split("\\s+".toRegex()).count { it.isNotEmpty() }
    }
    val charCount = prose.length
    val lineCount = remember(prose) { if (prose.isEmpty()) 0 else prose.count { it == '\n' } + 1 }

    CastleBackdrop(modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = Gap.sm, end = Gap.md, top = Gap.sm, bottom = Gap.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconAction(
                    label = "Back",
                    onClick = { if (isChanged) showUnsavedDialog = true else onBack() }
                ) { tint -> IconBack(tint = tint, size = 22.dp) }

                Spacer(modifier = Modifier.width(Gap.xs))

                if (currentFilePath == null) {
                    InlineNameField(
                        value = fileName,
                        onChange = { fileName = it },
                        onDone = { save() },
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = fileName,
                            style = MaterialTheme.typography.titleLarge,
                            color = Lavender,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        // The folder is what tells two notes of the same name apart.
                        Text(
                            text = currentFilePath!!.let { path ->
                                val parent = File(path).parentFile
                                if (parent == null || parent == FileRepository.getNotesRoot(context)) "Library" else parent.name
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = Mist,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Box {
                    IconAction(label = "More actions", onClick = { showMenu = true }) { tint ->
                        IconMore(tint = tint, size = 22.dp)
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        containerColor = Parchment,
                        border = BorderStroke(2.dp, Outline),
                        shape = RoundedCornerShape(Corner.medium)
                    ) {
                        DropdownMenuItem(
                            text = { MenuLabel(if (currentFilePath == null) "Save" else "Save a copy…") },
                            onClick = {
                                showMenu = false
                                showSaveAsDialog = true
                            }
                        )
                        DropdownMenuItem(
                            text = { MenuLabel("Share to another app…") },
                            onClick = {
                                showMenu = false
                                shareNote(
                                    context,
                                    fileName.ifBlank { "Untitled" },
                                    blocks.toList(),
                                    noteImageResolver(currentFilePath?.let { File(it) })
                                )
                            }
                        )
                        DropdownMenuItem(
                            text = { MenuLabel("Export as PDF or Word…") },
                            onClick = {
                                showMenu = false
                                showExportDialog = true
                            }
                        )
                        DropdownMenuItem(
                            text = { MenuLabel("Bind the drawer to this note") },
                            onClick = {
                                showMenu = false
                                sendToOverlay()
                            }
                        )
                        DropdownMenuItem(
                            text = { MenuLabel("Send the text to the drawer") },
                            onClick = {
                                showMenu = false
                                if (prose.isBlank()) {
                                    Toast.makeText(context, "Nothing to send", Toast.LENGTH_SHORT).show()
                                } else if (!OverlayService.isRunning) {
                                    Toast.makeText(context, "Wake the drawer in Settings first", Toast.LENGTH_LONG).show()
                                } else {
                                    // Joins the drawer's pending document rather than becoming a note.
                                    OverlayService.pushTextToDrawer(context, prose)
                                    OverlayService.openDrawer(context)
                                    Toast.makeText(context, "Sent to the drawer", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.width(Gap.xs))

                RoyalButton(
                    label = "Save",
                    kind = if (isChanged) ButtonKind.Primary else ButtonKind.Stone,
                    height = 42.dp,
                    onClick = { save() },
                    modifier = Modifier.widthIn(min = 76.dp)
                )
            }

            val noteFile = currentFilePath?.let { File(it) }
            ScrollPanel(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = Gap.sm)
                    .padding(bottom = Gap.sm)
            ) {
                NoteDocument(
                    blocks = blocks,
                    resolveImage = remember(noteFile) { noteImageResolver(noteFile) },
                    editable = true,
                    onTextChange = { id, text -> blocks.replaceText(id, text) },
                    // Padding inside the list, so lines scroll under the roller instead of being cut at it.
                    contentPadding = PaddingValues(horizontal = Gap.lg, vertical = Gap.md),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )

                MetricsBar(
                    wordCount = wordCount,
                    charCount = charCount,
                    lineCount = lineCount,
                    imageCount = imageCount
                )
            }
        }
    }

    if (showSaveAsDialog) {
        SaveNoteDialog(
            title = if (currentFilePath == null) "Save this Note" else "Save a Copy",
            initialName = fileName,
            initialFolder = destinationFolder,
            onDismiss = { showSaveAsDialog = false },
            onConfirm = { name, folder ->
                if (saveAs(name, folder)) showSaveAsDialog = false
            }
        )
    }

    if (showExportDialog) {
        val title = fileName.ifBlank { "Untitled" }
        val noteFile = currentFilePath?.let { File(it) }
        ExportDialog(
            title = title,
            blocks = blocks.toList(),
            resolveImage = noteImageResolver(noteFile),
            onDismiss = { showExportDialog = false },
        )
    }

    if (showUnsavedDialog) {
        ScrollDialog(title = "Unsaved Words", onDismiss = { showUnsavedDialog = false }) {
            Text(
                text = "This note has changes that are not written down yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = Ink,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(Gap.lg))
            RoyalButton(
                label = "Save & Leave",
                kind = ButtonKind.Primary,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    if (currentFilePath == null && fileName.isBlank()) {
                        showUnsavedDialog = false
                        showSaveAsDialog = true
                    } else {
                        val saved = currentFilePath?.let { writeToCurrentFile() } ?: saveAs(fileName, destinationFolder)
                        if (saved) {
                            showUnsavedDialog = false
                            onBack()
                        }
                    }
                }
            )
            Spacer(Modifier.height(Gap.sm))
            DialogButtons(
                confirmLabel = "Discard",
                confirmKind = ButtonKind.Danger,
                onConfirm = {
                    DraftStore.clear(draftsDir, currentFilePath)
                    showUnsavedDialog = false
                    onBack()
                },
                onDismiss = { showUnsavedDialog = false },
                dismissLabel = "Keep Writing"
            )
        }
    }
}

/** The note's name while it has no file yet, edited in place rather than behind a dialog. */
@Composable
private fun InlineNameField(
    value: String,
    onChange: (String) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.titleLarge.copy(color = Lavender),
        cursorBrush = SolidColor(Gold),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = modifier,
        decorationBox = { inner ->
            Box(modifier = Modifier.fillMaxWidth()) {
                if (value.isEmpty()) {
                    Text(
                        text = "Untitled",
                        style = MaterialTheme.typography.titleLarge,
                        color = Mist
                    )
                }
                inner()
            }
        }
    )
}

/** The document's measurements, along the foot of the scroll. */
@Composable
private fun MetricsBar(
    wordCount: Int,
    charCount: Int,
    lineCount: Int,
    imageCount: Int
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Gap.lg, vertical = Gap.sm),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        ScrollLabel(
            buildString {
                append("$wordCount words  ·  $charCount chars")
                if (imageCount > 0) append("  ·  $imageCount image${if (imageCount == 1) "" else "s"}")
            }
        )
        ScrollLabel(if (lineCount == 1) "1 line" else "$lineCount lines")
    }
}

@Composable
private fun MenuLabel(text: String) {
    Text(text = text, style = MaterialTheme.typography.labelLarge, color = Ink)
}
