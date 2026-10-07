package com.example.writersblock.ui

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.writersblock.data.FileRepository
import com.example.writersblock.data.NoteBlock
import com.example.writersblock.data.NoteItem
import com.example.writersblock.theme.Blood
import com.example.writersblock.theme.Gap
import com.example.writersblock.theme.Ink
import com.example.writersblock.theme.InkFaded
import com.example.writersblock.theme.NightDeep
import com.example.writersblock.theme.ParchmentEdge
import com.example.writersblock.theme.WoodLight
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

/** Where the drawer's contents should go when the user hits Save. */
sealed interface SaveRequest {
    /** Add the pending blocks to the end of the note the drawer is bound to. */
    data object AppendToBound : SaveRequest

    /** Overwrite the bound note with the pending blocks, discarding what it held. */
    data object ReplaceBound : SaveRequest

    /** Write the pending blocks to a new note. */
    data class AsNewNote(val folderPath: String?, val name: String) : SaveRequest
}

/**
 * The overlay drawer: a parchment scroll that unrolls down the right side of the screen.
 *
 * One composable in one full-screen overlay window, so the editor is shared with the in-app one
 * ([NoteDocument]) and every gesture is a Compose gesture. While closed, the service makes the whole
 * window untouchable and invisible, so it never steals a tap from the app underneath.
 *
 * Opening unrolls the scroll from its top roller; closing rolls it back up. Dragging it to the right
 * flings it away. Both animations are read in the layout and draw phases only — reading them during
 * composition would recompose the editor, the note lists and the picker on every frame.
 *
 * Sheets are drawn inside this window rather than with `Dialog`: a dialog needs a window token and a
 * service has none, so `Dialog` from here throws `BadTokenException`.
 *
 * @param isOpen set by the service. The scroll unrolls when it becomes true; closing is driven from
 *   here so the roll-up is not cut short by the window flags changing under it.
 * @param onBind binds the drawer to a note path, or unbinds it when given an empty string.
 */
