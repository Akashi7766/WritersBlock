package com.example.writersblock.ui

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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.writersblock.data.FileRepository
import com.example.writersblock.data.NoteBlock
import com.example.writersblock.data.NoteItem
import com.example.writersblock.data.resolveAsset
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
 * The scroll shows one page at a time: the loose page, or a note opened in full and editable. Every
 * edit is autosaved by the service into a crash-safe draft; Save writes it into the note. Switching
 * pages with unsaved changes asks first ([switchTarget], resolved through [onResolveSwitch]).
 */
@Composable
fun OverlayDrawer(
    isOpen: Boolean,
    onRequestClose: () -> Unit,
    focusRequestId: Int,

    pagePath: String,
    isDirty: Boolean,
    onRequestSwitch: (String) -> Unit,
    switchTarget: String?,
    onResolveSwitch: (SwitchChoice) -> Unit,

    pendingBlocks: SnapshotStateList<NoteBlock>,
    onPendingTextChange: (id: String, text: String) -> Unit,
    onPendingImageRemove: (id: String) -> Unit,

    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onCapture: () -> Unit,
    onSave: () -> Unit,
    onSaveAsNew: (folderPath: String, name: String) -> Unit,
    onShare: () -> Unit,
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

    // The drawer has no activity window to lend Android's own copy/paste bar, so it draws its own.
    val textMenu = remember { DrawerTextMenu() }

    CompositionLocalProvider(
        LocalTextContextMenuToolbarProvider provides textMenu,
        LocalTextToolbar provides textMenu,
    ) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            // An overlay window has no back dispatcher, so Back is caught here: it closes an open sheet
            // first, then the drawer. The keyboard, when up, takes the first Back itself.
            .onPreviewKeyEvent { event ->
                if (event.key != Key.Back || !isOpen) return@onPreviewKeyEvent false
                if (event.type == KeyEventType.KeyUp) {
                    when {
                        switchTarget != null -> onResolveSwitch(SwitchChoice.Cancel)
                        sheet != null -> sheet = null
                        else -> dismiss()
                    }
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
            DrawerHeader(onDismiss = ::dismiss, onShare = onShare)

            ModeBar(
                pagePath = pagePath,
                isDirty = isDirty,
                onPickNote = { sheet = DrawerSheet.PickNote },
                onClosePage = { onRequestSwitch("") },
            )

            PendingEditor(
                blocks = pendingBlocks,
                resolveImage = remember(pagePath) { draftImageResolver(pagePath) },
                onTextChange = onPendingTextChange,
                onImageRemove = onPendingImageRemove,
                focusRequestId = focusRequestId,
                hint = when {
                    pagePath.isEmpty() -> "A loose page. Your words are kept safe as you type; Save makes it a note."
                    isDirty -> "Unsaved, but safe: Save writes your changes into ${File(pagePath).nameWithoutExtension}."
                    else -> "Saved. Edit anything; scans land at the end."
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )

            DrawerFooter(
                pendingBlocks = pendingBlocks,
                isDirty = isDirty,
                bottomInset = maxOf(navBottom - Gap.lg, 0.dp),
                onCopy = onCopy,
                onPaste = onPaste,
                onCapture = onCapture,
                onSave = { if (pagePath.isEmpty()) sheet = DrawerSheet.Save else onSave() },
            )
        }

        val unsavedTarget = switchTarget
        val current = when {
            unsavedTarget != null -> DrawerSheet.Unsaved
            else -> sheet
        }
        current?.let { shown ->
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .width(panelWidth)
                    .fillMaxHeight()
                    .imePadding(),
            ) {
                // One scaffold per kind of sheet, so one sheet's animation never acts on the next.
                key(shown) {
                SheetScaffold(
                    title = when (shown) {
                        DrawerSheet.Save -> "Save as a Note"
                        DrawerSheet.PickNote -> "Open a Note"
                        DrawerSheet.Unsaved -> "Unsaved Changes"
                    },
                    bottomInset = navBottom,
                    onDismiss = {
                        if (shown == DrawerSheet.Unsaved) onResolveSwitch(SwitchChoice.Cancel) else sheet = null
                    },
                ) { dismissSheet ->
                    when (shown) {
                        DrawerSheet.Save -> SaveSheet(
                            hasContent = pendingBlocks.any { it is NoteBlock.Image || (it is NoteBlock.Text && it.text.isNotBlank()) },
                            onSave = { folder, name ->
                                onSaveAsNew(folder, name)
                                dismissSheet()
                            },
                            onClose = dismissSheet,
                        )

                        DrawerSheet.PickNote -> NotePickerSheet(
                            onPick = { path ->
                                // Closed at once rather than animated: the switch may raise the Unsaved
                                // sheet in its place, and a closing animation finishing afterwards would
                                // dismiss that one instead.
                                sheet = null
                                onRequestSwitch(path)
                            },
                            onClose = dismissSheet,
                        )

                        DrawerSheet.Unsaved -> UnsavedSheet(
                            pagePath = pagePath,
                            targetPath = unsavedTarget.orEmpty(),
                            onChoice = { choice ->
                                if (choice == SwitchChoice.Save && pagePath.isEmpty()) {
                                    // A loose page needs a name before it can be saved.
                                    onResolveSwitch(SwitchChoice.Cancel)
                                    sheet = DrawerSheet.Save
                                } else {
                                    onResolveSwitch(choice)
                                }
                            },
                        )
                    }
                }
                }
            }
        }

        DrawerTextMenuHost(textMenu)
    }
    }
}

