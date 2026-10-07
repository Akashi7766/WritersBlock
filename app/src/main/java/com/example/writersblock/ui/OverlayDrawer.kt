package com.example.writersblock.ui

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.writersblock.data.FileRepository
import com.example.writersblock.data.NoteBlock
import com.example.writersblock.data.NoteItem
import com.example.writersblock.theme.BorderGray
import com.example.writersblock.theme.BorderBright
import com.example.writersblock.theme.Corner
import com.example.writersblock.theme.Danger
import com.example.writersblock.theme.Gap
import com.example.writersblock.theme.PureBlack
import com.example.writersblock.theme.PureWhite
import com.example.writersblock.theme.SlateBlack
import com.example.writersblock.theme.SlateDark
import com.example.writersblock.theme.TextGray
import com.example.writersblock.theme.TextMuted
import com.example.writersblock.theme.TextWhite
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

/** Where the drawer's contents should go when the user hits SAVE. */
sealed interface SaveRequest {
    /** Add the pending blocks to the end of the note the drawer is bound to. */
    data object AppendToBound : SaveRequest

    /** Overwrite the bound note with the pending blocks, discarding what it held. */
    data object ReplaceBound : SaveRequest

    /** Write the pending blocks to a new note. */
    data class AsNewNote(val folderPath: String?, val name: String) : SaveRequest
}

private val LabelStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 10.sp,
    fontWeight = FontWeight.Bold,
    letterSpacing = 0.1.sp,
)

private val BodyStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 13.sp,
    color = TextWhite,
)