@Composable
fun OverlayDrawer(
    isOpen: Boolean,
    onRequestClose: () -> Unit,
    focusRequestId: Int,

    boundNotePath: String,
    boundBlocks: List<NoteBlock>,
    onBind: (String) -> Unit,

    pendingBlocks: SnapshotStateList<NoteBlock>,
    onPendingTextChange: (id: String, text: String) -> Unit,
    onPendingImageRemove: (id: String) -> Unit,

    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onCapture: () -> Unit,
    onSave: (SaveRequest) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current

    // An overlay window is not reliably told about the system bars, so take the larger of the reported
    // inset and a sane minimum.
    val statusTop = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
    val navBottom = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }

    val unroll = remember { Animatable(0f) }
    val dragX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var sheet by remember { mutableStateOf<DrawerSheet?>(null) }
    val rootFocus = remember { FocusRequester() }

    /** Rolls the scroll up, then tells the service to make the window passive again. */
    fun dismiss() {
        scope.launch {
            unroll.animateTo(0f, tween(240, easing = FastOutSlowInEasing))
            dragX.snapTo(0f)
            onRequestClose()
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            // An overlay window has no back dispatcher, so Back is caught here: it closes an open sheet
            // first, then the drawer. The keyboard, when up, takes the first Back itself.
            .onPreviewKeyEvent { event ->
                if (event.key != Key.Back || !isOpen) return@onPreviewKeyEvent false
                if (event.type == KeyEventType.KeyUp) {
                    if (sheet != null) sheet = null else dismiss()
                }
                true
            }
            // Focusable so Back is delivered even when no text field has the focus.
            .focusRequester(rootFocus)
            .focusable()
    ) {
        val panelWidth: Dp = minOf(360.dp, maxWidth * 0.94f)
        val panelWidthPx = with(density) { panelWidth.toPx() }

        /** The drag-to-dismiss path: carries on in the direction of the swipe instead of rolling up. */
        fun flingAway() {
            scope.launch {
                dragX.animateTo(panelWidthPx, tween(160))
                unroll.snapTo(0f)
                dragX.snapTo(0f)
                onRequestClose()
            }
        }

        LaunchedEffect(isOpen) {
            if (isOpen) {
                // Not an error if it fails: the window may not be focusable for another frame yet.
                runCatching { rootFocus.requestFocus() }
                dragX.snapTo(0f)
                unroll.animateTo(1f, tween(380, easing = FastOutSlowInEasing))
            } else if (unroll.value > 0f) {
                // Closed by something that did not animate — the capture flow hiding the window, or the
                // service restarting. Snap: the window is already non-interactive.
                unroll.snapTo(0f)
                dragX.snapTo(0f)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    val shown = unroll.value * (1f - dragX.value / panelWidthPx)
                    if (shown > 0.01f) drawRect(NightDeep.copy(alpha = 0.6f * shown))
                }
                .pointerInput(Unit) { detectTapGestures { dismiss() } }
        )

        ScrollPanel(
            unroll = { unroll.value },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .width(panelWidth)
                .fillMaxHeight()
                .padding(top = maxOf(statusTop, 12.dp) + Gap.xs, bottom = Gap.xs, end = Gap.xs)
                // The keyboard is drawn above an application overlay, so an unresized window ends up
                // with its composer behind it. If the window manager honours ADJUST_RESIZE the window
                // itself shrinks and this inset is zero, so the two never double up.
                .imePadding()
                .offset { IntOffset(dragX.value.roundToInt(), 0) }
                // Horizontal only, so the lists inside still scroll vertically and a drag that starts in
                // a text field still selects text — the field consumes it first.
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        scope.launch { dragX.snapTo((dragX.value + delta).coerceIn(0f, panelWidthPx)) }
                    },
                    onDragStopped = { velocity ->
                        if (dragX.value > panelWidthPx * 0.35f || velocity > 900f) {
                            flingAway()
                        } else {
                            scope.launch { dragX.animateTo(0f, tween(180)) }
                        }
                    },
                ),
        ) {
            DrawerHeader(onDismiss = ::dismiss)

            ModeBar(
                boundNotePath = boundNotePath,
                onPickNote = { sheet = DrawerSheet.PickNote },
                onUnbind = { onBind("") },
            )

            if (boundNotePath.isNotEmpty()) {
                BoundNotePreview(
                    notePath = boundNotePath,
                    blocks = boundBlocks,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.5f),
                )
                InkRule()
            }

            PendingEditor(
                blocks = pendingBlocks,
                onTextChange = onPendingTextChange,
                onImageRemove = onPendingImageRemove,
                focusRequestId = focusRequestId,
                hint = if (boundNotePath.isEmpty()) {
                    "A loose page: nothing is kept until you Save."
                } else {
                    "Save adds this to ${File(boundNotePath).nameWithoutExtension}."
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )

            DrawerFooter(
                pendingBlocks = pendingBlocks,
                bottomInset = maxOf(navBottom - Gap.lg, 0.dp),
                onCopy = onCopy,
                onPaste = onPaste,
                onCapture = onCapture,
                onSave = { sheet = DrawerSheet.Save },
            )
        }

        sheet?.let { current ->
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .width(panelWidth)
                    .fillMaxHeight()
                    .imePadding(),
            ) {
                SheetScaffold(
                    title = when (current) {
                        DrawerSheet.Save -> "Save to…"
                        DrawerSheet.PickNote -> "Bind to a Note"
                    },
                    bottomInset = navBottom,
                    onDismiss = { sheet = null },
                ) { dismissSheet ->
                    when (current) {
                        DrawerSheet.Save -> SaveSheet(
                            boundNotePath = boundNotePath,
                            hasContent = pendingBlocks.any { it is NoteBlock.Image || (it is NoteBlock.Text && it.text.isNotBlank()) },
                            onSave = { request ->
                                onSave(request)
                                dismissSheet()
                            },
                            onClose = dismissSheet,
                        )

                        DrawerSheet.PickNote -> NotePickerSheet(
                            onPick = { path ->
                                onBind(path)
                                dismissSheet()
                            },
                            onClose = dismissSheet,
                        )
                    }
                }
            }
        }
    }
}

private enum class DrawerSheet { Save, PickNote }

// ── Header and mode ───────────────────────────────────────────────────────────────────────────────

@Composable
private fun DrawerHeader(onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Gap.lg, end = Gap.xs, top = Gap.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "The Scroll",
            style = MaterialTheme.typography.titleLarge,
            color = Ink,
            modifier = Modifier.weight(1f),
        )
        IconAction(label = "Roll up the drawer", onClick = onDismiss, size = 40.dp, tint = Ink) { tint ->
            IconClose(tint = tint, size = 16.dp)
        }
    }
}