private enum class DrawerSheet { Save, PickNote, Unsaved }

/** What to do with unsaved changes when switching to another page. */
enum class SwitchChoice { Save, AppendToTarget, Discard, Cancel }

/**
 * Resolves a draft's picture references: absolute paths are captures still in staging, anything else
 * is a picture the page's note already holds.
 */
fun draftImageResolver(pagePath: String): (String) -> File? = { ref ->
    val file = File(ref)
    when {
        file.isAbsolute -> file
        pagePath.isEmpty() -> null
        else -> resolveAsset(File(pagePath), ref)
    }
}

// ── Header and page ───────────────────────────────────────────────────────────────────────────────

@Composable
private fun DrawerHeader(onDismiss: () -> Unit, onShare: () -> Unit) {
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
        IconAction(label = "Share to another app", onClick = onShare, size = 40.dp, tint = Ink) { tint ->
            IconShare(tint = tint, size = 18.dp)
        }
        IconAction(label = "Roll up the drawer", onClick = onDismiss, size = 40.dp, tint = Ink) { tint ->
            IconClose(tint = tint, size = 16.dp)
        }
    }
}

@Composable
private fun ModeBar(
    pagePath: String,
    isDirty: Boolean,
    onPickNote: () -> Unit,
    onClosePage: () -> Unit,
) {
    val bound = pagePath.isNotEmpty()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Gap.lg, end = Gap.sm, bottom = Gap.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(active = !isDirty)
        Spacer(Modifier.width(Gap.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (bound) File(pagePath).nameWithoutExtension else "Loose page",
                style = MaterialTheme.typography.labelLarge,
                color = if (bound) Ink else InkFaded,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (isDirty) {
                Text("unsaved", style = MaterialTheme.typography.labelSmall, color = Blood)
            }
        }
        if (bound) {
            IconAction(label = "Close this note", onClick = onClosePage, size = 36.dp, tint = InkFaded) { tint ->
                IconClose(tint = tint, size = 13.dp)
            }
        }
        RoyalButton(
            label = "Notes",
            kind = ButtonKind.Parchment,
            height = 38.dp,
            icon = { tint -> IconNote(tint = tint, size = 14.dp) },
            onClick = onPickNote,
        )
    }
    InkRule()
}

// ── Note content ──────────────────────────────────────────────────────────────────────────────────