/**
 * The overlay drawer's whole interface, in one overlay window.
 *
 * This is one composable rather than a view tree with an embedded `ComposeView`, for two reasons. The
 * editor is shared with the in-app one instead of being reimplemented ([NoteDocument]), and the drawer
 * becomes swipable: its position is an animatable the drag gesture writes to, which a `WindowManager`
 * translation could not be dragged through smoothly.
 *
 * The window is full screen so the scrim can cover the app behind it. While the drawer is closed the
 * service makes the entire window untouchable, so the scrim never steals a tap from the app
 * underneath — the panel is simply parked off the right edge.
 *
 * Sheets are drawn inside this composable rather than with `Dialog` or `ModalBottomSheet`. A dialog
 * needs a window token, and a service has none: `Dialog` from a service throws `BadTokenException`
 * unless its window type is set to an overlay type by hand. A sheet that is a sibling in the same `Box`
 * costs nothing and cannot fail that way.
 *
 * @param isOpen set by the service. The panel animates in when it becomes true; closing is driven from
 *   here so a swipe is not cut short by the window flags changing under it.
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

    // An overlay window is not reliably told about the system bars. A drawer whose header sits under
    // the status bar is unreadable, so take the larger of the reported inset and a sane minimum —
    // right whether or not insets arrive.
    val statusTop = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
    val navBottom = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
    val topInset = maxOf(statusTop, 12.dp)

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val panelWidth: Dp = minOf(340.dp, maxWidth * 0.92f)
        val panelWidthPx = with(density) { panelWidth.toPx() }

        val offsetX = remember(panelWidthPx) { Animatable(panelWidthPx) }
        val scope = rememberCoroutineScope()
        var sheet by remember { mutableStateOf<DrawerSheet?>(null) }

        /** Slides the panel out, then tells the service to make the window passive again. */
        fun dismiss() {
            scope.launch {
                offsetX.animateTo(panelWidthPx, tween(180))
                onRequestClose()
            }
        }

        LaunchedEffect(isOpen, panelWidthPx) {
            if (isOpen) {
                offsetX.animateTo(0f, tween(220))
            } else if (offsetX.value < panelWidthPx) {
                // Closed by something that did not animate — the capture flow hiding the window, or
                // the service restarting. Snap: the window is already non-interactive.
                offsetX.snapTo(panelWidthPx)
            }
        }

        // 1 when fully open, 0 when parked. Drives the scrim so it fades with the panel rather than
        // appearing a frame before it.
        //
        // The scrim's alpha is read inside the draw lambda, not here. Reading `offsetX.value` in the
        // composition would recompose the whole drawer — editor, note lists and all — on every frame
        // of the slide; a draw-phase read re-runs only the draw.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    val progress = 1f - (offsetX.value / panelWidthPx).coerceIn(0f, 1f)
                    if (progress > 0.01f) {
                        drawRect(PureBlack.copy(alpha = 0.55f * progress))
                    }
                }
                .pointerInput(Unit) { detectTapGestures { dismiss() } }
        )

        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(panelWidth)
                .fillMaxHeight()
                // The keyboard is drawn *above* an application overlay, so an unresized window ends up
                // with its composer behind it. If the window manager honours ADJUST_RESIZE the window
                // itself shrinks and this inset is zero, so the two never double up.
                .imePadding()
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .background(SlateBlack)
                // Drag right to put it away. Horizontal only, so the note lists inside still scroll
                // vertically, and a drag that starts inside a text field selects text as it should —
                // the field consumes it before this sees anything.
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        scope.launch {
                            offsetX.snapTo((offsetX.value + delta).coerceIn(0f, panelWidthPx))
                        }
                    },
                    onDragStopped = { velocity ->
                        val draggedFar = offsetX.value > panelWidthPx * 0.35f
                        if (draggedFar || velocity > 900f) {
                            dismiss()
                        } else {
                            scope.launch { offsetX.animateTo(0f, tween(180)) }
                        }
                    },
                )
        ) {
            DrawerHeader(onDismiss = ::dismiss, topInset = topInset)

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
                Hairline()
            }

            PendingEditor(
                blocks = pendingBlocks,
                onTextChange = onPendingTextChange,
                onImageRemove = onPendingImageRemove,
                focusRequestId = focusRequestId,
                hint = if (boundNotePath.isEmpty()) {
                    "scratch — nothing is kept until you hit SAVE"
                } else {
                    "SAVE appends this to ${File(boundNotePath).name}"
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )

            DrawerFooter(
                pendingBlocks = pendingBlocks,
                bottomInset = maxOf(navBottom, 6.dp),
                onCopy = onCopy,
                onPaste = onPaste,
                onCapture = onCapture,
                onSave = { sheet = DrawerSheet.Save },
            )
        }

        sheet?.let { current ->
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(panelWidth)
                    .fillMaxHeight(),
            ) {
                SheetScaffold(
                    title = when (current) {
                        DrawerSheet.Save -> "SAVE TO"
                        DrawerSheet.PickNote -> "BIND DRAWER TO NOTE"
                    },
                    onDismiss = { sheet = null },
                ) { dismissSheet ->
                    when (current) {
                        DrawerSheet.Save -> SaveSheet(
                            boundNotePath = boundNotePath,
                            hasContent = pendingBlocks.isNotEmpty(),
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

// ── Header and mode ───────────────────────────────────────────────────────────────────────────

@Composable
private fun DrawerHeader(onDismiss: () -> Unit, topInset: Dp) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = topInset)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Gap.md, end = Gap.xs, top = Gap.xs, bottom = Gap.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Grip. The whole panel drags, but nothing on screen said so.
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.padding(end = 10.dp),
            ) {
                repeat(3) {
                    Box(
                        modifier = Modifier
                            .width(14.dp)
                            .height(2.dp)
                            .background(TextMuted, RoundedCornerShape(1.dp))
                    )
                }
            }

            Text(
                text = "CAPTURE DRAWER",
                style = LabelStyle.copy(fontSize = 11.sp, letterSpacing = 0.18.sp),
                color = TextWhite,
                modifier = Modifier.weight(1f),
            )

            IconAction(label = "Close drawer", onClick = onDismiss, size = 34.dp) { tint ->
                IconClose(tint = tint, size = 15.dp)
            }
        }
        Hairline()
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
            .background(SlateDark)
            .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(active = bound, size = 7.dp)
        Spacer(Modifier.width(Gap.sm))

        Text(
            text = if (bound) File(boundNotePath).name else "SCRATCH",
            style = LabelStyle.copy(fontSize = 11.sp),
            color = if (bound) TextWhite else TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        SheetButton(label = if (bound) "CHANGE" else "BIND", onClick = onPickNote)
        if (bound) {
            Spacer(Modifier.width(Gap.xs))
            IconAction(label = "Unbind drawer", onClick = onUnbind, size = 30.dp) { tint ->
                IconClose(tint = tint, size = 13.dp)
            }
        }
    }
    Hairline()
}