@Composable
private fun ModeBar(
    boundNotePath: String,
    onPickNote: () -> Unit,
    onUnbind: () -> Unit,
) {
    val bound = boundNotePath.isNotEmpty()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Gap.lg, end = Gap.sm, bottom = Gap.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(active = bound)
        Spacer(Modifier.width(Gap.sm))
        Text(
            text = if (bound) File(boundNotePath).nameWithoutExtension else "Loose page",
            style = MaterialTheme.typography.labelLarge,
            color = if (bound) Ink else InkFaded,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (bound) {
            IconAction(label = "Unbind the drawer", onClick = onUnbind, size = 36.dp, tint = InkFaded) { tint ->
                IconClose(tint = tint, size = 13.dp)
            }
        }
        RoyalButton(
            label = if (bound) "Change" else "Bind",
            kind = ButtonKind.Parchment,
            height = 38.dp,
            icon = { tint -> IconLink(tint = tint, size = 14.dp) },
            onClick = onPickNote,
        )
    }
    InkRule()
}

// ── Note content ──────────────────────────────────────────────────────────────────────────────────

@Composable
private fun BoundNotePreview(
    notePath: String,
    blocks: List<NoteBlock>,
    modifier: Modifier = Modifier,
) {
    val noteFile = remember(notePath) { File(notePath) }

    Column(modifier = modifier) {
        DrawerLabel(if (blocks.isEmpty()) "THE BOUND NOTE IS EMPTY" else "ALREADY IN THIS NOTE")
        if (blocks.isEmpty()) {
            Text(
                text = "Anything you write below lands in it.",
                style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                color = InkFaded,
                modifier = Modifier.padding(horizontal = Gap.lg, vertical = Gap.sm),
            )
        } else {
            NoteDocument(
                blocks = blocks,
                resolveImage = remember(noteFile) { noteImageResolver(noteFile) },
                editable = false,
                onTextChange = { _, _ -> },
                textColor = InkFaded,
                contentPadding = PaddingValues(start = Gap.lg, end = Gap.lg, top = Gap.xs, bottom = Gap.md),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        }
    }
}

@Composable
private fun PendingEditor(
    blocks: SnapshotStateList<NoteBlock>,
    onTextChange: (String, String) -> Unit,
    onImageRemove: (String) -> Unit,
    focusRequestId: Int,
    hint: String,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(focusRequestId) {
        if (focusRequestId > 0) {
            // A window that has not been laid out yet has nothing to focus and the requester throws
            // rather than declining. Failing to raise the keyboard is not worth a crash.
            runCatching { focusRequester.requestFocus() }
        }
    }

    Column(modifier = modifier) {
        NoteDocument(
            blocks = blocks,
            resolveImage = remember { stagedImageResolver() },
            editable = true,
            onTextChange = onTextChange,
            onImageRemove = onImageRemove,
            contentPadding = PaddingValues(start = Gap.lg, end = Gap.lg, top = Gap.md, bottom = Gap.md),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .focusRequester(focusRequester),
        )
        Text(
            text = hint,
            style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
            color = InkFaded,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Gap.lg, vertical = Gap.xs),
        )
    }
}

@Composable
private fun DrawerFooter(
    pendingBlocks: List<NoteBlock>,
    bottomInset: Dp,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onCapture: () -> Unit,
    onSave: () -> Unit,
) {
    // Reading the list here is what makes the count live: any edit to it recomposes this row.
    val words = remember(pendingBlocks.toList()) {
        val text = pendingBlocks.filterIsInstance<NoteBlock.Text>().joinToString(" ") { it.text }
        text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
    }
    val images = pendingBlocks.count { it is NoteBlock.Image }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Gap.md)
            .padding(bottom = Gap.sm + bottomInset),
    ) {
        InkRule(modifier = Modifier.padding(bottom = Gap.sm))
        // Scan is the reason the drawer exists, so it is the wide, orange one.
        RoyalButton(
            label = "Scan the Screen",
            kind = ButtonKind.Primary,
            icon = { tint -> IconScan(tint = tint, size = 18.dp) },
            onClick = onCapture,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Gap.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(Gap.sm), verticalAlignment = Alignment.CenterVertically) {
            RoyalButton(label = "Copy", kind = ButtonKind.Parchment, height = 40.dp, onClick = onCopy, modifier = Modifier.weight(1f))
            RoyalButton(label = "Paste", kind = ButtonKind.Parchment, height = 40.dp, onClick = onPaste, modifier = Modifier.weight(1f))
            RoyalButton(label = "Save", kind = ButtonKind.Stone, height = 40.dp, onClick = onSave, modifier = Modifier.weight(1.1f))
        }
        Text(
            text = buildString {
                append(words).append(if (words == 1) " word" else " words")
                if (images > 0) append("  ·  ").append(images).append(if (images == 1) " picture" else " pictures")
            },
            style = MaterialTheme.typography.labelSmall,
            color = InkFaded,
            modifier = Modifier.padding(top = Gap.xs, start = Gap.xs),
        )
    }
}