@Composable
private fun PendingEditor(
    blocks: SnapshotStateList<NoteBlock>,
    resolveImage: (String) -> File?,
    onTextChange: (String, String) -> Unit,
    onImageRemove: (String) -> Unit,
    focusRequestId: Int,
    hint: String,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    var lastSize by remember { mutableIntStateOf(blocks.size) }

    LaunchedEffect(focusRequestId) {
        if (focusRequestId > 0) {
            // A window that has not been laid out yet has nothing to focus and the requester throws
            // rather than declining. Failing to raise the keyboard is not worth a crash.
            runCatching { focusRequester.requestFocus() }
        }
    }

    // A scan lands at the end of a note that may be long: follow it there, so the new picture is in view.
    LaunchedEffect(blocks.size) {
        if (blocks.size > lastSize && blocks.isNotEmpty()) listState.animateScrollToItem(blocks.lastIndex)
        lastSize = blocks.size
    }

    Column(modifier = modifier) {
        NoteDocument(
            blocks = blocks,
            resolveImage = resolveImage,
            editable = true,
            onTextChange = onTextChange,
            onImageRemove = onImageRemove,
            listState = listState,
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
    isDirty: Boolean,
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
            RoyalButton(
                label = "Save",
                kind = if (isDirty) ButtonKind.Primary else ButtonKind.Stone,
                height = 40.dp,
                onClick = onSave,
                modifier = Modifier.weight(1.1f),
            )
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

/** Saving the loose page as a new note: a name, and the folder it goes in. */
@Composable
private fun SaveSheet(
    hasContent: Boolean,
    onSave: (folderPath: String, name: String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var folder by remember { mutableStateOf(FileRepository.getNotesRoot(context).absolutePath) }

    Column(modifier = Modifier.fillMaxWidth()) {
        if (!hasContent) {
            Text(
                text = "The page is blank: write or scan something first.",
                style = MaterialTheme.typography.bodyMedium,
                color = InkFaded,
                modifier = Modifier.padding(vertical = Gap.md),
            )
            RoyalButton(label = "Close", kind = ButtonKind.Parchment, onClick = onClose, modifier = Modifier.fillMaxWidth())
            return@Column
        }
        InkField(
            value = name,
            onValueChange = { name = it },
            hint = "Note name",
            onDone = { if (name.isNotBlank()) onSave(folder, name.trim()) },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Gap.md))
        ScrollLabel("SAVE IN")
        FolderPicker(folderPath = folder, onFolderChange = { folder = it }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(Gap.sm))
        DialogButtons(
            confirmLabel = "Save",
            confirmEnabled = name.isNotBlank(),
            onConfirm = { onSave(folder, name.trim()) },
            onDismiss = onClose,
        )
    }
}

/** Unsaved changes on the way to another page: save them, append them there, or discard them. */
@Composable
private fun UnsavedSheet(
    pagePath: String,
    targetPath: String,
    onChoice: (SwitchChoice) -> Unit,
) {
    val pageName = if (pagePath.isEmpty()) "The loose page" else File(pagePath).nameWithoutExtension
    val targetName = if (targetPath.isEmpty()) "the loose page" else File(targetPath).nameWithoutExtension

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Gap.sm)) {
        Text(
            text = "$pageName has changes that are not saved. What should happen to them before opening $targetName?",
            style = MaterialTheme.typography.bodyMedium,
            color = Ink,
        )
        RoyalButton(
            label = if (pagePath.isEmpty()) "Save as a New Note…" else "Save, then Open",
            kind = ButtonKind.Primary,
            onClick = { onChoice(SwitchChoice.Save) },
            modifier = Modifier.fillMaxWidth(),
        )
        // Appending only makes sense for words that are not a note yet; a saved note's own text would be
        // copied into the other one wholesale.
        if (pagePath.isEmpty() && targetPath.isNotEmpty()) {
            RoyalButton(
                label = "Append to the End of $targetName",
                kind = ButtonKind.Parchment,
                onClick = { onChoice(SwitchChoice.AppendToTarget) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Gap.sm)) {
            RoyalButton(
                label = "Cancel",
                kind = ButtonKind.Parchment,
                onClick = { onChoice(SwitchChoice.Cancel) },
                modifier = Modifier.weight(1f),
            )
            RoyalButton(
                label = "Discard",
                kind = ButtonKind.Danger,
                onClick = { onChoice(SwitchChoice.Discard) },
                modifier = Modifier.weight(1f),
            )
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
                    text = libraryPath(context, folderPath),
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
                text = item.name.removeSuffix(".md"),
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
