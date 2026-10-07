package com.example.writersblock.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.navigation3.runtime.NavKey
import com.example.writersblock.FileBrowser
import com.example.writersblock.FileEditor
import com.example.writersblock.data.FileRepository
import com.example.writersblock.data.NoteItem
import com.example.writersblock.theme.*
import java.io.File

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun FileBrowserScreen(
    folderPath: String?,
    onNavigate: (NavKey) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val rootDir = remember { FileRepository.getNotesRoot(context) }
    val currentFolder = remember(folderPath) {
        if (folderPath.isNullOrEmpty()) rootDir else File(folderPath)
    }

    var items by remember { mutableStateOf<List<NoteItem>>(emptyList()) }
    var pendingNew by remember { mutableStateOf<NewItem?>(null) }
    var itemToDelete by remember { mutableStateOf<NoteItem?>(null) }

    fun refreshItems() {
        items = FileRepository.listItems(context, currentFolder.absolutePath)
    }

    LaunchedEffect(currentFolder) {
        refreshItems()
    }

    /** The path from the notes root down to here, as (label, path) pairs the header can walk back up. */
    val crumbs = remember(currentFolder) {
        val rootPath = rootDir.absolutePath
        val segments = currentFolder.absolutePath
            .removePrefix(rootPath)
            .trim(File.separatorChar)
            .split(File.separatorChar)
            .filter { it.isNotEmpty() }

        buildList {
            add("NOTES" to rootPath)
            var running = rootPath
            segments.forEach { segment ->
                running = "$running${File.separator}$segment"
                add(segment to running)
            }
        }
    }

    val atRoot = currentFolder.absolutePath == rootDir.absolutePath

    // Back means "up one level" while there is a level to go up, and only leaves the browser entirely
    // at the top. Sending every back press to the home screen made the browser feel like a dead end.
    val up: () -> Unit = {
        val parent = currentFolder.parentFile
        if (atRoot || parent == null) onBack() else onNavigate(FileBrowser(parent.absolutePath))
    }

    androidx.activity.compose.BackHandler { up() }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = SlateBlack
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = Gap.screenH)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Gap.md, bottom = Gap.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconAction(
                    label = if (atRoot) "Back" else "Up one folder",
                    onClick = up
                ) { tint -> IconBack(tint = tint) }

                Spacer(modifier = Modifier.width(Gap.sm))

                Column(modifier = Modifier.weight(1f)) {
                    SectionLabel(text = "EXPLORER")
                    Breadcrumbs(
                        crumbs = crumbs,
                        onCrumbClick = { path ->
                            if (path != currentFolder.absolutePath) onNavigate(FileBrowser(path))
                        }
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Gap.xs, bottom = Gap.md),
                horizontalArrangement = Arrangement.spacedBy(Gap.sm)
            ) {
                PillButton(
                    label = "NEW FOLDER",
                    icon = { tint -> IconFolder(tint = tint, size = 14.dp) },
                    onClick = { pendingNew = NewItem(isFolder = true) },
                    modifier = Modifier.weight(1f)
                )
                PillButton(
                    label = "NEW NOTE",
                    icon = { tint -> IconPlus(tint = tint, size = 14.dp) },
                    onClick = { pendingNew = NewItem(isFolder = false) },
                    modifier = Modifier.weight(1f)
                )
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(Gap.sm),
                contentPadding = PaddingValues(bottom = Gap.xl)
            ) {
                if (items.isEmpty()) {
                    item {
                        EmptyState(
                            title = "This folder is empty",
                            hint = "Create a note or a folder above."
                        )
                    }
                } else {
                    items(items) { item ->
                        ItemRow(
                            item = item,
                            onClick = {
                                if (item.isDirectory) {
                                    onNavigate(FileBrowser(item.path))
                                } else {
                                    onNavigate(FileEditor(item.path, currentFolder.absolutePath))
                                }
                            },
                            onDelete = { itemToDelete = item }
                        )
                    }
                }
            }
        }
    }

    pendingNew?.let { pending ->
        NameDialog(
            title = if (pending.isFolder) "NEW FOLDER" else "NEW NOTE",
            hint = if (pending.isFolder) "folder_name" else "note_name",
            confirmLabel = "CREATE",
            onDismiss = { pendingNew = null },
            onConfirm = { name ->
                val created = if (pending.isFolder) {
                    FileRepository.createFolder(context, currentFolder.absolutePath, name)
                } else {
                    FileRepository.createFile(context, currentFolder.absolutePath, name)
                }
                if (created != null) {
                    pendingNew = null
                    refreshItems()
                } else {
                    Toast.makeText(context, "Could not create \"$name\"", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    itemToDelete?.let { item ->
        ConfirmDialog(
            title = "DELETE",
            body = "Delete ${item.name}? This cannot be undone.",
            confirmLabel = "DELETE",
            destructive = true,
            onDismiss = { itemToDelete = null },
            onConfirm = {
                if (FileRepository.deleteItem(item.path)) {
                    refreshItems()
                } else {
                    Toast.makeText(context, "Could not delete ${item.name}", Toast.LENGTH_SHORT).show()
                }
                itemToDelete = null
            }
        )
    }
}

/** Which kind of thing the name dialog is about to create. */
private data class NewItem(val isFolder: Boolean)

/**
 * The path, with every segment tappable.
 *
 * It shows the whole route rather than just the current folder name, and doubles as the way back up —
 * which is the navigation people reach for first, before they look for a back arrow.
 */
@Composable
private fun Breadcrumbs(
    crumbs: List<Pair<String, String>>,
    onCrumbClick: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = Gap.hairline),
        verticalAlignment = Alignment.CenterVertically
    ) {
        crumbs.forEachIndexed { index, (label, path) ->
            val isLast = index == crumbs.lastIndex
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = if (isLast) TextWhite else TextMuted,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(Corner.small))
                    .clickable(enabled = !isLast) { onCrumbClick(path) }
                    .padding(horizontal = Gap.xs, vertical = Gap.hairline)
            )
            if (!isLast) {
                Text(
                    text = "/",
                    style = MaterialTheme.typography.titleSmall,
                    color = TextMuted,
                    modifier = Modifier.padding(horizontal = Gap.hairline)
                )
            }
        }
    }
}