// ── Sheets ────────────────────────────────────────────────────────────────────────────────────────

/**
 * A smaller scroll that rises from the bottom of the drawer's own window.
 *
 * [content] receives the dismiss action it should call, so a sheet that closes after doing something
 * still slides away instead of vanishing.
 */
@Composable
private fun SheetScaffold(
    title: String,
    bottomInset: Dp,
    onDismiss: () -> Unit,
    content: @Composable (dismiss: () -> Unit) -> Unit,
) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    fun close() {
        scope.launch {
            progress.animateTo(0f, tween(150))
            onDismiss()
        }
    }

    LaunchedEffect(Unit) { progress.animateTo(1f, tween(240, easing = FastOutSlowInEasing)) }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind { drawRect(NightDeep.copy(alpha = 0.55f * progress.value)) }
                .pointerInput(Unit) { detectTapGestures { close() } }
        )

        ScrollCard(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = Gap.xs)
                .padding(bottom = bottomInset + Gap.xs)
                .offset { IntOffset(0, ((1f - progress.value) * 900f).roundToInt()) }
                // Swallow taps so they do not fall through to the scrim and close the sheet.
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = Ink,
                    modifier = Modifier.weight(1f),
                )
                IconAction(label = "Close", onClick = { close() }, size = 40.dp, tint = Ink) { tint ->
                    IconClose(tint = tint, size = 15.dp)
                }
            }
            InkRule(modifier = Modifier.padding(vertical = Gap.xs))
            content(::close)
        }
    }
}

