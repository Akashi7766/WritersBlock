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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.writersblock.data.FileRepository
import com.example.writersblock.data.NoteBlock
import com.example.writersblock.data.parseNote
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

    var currentFilePath by remember { mutableStateOf(filePath) }
    var fileName by remember { mutableStateOf(currentFilePath?.let { File(it).nameWithoutExtension } ?: "") }

    // The document, as blocks. Edits land here directly; the file form is derived from it.
    val blocks = remember { mutableStateListOf<NoteBlock>() }

    /** The note exactly as it was when last loaded or saved, for the dirty check. */
    var originalContent by remember { mutableStateOf("") }

    var showSaveAsDialog by remember { mutableStateOf(false) }
    var showUnsavedDialog by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    // Where a new or copied note goes: beside the file being edited when there is one, so "save as"
    // from a note in a folder does not quietly drop the copy into the notes root.
    val destinationFolder = currentFilePath?.let { File(it).parentFile?.absolutePath } ?: folderPath

    // Load file if it exists, and lay out an empty document if it does not.
    LaunchedEffect(currentFilePath) {
        val loaded = currentFilePath?.let { FileRepository.readFile(it) } ?: ""
        val noteFileName = currentFilePath?.let { File(it).name } ?: ""

        blocks.clear()
        blocks.addAll(parseNote(loaded, noteFileName))
        // A new note parses to nothing; it still needs one block for the caret to live in.
        blocks.seedIfEmpty()

        originalContent = loaded
    }

    // The file form of the document. Derived rather than recomputed inline so it is only recalculated
    // when a block actually changes, not on every unrelated recomposition.
    val serialized by remember { derivedStateOf { serializeNote(blocks) } }

    val isChanged = serialized != originalContent

    /** Writes the document back over the file it came from. */
    fun writeToCurrentFile(): Boolean {
        val path = currentFilePath
        if (path == null) return false
        return if (FileRepository.writeFile(path, serialized)) {
            originalContent = serialized
            Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
            true
        } else {
            Toast.makeText(context, "Could not save", Toast.LENGTH_SHORT).show()
            false
        }
    }

    /** Writes the document to a file called [name], and edits that from now on. */
    fun saveAs(name: String): Boolean {
        val nameToUse = name.trim()
        if (nameToUse.isEmpty()) {
            Toast.makeText(context, "Give the note a name first", Toast.LENGTH_SHORT).show()
            return false
        }

        val created = FileRepository.createFile(context, destinationFolder, nameToUse, serialized)
        if (created == null) {
            Toast.makeText(context, "Could not save \"$nameToUse\"", Toast.LENGTH_SHORT).show()
            return false
        }

        currentFilePath = created.absolutePath
        fileName = created.nameWithoutExtension
        originalContent = serialized
        Toast.makeText(context, "Saved as ${created.name}", Toast.LENGTH_SHORT).show()
        return true
    }

    /** The SAVE button: overwrite in place, and name the note first if it has never been named. */
    fun save() {
        if (currentFilePath == null && fileName.isBlank()) {
            showSaveAsDialog = true
        } else if (currentFilePath == null) {
            saveAs(fileName)
        } else {
            writeToCurrentFile()
        }
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
            Toast.makeText(
                context,
                "Turn on the overlay service in Settings first",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        OverlayService.openDrawer(context, path)
        Toast.makeText(context, "Drawer bound to ${File(path).name}", Toast.LENGTH_SHORT).show()
    }

    // Auto-save logic every 30 seconds for existing files
    LaunchedEffect(serialized, currentFilePath) {
        if (currentFilePath != null && isChanged) {
            delay(30000)
            FileRepository.writeFile(currentFilePath!!, serialized)
            originalContent = serialized
            Toast.makeText(context, "Auto-saved", Toast.LENGTH_SHORT).show()
        }
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

    Surface(
        modifier = modifier.fillMaxSize(),
        color = SlateBlack
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Gap.sm, vertical = Gap.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconAction(
                    label = "Back",
                    onClick = { if (isChanged) showUnsavedDialog = true else onBack() }
                ) { tint -> IconBack(tint = tint) }

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
                            style = MaterialTheme.typography.titleSmall,
                            color = TextWhite,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            // The folder is what tells two notes of the same name apart, and the
                            // editor was previously the one screen that hid it.
                            text = currentFilePath!!.let { File(it).parentFile?.name ?: "" },
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Box {
                    IconAction(label = "More actions", onClick = { showMenu = true }) { tint ->
                        IconMore(tint = tint)
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        containerColor = SlateDark,
                        border = BorderStroke(1.dp, BorderGray),
                        shape = RoundedCornerShape(Corner.medium)
                    ) {
                        DropdownMenuItem(
                            text = { MenuLabel(if (currentFilePath == null) "SAVE" else "SAVE A COPY…") },
                            onClick = {
                                showMenu = false
                                showSaveAsDialog = true
                            }
                        )
                        DropdownMenuItem(
                            text = { MenuLabel("SEND TO OVERLAY") },
                            onClick = {
                                showMenu = false
                                sendToOverlay()
                            }
                        )
                        DropdownMenuItem(
                            text = { MenuLabel("SEND TEXT TO OVERLAY") },
                            onClick = {
                                showMenu = false
                                if (prose.isBlank()) {
                                    Toast.makeText(context, "Nothing to send", Toast.LENGTH_SHORT).show()
                                } else {
                                    // Joins the drawer's pending document rather than becoming a
                                    // note: prose that is on its way somewhere that is not a note yet.
                                    OverlayService.openDrawer(context)
                                    OverlayService.pushTextToDrawer(context, prose)
                                    Toast.makeText(context, "Sent to the drawer", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.width(Gap.xs))

                if (isChanged) {
                    FilledAction(
                        label = "SAVE",
                        onClick = { save() },
                        modifier = Modifier.widthIn(min = 72.dp)
                    )
                } else {
                    PillButton(
                        label = "SAVE",
                        onClick = { save() },
                        modifier = Modifier.widthIn(min = 72.dp)
                    )
                }
            }

            Hairline()

            // Monospace Editor space
            val noteFile = currentFilePath?.let { File(it) }
            NoteDocument(
                blocks = blocks,
                resolveImage = remember(noteFile) { noteImageResolver(noteFile) },
                editable = true,
                onTextChange = { id, text -> blocks.replaceText(id, text) },
                // Padding is applied inside the list so lines scroll under the header instead of
                // being clipped at its edge.
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

    if (showSaveAsDialog) {
        NameDialog(
            title = if (currentFilePath == null) "NAME THIS NOTE" else "SAVE A COPY AS",
            hint = "note_name",
            confirmLabel = "SAVE",
            initial = fileName,
            onDismiss = { showSaveAsDialog = false },
            onConfirm = { name ->
                if (saveAs(name)) showSaveAsDialog = false
            }
        )
    }

    if (showUnsavedDialog) {
        AlertDialog(
            onDismissRequest = { showUnsavedDialog = false },
            containerColor = SlateDark,
            shape = RoundedCornerShape(Corner.large),
            title = { DialogTitle("UNSAVED CHANGES") },
            text = {
                Text(
                    text = "This note has changes that are not written to disk yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextGray
                )
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(Gap.xs)) {
                    DialogAction(
                        label = "DISCARD",
                        color = Danger,
                        onClick = {
                            showUnsavedDialog = false
                            onBack()
                        }
                    )
                    DialogAction(
                        label = "SAVE & EXIT",
                        onClick = {
                            if (currentFilePath == null && fileName.isBlank()) {
                                showUnsavedDialog = false
                                showSaveAsDialog = true
                            } else {
                                val saved = currentFilePath?.let { writeToCurrentFile() }
                                    ?: saveAs(fileName)
                                if (saved) {
                                    showUnsavedDialog = false
                                    onBack()
                                }
                            }
                        }
                    )
                }
            },
            dismissButton = {
                DialogAction(label = "CANCEL", onClick = { showUnsavedDialog = false }, muted = true)
            }
        )
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
        textStyle = MaterialTheme.typography.titleSmall.copy(color = TextWhite),
        cursorBrush = SolidColor(PureWhite),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = modifier,
        decorationBox = { inner ->
            Box(modifier = Modifier.fillMaxWidth()) {
                if (value.isEmpty()) {
                    Text(
                        text = "untitled",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextMuted
                    )
                }
                inner()
            }
        }
    )
}

/** The document's measurements, along the bottom edge. */
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
            .background(SlateDark)
            .padding(horizontal = Gap.lg, vertical = Gap.sm),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = buildString {
                append("$wordCount words")
                append("  ·  $charCount chars")
                if (imageCount > 0) append("  ·  $imageCount image${if (imageCount == 1) "" else "s"}")
            },
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted
        )
        Text(
            text = if (lineCount == 1) "1 line" else "$lineCount lines",
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted
        )
    }
}

/** A one-pixel rule. Used to separate a header from its content without a shadow. */
@Composable
internal fun Hairline(color: Color = BorderGray) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(color)
    )
}

@Composable
private fun MenuLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
        color = TextWhite
    )
}