// ── Note content ──────────────────────────────────────────────────────────────────────────────

@Composable
private fun BoundNotePreview(
    notePath: String,
    blocks: List<NoteBlock>,
    modifier: Modifier = Modifier,
) {
    val noteFile = remember(notePath) { File(notePath) }

    Column(modifier = modifier) {
        DrawerLabel(if (blocks.isEmpty()) "THE BOUND NOTE IS EMPTY" else "IN THIS NOTE")
        if (blocks.isEmpty()) {
            Text(
                text = "Anything you write below lands in it.",
                style = BodyStyle.copy(fontSize = 12.sp),
                color = TextMuted,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        } else {
            NoteDocument(
                blocks = blocks,
                resolveImage = remember(noteFile) { noteImageResolver(noteFile) },
                editable = false,
                onTextChange = { _, _ -> },
                textColor = TextGray,
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 14.dp),
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
        DrawerLabel("CAPTURE")
        NoteDocument(
            blocks = blocks,
            resolveImage = remember { stagedImageResolver() },
            editable = true,
            onTextChange = onTextChange,
            onImageRemove = onImageRemove,
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .focusRequester(focusRequester),
        )
        Text(
            text = hint,
            style = BodyStyle.copy(fontSize = 10.sp),
            color = TextMuted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .background(SlateDark)
                .padding(horizontal = 14.dp, vertical = 5.dp),
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

    Column(modifier = Modifier.fillMaxWidth()) {
        Hairline()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SlateDark)
                .padding(horizontal = Gap.md, vertical = Gap.sm),
            horizontalArrangement = Arrangement.spacedBy(Gap.sm),
        ) {
            SheetButton(label = "COPY", onClick = onCopy, modifier = Modifier.weight(1f))
            SheetButton(label = "PASTE", onClick = onPaste, modifier = Modifier.weight(1f))
            // SCAN is the reason the drawer exists and was previously one of three identical grey
            // buttons. It gets the icon and the brighter edge so it reads as the action, not an option.
            SheetButton(
                label = "SCAN",
                onClick = onCapture,
                modifier = Modifier.weight(1.2f),
                accent = true,
                icon = { tint -> IconScan(tint = tint, size = 13.dp) },
            )
        }

        Hairline()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SlateDark)
                .padding(start = Gap.md, end = Gap.md, top = Gap.sm)
                .padding(bottom = Gap.sm + bottomInset),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Gap.sm),
        ) {
            Text(
                text = buildString {
                    append(words).append(if (words == 1) " word" else " words")
                    if (images > 0) append("  ·  ").append(images).append(" img")
                },
                style = BodyStyle.copy(fontSize = 10.sp),
                color = TextMuted,
                modifier = Modifier.weight(1f),
            )

            SheetButton(
                label = "SAVE",
                onClick = onSave,
                primary = true,
                horizontalPadding = 20.dp,
            )
        }
    }
}

// ── Sheets ────────────────────────────────────────────────────────────────────────────────────

/**
 * A bottom sheet drawn inside the drawer's own window.
 *
 * [content] receives the dismiss action it should call, so a sheet that closes after doing something
 * still slides away instead of vanishing.
 */
