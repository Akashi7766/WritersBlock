package com.example.writersblock.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import com.example.writersblock.FileBrowser
import com.example.writersblock.FileEditor
import com.example.writersblock.data.FileRepository
import com.example.writersblock.data.NoteItem
import com.example.writersblock.theme.Gap
import com.example.writersblock.theme.Gold
import com.example.writersblock.theme.Lavender
import com.example.writersblock.theme.Mist
import java.io.File

/** The library: folders and notes, one level at a time. */
@Composable
fun FileBrowserScreen(
    folderPath: String?,
    onNavigate: (NavKey) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val rootDir = remember { FileRepository.getNotesRoot(context) }
    val currentFolder = remember(folderPath) {
        if (folderPath.isNullOrEmpty()) rootDir else File(folderPath)
    }

    var items by remember { mutableStateOf<List<NoteItem>>(emptyList()) }
    var creatingFolder by remember { mutableStateOf<Boolean?>(null) }
    var itemToDelete by remember { mutableStateOf<NoteItem?>(null) }

    fun refreshItems() {
        items = FileRepository.listItems(context, currentFolder.absolutePath)
    }

    LaunchedEffect(currentFolder) { refreshItems() }

    /** The path from the notes root down to here, as (label, path) pairs. */
    val crumbs = remember(currentFolder) {
        val rootPath = rootDir.absolutePath
        val segments = currentFolder.absolutePath
            .removePrefix(rootPath)
            .trim(File.separatorChar)
            .split(File.separatorChar)
            .filter { it.isNotEmpty() }
        buildList {
            add("Library" to rootPath)
            var running = rootPath
            segments.forEach { segment ->
                running = "$running${File.separator}$segment"
                add(segment to running)
            }
        }
    }

    val atRoot = currentFolder.absolutePath == rootDir.absolutePath

    // Back goes up one level while there is one, and only leaves the library from the top.
    val up: () -> Unit = {
        val parent = currentFolder.parentFile
        if (atRoot || parent == null) onBack() else onNavigate(FileBrowser(parent.absolutePath))
    }
    BackHandler { up() }

    CastleBackdrop(modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = Gap.screenH),
        ) {
            ScreenHeader(
                title = if (atRoot) "Library" else currentFolder.name,
                onBack = up,
                backLabel = if (atRoot) "Back" else "Up one folder",
            )

            if (!atRoot) {
                Breadcrumbs(
                    crumbs = crumbs,
                    onCrumbClick = { path -> if (path != currentFolder.absolutePath) onNavigate(FileBrowser(path)) },
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Gap.sm),
                horizontalArrangement = Arrangement.spacedBy(Gap.sm),
            ) {
                RoyalButton(
                    label = "New Folder",
                    icon = { tint -> IconFolder(tint = tint, size = 16.dp) },
                    onClick = { creatingFolder = true },
                    modifier = Modifier.weight(1f),
                )
                RoyalButton(
                    label = "New Note",
                    kind = ButtonKind.Primary,
                    icon = { tint -> IconQuill(tint = tint, size = 16.dp) },
                    onClick = { creatingFolder = false },
                    modifier = Modifier.weight(1f),
                )
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(Gap.sm),
                contentPadding = PaddingValues(top = Gap.sm, bottom = CastleWallHeight),
            ) {
                if (items.isEmpty()) {
                    item { EmptyState(title = "An empty shelf", hint = "Create a note or a folder above.") }
                } else {
                    items(items, key = { it.path }) { item ->
                        NoteRow(
                            title = item.name,
                            snippet = if (item.isDirectory) "" else item.contentSnippet,
                            trailing = null,
                            isFolder = item.isDirectory,
                            onClick = {
                                if (item.isDirectory) {
                                    onNavigate(FileBrowser(item.path))
                                } else {
                                    onNavigate(FileEditor(item.path, currentFolder.absolutePath))
                                }
                            },
                            end = {
                                IconAction(label = "Delete ${item.name}", onClick = { itemToDelete = item }, size = 40.dp, tint = Mist) { tint ->
                                    IconClose(tint = tint, size = 14.dp)
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    creatingFolder?.let { isFolder ->
        NameDialog(
            title = if (isFolder) "New Folder" else "New Note",
            hint = if (isFolder) "Folder name" else "Note name",
            confirmLabel = "Create",
            onDismiss = { creatingFolder = null },
            onConfirm = { name ->
                val created = if (isFolder) {
                    FileRepository.createFolder(context, currentFolder.absolutePath, name)
                } else {
                    FileRepository.createFile(context, currentFolder.absolutePath, name)
                }
                if (created == null) {
                    Toast.makeText(context, "Could not create \"$name\"", Toast.LENGTH_SHORT).show()
                } else {
                    creatingFolder = null
                    // A new note is opened straight away; nobody creates an empty note to look at it in a list.
                    if (isFolder) refreshItems() else onNavigate(FileEditor(created.absolutePath, currentFolder.absolutePath))
                }
            },
        )
    }

    itemToDelete?.let { item ->
        ConfirmDialog(
            title = "Burn it?",
            body = "Delete ${item.name}${if (item.isDirectory) " and everything in it" else ""}? This cannot be undone.",
            confirmLabel = "Delete",
            destructive = true,
            onDismiss = { itemToDelete = null },
            onConfirm = {
                if (FileRepository.deleteItem(item.path)) {
                    refreshItems()
                } else {
                    Toast.makeText(context, "Could not delete ${item.name}", Toast.LENGTH_SHORT).show()
                }
                itemToDelete = null
            },
        )
    }
}

/** The route from the library root to here, every step tappable. */
@Composable
private fun Breadcrumbs(
    crumbs: List<Pair<String, String>>,
    onCrumbClick: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        crumbs.forEachIndexed { index, (label, path) ->
            val isLast = index == crumbs.lastIndex
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (isLast) Gold else Lavender,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(enabled = !isLast) { onCrumbClick(path) }
                    .padding(horizontal = Gap.sm, vertical = Gap.xs),
            )
            if (!isLast) Text("›", style = MaterialTheme.typography.labelMedium, color = Mist)
        }
        Spacer(Modifier.padding(end = Gap.sm))
    }
}