@Composable
private fun ItemRow(
    item: NoteItem,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Corner.medium))
            .background(SlateDark, RoundedCornerShape(Corner.medium))
            .border(BorderStroke(1.dp, BorderGray), RoundedCornerShape(Corner.medium))
            .combinedClickable(onClick = onClick, onLongClick = onDelete)
            .padding(start = Gap.md, end = Gap.xs, top = Gap.md, bottom = Gap.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (item.isDirectory) {
            IconFolder(tint = TextGray, size = 20.dp)
        } else {
            IconNote(tint = TextMuted, size = 20.dp)
        }

        Spacer(modifier = Modifier.width(Gap.md))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.titleSmall,
                color = TextWhite,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!item.isDirectory && item.contentSnippet.isNotEmpty()) {
                Spacer(modifier = Modifier.height(Gap.hairline))
                Text(
                    text = item.contentSnippet,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // The delete affordance stays visible rather than hiding behind a long-press: the long-press is
        // a shortcut for people who know it, not the only way in.
        IconAction(
            label = "Delete ${item.name}",
            onClick = onDelete,
            size = 32.dp
        ) { tint -> IconClose(tint = tint, size = 15.dp) }

        if (item.isDirectory) {
            IconChevronRight(tint = TextMuted, size = 16.dp)
        }
    }
}

// ── Shared controls ───────────────────────────────────────────────────────────────────────────

/** A short outlined button. Used wherever the action is secondary to the screen's subject. */
@Composable
internal fun PillButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: (@Composable (Color) -> Unit)? = null
) {
    Row(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(Corner.medium))
            .background(SlateDark, RoundedCornerShape(Corner.medium))
            .border(BorderStroke(1.dp, BorderGray), RoundedCornerShape(Corner.medium))
            .clickable(onClick = onClick)
            .padding(horizontal = Gap.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            icon(TextGray)
            Spacer(modifier = Modifier.width(Gap.sm))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = TextWhite
        )
    }
}

/** A single-line name prompt. Used for new files, new folders and save-as. */
@Composable
internal fun NameDialog(
    title: String,
    hint: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    initial: String = ""
) {
    var name by remember { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SlateDark,
        shape = RoundedCornerShape(Corner.large),
        title = { DialogTitle(title) },
        text = {
            BasicTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = TextWhite),
                cursorBrush = SolidColor(PureWhite),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = ImeAction.Done
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onDone = { if (name.isNotBlank()) onConfirm(name.trim()) }
                ),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Corner.small))
                            .background(SlateBlack, RoundedCornerShape(Corner.small))
                            .border(
                                BorderStroke(1.dp, BorderBright),
                                RoundedCornerShape(Corner.small)
                            )
                            .padding(horizontal = Gap.md, vertical = Gap.md),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            if (name.isEmpty()) {
                                Text(
                                    text = hint,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = TextMuted
                                )
                            }
                            inner()
                        }
                    }
                }
            )
        },
        confirmButton = {
            DialogAction(
                label = confirmLabel,
                enabled = name.isNotBlank(),
                onClick = { onConfirm(name.trim()) }
            )
        },
        dismissButton = {
            DialogAction(label = "CANCEL", onClick = onDismiss, muted = true)
        }
    )
}

@Composable
internal fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    destructive: Boolean = false
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SlateDark,
        shape = RoundedCornerShape(Corner.large),
        title = { DialogTitle(title) },
        text = {
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = TextGray
            )
        },
        confirmButton = {
            DialogAction(
                label = confirmLabel,
                onClick = onConfirm,
                color = if (destructive) Danger else TextWhite
            )
        },
        dismissButton = {
            DialogAction(label = "CANCEL", onClick = onDismiss, muted = true)
        }
    )
}

@Composable
internal fun DialogTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = TextWhite
    )
}

/**
 * A text action in a dialog.
 *
 * Material's [TextButton] carries its own colours and ripple; these dialogs are a flat monochrome and
 * the ripple reads as a smudge against the near-black sheet, so the action is plain text instead.
 */
@Composable
internal fun DialogAction(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    muted: Boolean = false,
    color: Color = TextWhite
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
        color = when {
            !enabled -> TextMuted.copy(alpha = 0.5f)
            muted -> TextMuted
            else -> color
        },
        modifier = Modifier
            .clip(RoundedCornerShape(Corner.small))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Gap.md, vertical = Gap.sm)
    )
}