@Composable
private fun SheetScaffold(
    title: String,
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

    LaunchedEffect(Unit) { progress.animateTo(1f, tween(220)) }

    Box(modifier = Modifier.fillMaxSize()) {
        // Same deferred read as the drawer's own scrim: the slide is a draw-phase change, not a
        // reason to recompose whatever the sheet is showing.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind { drawRect(PureBlack.copy(alpha = 0.5f * progress.value)) }
                .pointerInput(Unit) { detectTapGestures { close() } }
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .offset { IntOffset(0, ((1f - progress.value) * 700f).roundToInt()) }
                .clip(RoundedCornerShape(topStart = Corner.sheet, topEnd = Corner.sheet))
                .background(SlateBlack)
                .border(1.dp, BorderGray, RoundedCornerShape(topStart = Corner.sheet, topEnd = Corner.sheet))
                .padding(bottom = Gap.md),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = Gap.lg, end = Gap.xs, top = Gap.md, bottom = Gap.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = LabelStyle.copy(fontSize = 11.sp, letterSpacing = 0.18.sp),
                    color = TextWhite,
                    modifier = Modifier.weight(1f),
                )
                IconAction(label = "Close", onClick = { close() }, size = 34.dp) { tint ->
                    IconClose(tint = tint, size = 15.dp)
                }
            }
            Hairline()
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
    val boundName = if (boundNotePath.isEmpty()) "" else File(boundNotePath).name

    var newNoteName by remember { mutableStateOf("") }
    var destinationFolder by remember {
        mutableStateOf(FileRepository.getNotesRoot(context).absolutePath)
    }
    var pickingFolder by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        if (!hasContent) {
            Text(
                text = "Nothing to save yet.",
                style = BodyStyle.copy(fontSize = 12.sp),
                color = TextMuted,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            )
        } else {
            if (boundName.isNotEmpty()) {
                SheetRow(
                    title = "APPEND TO $boundName",
                    subtitle = "Adds to the end of the bound note. Captures move into its assets folder.",
                    primary = true,
                    onClick = { onSave(SaveRequest.AppendToBound) },
                )
                SheetRow(
                    title = "REPLACE $boundName",
                    subtitle = "Discards what the bound note holds and writes this instead.",
                    danger = true,
                    onClick = { onSave(SaveRequest.ReplaceBound) },
                )
            }

            if (pickingFolder) {
                DrawerLabel("CHOOSE A FOLDER")
                FolderList(
                    folderPath = destinationFolder,
                    onNavigate = { destinationFolder = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = relativePath(context, destinationFolder),
                        style = BodyStyle.copy(fontSize = 11.sp),
                        color = TextMuted,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    SheetButton(label = "DONE", primary = true, onClick = { pickingFolder = false })
                }
            } else {
                DrawerLabel("NEW NOTE")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .border(1.dp, BorderGray, RoundedCornerShape(Corner.small))
                            .padding(horizontal = 10.dp, vertical = 9.dp)
                    ) {
                        if (newNoteName.isEmpty()) {
                            Text("note_name", style = BodyStyle.copy(fontSize = 12.sp), color = TextMuted)
                        }
                        BasicTextField(
                            value = newNoteName,
                            onValueChange = { newNoteName = it },
                            singleLine = true,
                            textStyle = BodyStyle.copy(fontSize = 12.sp),
                            cursorBrush = SolidColor(PureWhite),
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Sentences
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    SheetButton(
                        label = "CREATE",
                        primary = newNoteName.isNotBlank(),
                        onClick = {
                            if (newNoteName.isNotBlank()) {
                                onSave(SaveRequest.AsNewNote(destinationFolder, newNoteName.trim()))
                            }
                        },
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 14.dp, top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "in " + relativePath(context, destinationFolder),
                        style = BodyStyle.copy(fontSize = 10.sp),
                        color = TextMuted,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    SheetButton(label = "CHANGE", onClick = { pickingFolder = true })
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
            SheetButton(label = "CANCEL", onClick = onClose, modifier = Modifier.weight(1f))
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
    val recents = remember(root) { FileRepository.getRecentNotes(context, 6) }

    LaunchedEffect(folderPath) {
        folderItems = FileRepository.listItems(context, folderPath)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = relativePath(context, folderPath),
                style = BodyStyle.copy(fontSize = 11.sp),
                color = TextMuted,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (folderPath != root.absolutePath) {
                SheetButton(
                    label = "UP",
                    onClick = {
                        folderPath = File(folderPath).parentFile?.absolutePath ?: root.absolutePath
                    },
                )
            }
        }
        Hairline()

        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
            if (folderPath == root.absolutePath && recents.isNotEmpty()) {
                item { DrawerLabel("RECENT") }
                items(recents, key = { "recent:" + it.path }) { note ->
                    NoteRow(item = note) { onPick(note.path) }
                }
                item { DrawerLabel("ALL NOTES") }
            }

            items(folderItems, key = { it.path }) { item ->
                if (item.isDirectory) {
                    FolderRow(name = item.name) { folderPath = item.path }
                } else {
                    NoteRow(item = item) { onPick(item.path) }
                }
            }

            if (folderItems.isEmpty()) {
                item {
                    Text(
                        text = "No notes in this folder.",
                        style = BodyStyle.copy(fontSize = 12.sp),
                        color = TextMuted,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    )
                }
            }
        }

        Hairline()
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
            SheetButton(
                label = "USE SCRATCH INSTEAD",
                onClick = { onPick("") },
                modifier = Modifier.weight(1f),
            )
        }
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
            SheetButton(label = "CANCEL", onClick = onClose, modifier = Modifier.weight(1f))
        }
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
                FolderRow(name = ".. up") {
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
                    text = "No folders yet — notes saved here land in the root.",
                    style = BodyStyle.copy(fontSize = 11.sp),
                    color = TextMuted,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}

// ── Small shared pieces ───────────────────────────────────────────────────────────────────────

@Composable
private fun DrawerLabel(text: String) {
    Text(
        text = text,
        style = LabelStyle.copy(fontSize = 9.sp, letterSpacing = 0.2.sp),
        color = TextMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 4.dp),
    )
}

/**
 * The drawer's one button.
 *
 * Denser than the in-app [PillButton] — the panel is 340dp wide and the labels are monospace — but it
 * shares the shapes and the three tones, so the two never drift apart.
 */
@Composable
private fun SheetButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    accent: Boolean = false,
    horizontalPadding: Dp = 10.dp,
    icon: (@Composable (Color) -> Unit)? = null,
) {
    val border = when {
        primary -> PureWhite
        accent -> BorderBright
        else -> BorderGray
    }

    Row(
        modifier = modifier
            .border(1.dp, border, RoundedCornerShape(Corner.small))
            .background(if (primary) PureWhite else Color.Transparent, RoundedCornerShape(Corner.small))
            .clickable(onClick = onClick)
            .padding(horizontal = horizontalPadding, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            icon(if (primary) PureBlack else TextWhite)
            Spacer(Modifier.width(Gap.xs))
        }
        Text(
            text = label,
            style = LabelStyle,
            color = if (primary) PureBlack else TextWhite,
            maxLines = 1,
        )
    }
}

@Composable
private fun SheetRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    primary: Boolean = false,
    danger: Boolean = false,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(
            text = title,
            style = LabelStyle.copy(fontSize = 12.sp),
            color = when {
                danger -> Danger
                primary -> PureWhite
                else -> TextGray
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(Gap.hairline))
        Text(text = subtitle, style = BodyStyle.copy(fontSize = 10.sp), color = TextMuted)
    }
}

@Composable
private fun FolderRow(name: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconFolder(tint = TextMuted, size = 15.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            text = name,
            style = BodyStyle,
            color = TextWhite,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun NoteRow(item: NoteItem, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp)
    ) {
        Text(
            text = item.name,
            style = BodyStyle.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
            color = TextWhite,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (item.contentSnippet.isNotEmpty()) {
            Text(
                text = item.contentSnippet,
                style = BodyStyle.copy(fontSize = 10.sp),
                color = TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** `/Ideas` rather than an absolute path, for the sheet breadcrumbs. */
private fun relativePath(context: Context, path: String): String {
    val root = FileRepository.getNotesRoot(context)
    val relative = runCatching { File(path).relativeTo(root).path }.getOrNull().orEmpty()
    return if (relative.isEmpty() || relative == ".") "/" else "/$relative"
}
