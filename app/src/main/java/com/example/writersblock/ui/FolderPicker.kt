package com.example.writersblock.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.writersblock.data.FileRepository
import com.example.writersblock.theme.Gap
import com.example.writersblock.theme.Ink
import com.example.writersblock.theme.InkFaded
import com.example.writersblock.theme.WoodLight
import java.io.File

/**
 * Chooses a folder in the library, written on parchment: where you are, the folders inside it, Up,
 * and a New Folder field. The folder being shown is the choice; there is no separate confirm step.
 */
@Composable
fun FolderPicker(
    folderPath: String,
    onFolderChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val root = remember { FileRepository.getNotesRoot(context) }
    var refresh by remember { mutableIntStateOf(0) }
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    val folders = remember(folderPath, refresh) {
        FileRepository.listItems(context, folderPath).filter { it.isDirectory }
    }

    fun create() {
        val created = FileRepository.createFolder(context, folderPath, newName.trim())
        if (created == null) {
            Toast.makeText(context, "Could not make that folder", Toast.LENGTH_SHORT).show()
        } else {
            creating = false
            newName = ""
            refresh++
            onFolderChange(created.absolutePath)
        }
    }

    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconFolder(tint = WoodLight, size = 18.dp)
            Spacer(Modifier.width(Gap.sm))
            Text(
                text = libraryPath(context, folderPath),
                style = MaterialTheme.typography.labelLarge,
                color = Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (folderPath != root.absolutePath) {
                RoyalButton(
                    label = "Up",
                    kind = ButtonKind.Parchment,
                    height = 36.dp,
                    onClick = { onFolderChange(File(folderPath).parentFile?.absolutePath ?: root.absolutePath) },
                )
            }
        }

        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 170.dp)) {
            items(folders, key = { it.path }) { folder ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onFolderChange(folder.path) }
                        .padding(start = Gap.lg, top = Gap.sm, bottom = Gap.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("›", style = MaterialTheme.typography.labelLarge, color = InkFaded)
                    Spacer(Modifier.width(Gap.sm))
                    Text(folder.name, style = MaterialTheme.typography.labelLarge, color = Ink, maxLines = 1)
                }
            }
        }

        if (creating) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                InkField(
                    value = newName,
                    onValueChange = { newName = it },
                    hint = "Folder name",
                    onDone = { if (newName.isNotBlank()) create() },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(Gap.sm))
                RoyalButton(
                    label = "Make",
                    kind = ButtonKind.Primary,
                    height = 38.dp,
                    enabled = newName.isNotBlank(),
                    onClick = ::create,
                )
            }
        } else {
            Text(
                text = "+ New folder here",
                style = MaterialTheme.typography.labelLarge,
                color = WoodLight,
                modifier = Modifier
                    .clickable { creating = true }
                    .padding(vertical = Gap.sm),
            )
        }
    }
}

/** "Library" or "Library › Trips › Rome", for where a note is or will be. */
fun libraryPath(context: Context, path: String): String {
    val root = FileRepository.getNotesRoot(context)
    val relative = runCatching { File(path).relativeTo(root).path }.getOrNull().orEmpty()
    return if (relative.isEmpty() || relative == ".") {
        "Library"
    } else {
        "Library › " + relative.split(File.separatorChar).joinToString(" › ")
    }
}

/** Name a note and choose its folder: used for new notes and for "save a copy". */
@Composable
fun SaveNoteDialog(
    title: String,
    initialName: String,
    initialFolder: String,
    onDismiss: () -> Unit,
    onConfirm: (name: String, folderPath: String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var folder by remember { mutableStateOf(initialFolder) }

    ScrollDialog(title = title, onDismiss = onDismiss) {
        InkField(
            value = name,
            onValueChange = { name = it },
            hint = "Note name",
            onDone = { if (name.isNotBlank()) onConfirm(name.trim(), folder) },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Gap.md))
        ScrollLabel("SAVE IN")
        Text(
            text = "Tap a folder to open it; the one shown is where the note goes.",
            style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
            color = InkFaded,
        )
        Spacer(Modifier.height(Gap.xs))
        FolderPicker(folderPath = folder, onFolderChange = { folder = it }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(Gap.md))
        DialogButtons(
            confirmLabel = "Save",
            confirmEnabled = name.isNotBlank(),
            onConfirm = { onConfirm(name.trim(), folder) },
            onDismiss = onDismiss,
        )
    }
}