@Composable
private fun SaveSheet(
    boundNotePath: String,
    hasContent: Boolean,
    onSave: (SaveRequest) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val boundName = if (boundNotePath.isEmpty()) "" else File(boundNotePath).nameWithoutExtension

    var newNoteName by remember { mutableStateOf("") }
    var destinationFolder by remember { mutableStateOf(FileRepository.getNotesRoot(context).absolutePath) }
    var pickingFolder by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        if (!hasContent) {
            Text(
                text = "The page is blank: write or scan something first.",
                style = MaterialTheme.typography.bodyMedium,
                color = InkFaded,
                modifier = Modifier.padding(vertical = Gap.md),
            )
        } else {
            if (boundName.isNotEmpty()) {
                SheetRow(
                    title = "Add to $boundName",
                    subtitle = "Writes this at the end of the bound note.",
                    onClick = { onSave(SaveRequest.AppendToBound) },
                )
                SheetRow(
                    title = "Replace $boundName",
                    subtitle = "Throws away what the note holds and writes this instead.",
                    danger = true,
                    onClick = { onSave(SaveRequest.ReplaceBound) },
                )
                InkRule(modifier = Modifier.padding(vertical = Gap.sm))
            }

            if (pickingFolder) {
                DrawerLabel("CHOOSE A FOLDER")
                FolderList(
                    folderPath = destinationFolder,
                    onNavigate = { destinationFolder = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Gap.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = relativePath(context, destinationFolder),
                        style = MaterialTheme.typography.labelMedium,
                        color = Ink,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    RoyalButton(label = "Here", kind = ButtonKind.Primary, height = 38.dp, onClick = { pickingFolder = false })
                }
            } else {
                DrawerLabel("AS A NEW NOTE")
                InkField(
                    value = newNoteName,
                    onValueChange = { newNoteName = it },
                    hint = "Note name",
                    onDone = {
                        if (newNoteName.isNotBlank()) onSave(SaveRequest.AsNewNote(destinationFolder, newNoteName.trim()))
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Gap.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "in " + relativePath(context, destinationFolder),
                        style = MaterialTheme.typography.labelMedium,
                        color = InkFaded,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { pickingFolder = true }
                            .padding(vertical = Gap.sm),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "Change",
                        style = MaterialTheme.typography.labelMedium,
                        color = WoodLight,
                        modifier = Modifier
                            .clickable { pickingFolder = true }
                            .padding(Gap.sm),
                    )
                }
                Spacer(Modifier.height(Gap.sm))
                DialogButtons(
                    confirmLabel = "Create",
                    confirmEnabled = newNoteName.isNotBlank(),
                    onConfirm = { onSave(SaveRequest.AsNewNote(destinationFolder, newNoteName.trim())) },
                    onDismiss = onClose,
                )
            }
        }

        // The new-note form has its own Cancel beside Create; every other state needs one here.
        if (!hasContent || pickingFolder) {
            Spacer(Modifier.height(Gap.sm))
            RoyalButton(label = "Cancel", kind = ButtonKind.Parchment, onClick = onClose, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun NotePickerSheet(
    onPick: (String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val root = remember { FileRepository.getNotesRoot(context) }
    var folderPath by remember { mutableStateOf(root.absolutePath) }

    var folderItems by remember { mutableStateOf<List<NoteItem>>(emptyList()) }
    val recents = remember(root) { FileRepository.getRecentNotes(context, 5) }

    LaunchedEffect(folderPath) {
        folderItems = FileRepository.listItems(context, folderPath)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        if (folderPath != root.absolutePath) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = relativePath(context, folderPath),
                    style = MaterialTheme.typography.labelMedium,
                    color = InkFaded,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                RoyalButton(
                    label = "Up",
                    kind = ButtonKind.Parchment,
                    height = 36.dp,
                    onClick = { folderPath = File(folderPath).parentFile?.absolutePath ?: root.absolutePath },
                )
            }
        }

        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
            if (folderPath == root.absolutePath && recents.isNotEmpty()) {
                item { DrawerLabel("RECENT") }
                items(recents, key = { "recent:" + it.path }) { note ->
                    PickRow(item = note) { onPick(note.path) }
                }
                item { DrawerLabel("ALL NOTES") }
            }

            items(folderItems, key = { it.path }) { item ->
                if (item.isDirectory) {
                    FolderRow(name = item.name) { folderPath = item.path }
                } else {
                    PickRow(item = item) { onPick(item.path) }
                }
            }

            if (folderItems.isEmpty()) {
                item {
                    Text(
                        text = "No notes here yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = InkFaded,
                        modifier = Modifier.padding(vertical = Gap.md),
                    )
                }
            }
        }

        Spacer(Modifier.height(Gap.sm))
        DialogButtons(
            confirmLabel = "Loose Page",
            confirmKind = ButtonKind.Stone,
            onConfirm = { onPick("") },
            onDismiss = onClose,
        )
    }
}

/** Folder-only navigation, used by the save sheet's destination picker. */
@Composable
private fun FolderList(
    folderPath: String,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val root = remember { FileRepository.getNotesRoot(context) }

    val folders = remember(folderPath) {
        FileRepository.listItems(context, folderPath).filter { it.isDirectory }
    }

    LazyColumn(modifier = modifier) {
        if (folderPath != root.absolutePath) {
            item {
                FolderRow(name = "‹ Up a level") {
                    onNavigate(File(folderPath).parentFile?.absolutePath ?: root.absolutePath)
                }
            }
        }
        items(folders, key = { it.path }) { folder ->
            FolderRow(name = folder.name) { onNavigate(folder.path) }
        }
        if (folders.isEmpty() && folderPath == root.absolutePath) {
            item {
                Text(
                    text = "No folders yet: the note will go in the library itself.",
                    style = MaterialTheme.typography.bodySmall,
                    color = InkFaded,
                    modifier = Modifier.padding(vertical = Gap.sm),
                )
            }
        }
    }
}

// ── Small shared pieces ───────────────────────────────────────────────────────────────────────────

@Composable
private fun DrawerLabel(text: String) {
    ScrollLabel(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Gap.lg, end = Gap.lg, top = Gap.sm, bottom = Gap.xs),
    )
}

/** A faded ink line across the scroll. */
@Composable
private fun InkRule(modifier: Modifier = Modifier) {
    Rule(color = ParchmentEdge, modifier = modifier.padding(horizontal = Gap.md))
}

@Composable
private fun SheetRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    danger: Boolean = false,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = Gap.sm)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = if (danger) Blood else Ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = InkFaded)
    }
}

@Composable
private fun FolderRow(name: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = Gap.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconFolder(tint = WoodLight, size = 18.dp)
        Spacer(Modifier.width(Gap.md))
        Text(
            text = name,
            style = MaterialTheme.typography.labelLarge,
            color = Ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PickRow(item: NoteItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = Gap.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconNote(tint = WoodLight, size = 18.dp)
        Spacer(Modifier.width(Gap.md))
        Column {
            Text(
                text = item.name,
                style = MaterialTheme.typography.labelLarge,
                color = Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.contentSnippet.isNotEmpty()) {
                Text(
                    text = item.contentSnippet,
                    style = MaterialTheme.typography.bodySmall,
                    color = InkFaded,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** `/Ideas` rather than an absolute path. */
private fun relativePath(context: Context, path: String): String {
    val root = FileRepository.getNotesRoot(context)
    val relative = runCatching { File(path).relativeTo(root).path }.getOrNull().orEmpty()
    return if (relative.isEmpty() || relative == ".") "the library" else "/$relative"
}
