package com.example.writersblock.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.writersblock.data.NoteBlock
import com.example.writersblock.data.resolveAsset
import com.example.writersblock.theme.BorderGray
import com.example.writersblock.theme.Corner
import com.example.writersblock.theme.Gap
import com.example.writersblock.theme.PureBlack
import com.example.writersblock.theme.PureWhite
import com.example.writersblock.theme.TextMuted
import com.example.writersblock.theme.TextWhite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Renders a note as its blocks, text and images interleaved in position.
 *
 * This is presentation only — it never reads or writes the note file. Parsing, dirty-checking and
 * saving belong to the host, so the in-app editor and the overlay drawer can share this and keep
 * their own policies.
 *
 * The blocks are supplied by the caller and edits are reported back through [onTextChange] rather
 * than the list being rebuilt. Replacing every block on each keystroke would change their identities,
 * and since the lazy list keys on those identities, the text field would be recreated and the caret
 * would jump to the start after every character.
 *
 * @param resolveImage maps an image block's `ref` to the file to draw, or null when there is nothing
 *   to draw. A note resolves its refs against its own assets directory; the overlay drawer's pending
 *   blocks resolve them against staging, where the refs are absolute paths. See [noteImageResolver].
 * @param onTextChange reports an edit to one text block, identified by [NoteBlock.id].
 * @param onImageRemove when non-null, draws a delete affordance on each image. Only the overlay's
 *   pending list offers this — removing a block from a saved note is a different, larger operation.
 */
@Composable
fun NoteDocument(
    blocks: List<NoteBlock>,
    resolveImage: (String) -> File?,
    editable: Boolean,
    onTextChange: (id: String, text: String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    emptyHint: String = "start typing...",
    textColor: Color = TextWhite,
    onImageRemove: ((id: String) -> Unit)? = null,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = contentPadding,
    ) {
        if (blocks.isEmpty() && editable) {
            // Defensive: hosts seed an empty text block so the caret survives the first keystroke
            // (see seedIfEmpty). Reaching here means that did not happen, and an empty screen with no
            // way to type would be a worse outcome than a non-interactive hint.
            item(key = "empty-document") { Hint(emptyHint) }
        }

        items(blocks, key = { it.id }) { block ->
            when (block) {
                is NoteBlock.Text -> TextBlock(block, editable, emptyHint, textColor, onTextChange)
                is NoteBlock.Image -> ImageBlock(block, resolveImage(block.ref), onImageRemove)
            }
        }
    }
}

/**
 * Resolves refs the way a saved note does: against the note's own sibling assets directory.
 *
 * A note that has never been saved has no directory and therefore no images.
 */
fun noteImageResolver(noteFile: File?): (String) -> File? =
    { ref -> noteFile?.let { resolveAsset(it, ref) } }

/**
 * Resolves refs the way the overlay's pending blocks do: as absolute paths.
 *
 * A captured image sits in `cacheDir` staging until the user commits it to a note, and the pending
 * list has no note to resolve against, so the ref is simply where the file is.
 */
fun stagedImageResolver(): (String) -> File? = { ref -> File(ref) }

/** The document's text styling. Shared so the hint and real text line up exactly. */
private val EditorTextStyle = TextStyle(
    color = TextWhite,
    fontFamily = FontFamily.Monospace,
    fontSize = 14.sp,
    lineHeight = 20.sp,
)

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = EditorTextStyle.copy(color = Color(0xFF333333)),
        modifier = Modifier.defaultMinSize(minHeight = 20.dp),
    )
}

@Composable
private fun TextBlock(
    block: NoteBlock.Text,
    editable: Boolean,
    emptyHint: String,
    textColor: Color,
    onTextChange: (id: String, text: String) -> Unit,
) {
    if (!editable) {
        Text(text = block.text, style = EditorTextStyle.copy(color = textColor))
        return
    }

    BasicTextField(
        value = block.text,
        onValueChange = { onTextChange(block.id, it) },
        textStyle = EditorTextStyle.copy(color = textColor),
        cursorBrush = SolidColor(PureWhite),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        decorationBox = { innerTextField ->
            if (block.text.isEmpty()) Hint(emptyHint)
            innerTextField()
        },
        // Without a floor, clearing a block collapses it to zero height and it can no longer be
        // tapped to type into again.
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 20.dp),
    )
}

/**
 * Draws a captured image, or a legible placeholder when it cannot be shown.
 *
 * The placeholder matters as much as the picture: a reference can dangle after a file is moved, and
 * a screenshot of a FLAG_SECURE app decodes successfully but to an entirely black bitmap. Both look
 * like a rendering bug if the document simply shows nothing.
 */
@Composable
private fun ImageBlock(
    block: NoteBlock.Image,
    target: File?,
    onImageRemove: ((id: String) -> Unit)?,
) {
    val bitmap by produceState<Bitmap?>(initialValue = null, target) {
        value = if (target?.isFile == true) {
            withContext(Dispatchers.IO) { BitmapFactory.decodeFile(target.absolutePath) }
        } else {
            null
        }
    }

    val decoded = bitmap
    Box(modifier = Modifier.fillMaxWidth()) {
        if (decoded != null) {
            Image(
                bitmap = decoded.asImageBitmap(),
                contentDescription = block.alt.ifEmpty { "captured image" },
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 72.dp)
                    .border(width = 1.dp, color = BorderGray, shape = RoundedCornerShape(Corner.small)),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.padding(12.dp),
                ) {
                    Text(
                        text = "image unavailable",
                        style = EditorTextStyle.copy(color = TextMuted),
                    )
                    Text(
                        text = block.alt,
                        style = EditorTextStyle.copy(fontSize = 12.sp, color = TextMuted),
                    )
                }
            }
        }

        if (onImageRemove != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(Gap.xs)
                    .clip(RoundedCornerShape(Corner.small))
                    .background(PureBlack.copy(alpha = 0.55f), RoundedCornerShape(Corner.small))
                    .clickable { onImageRemove(block.id) }
                    .padding(horizontal = Gap.sm, vertical = Gap.xs),
            ) {
                IconClose(tint = TextWhite, size = 13.dp)
            }
        }
    }
}

// ── Editing the block list ────────────────────────────────────────────────────────────────────
//
// Every one of these mutates the list in place rather than handing back a rebuilt one. Block identity
// is what the lazy list keys on, and a rebuilt list has all-new identities — which recreates every
// text field and throws the caret to the start of the block mid-edit.

/**
 * Replaces the text of the block with [id], leaving every other block untouched.
 *
 * Only the one element is reassigned, so the rest keep their identity and the lazy list does not
 * rebuild them mid-edit.
 */
fun SnapshotStateList<NoteBlock>.replaceText(id: String, text: String) {
    val index = indexOfFirst { it.id == id }
    if (index < 0) return
    val block = this[index]
    if (block is NoteBlock.Text) this[index] = block.copy(text = text)
}

/**
 * Appends [text] to the end of the document.
 *
 * It lands in the trailing text block when there is one, so pasting into a half-written sentence
 * continues it rather than starting a new paragraph. The block keeps its identity, so the field is
 * not recreated.
 */
fun SnapshotStateList<NoteBlock>.appendText(text: String) {
    val last = lastOrNull()
    if (last is NoteBlock.Text) {
        replaceText(last.id, last.text + text)
    } else {
        add(NoteBlock.Text(text = text))
    }
}

/**
 * Appends a captured image, then an empty text block for the OCR text to land in.
 *
 * The trailing block is what makes the OCR text the thing being typed into rather than a sibling the
 * user has to go and find.
 *
 * A reference only counts as one when it occupies a whole line — [serializeNote] writes text verbatim,
 * so capturing into a half-written sentence would weld `![cap](…)` onto it and the next time the note
 * is parsed the picture would be prose. Closing the sentence first is the whole job of the first
 * branch.
 */
fun SnapshotStateList<NoteBlock>.appendImage(alt: String, ref: String) {
    val last = lastOrNull()
    if (last is NoteBlock.Text && last.text.isNotEmpty() && !last.text.endsWith("\n")) {
        replaceText(last.id, last.text + "\n")
    }
    add(NoteBlock.Image(alt = alt, ref = ref))
    add(NoteBlock.Text(text = ""))
}

/** Removes one block by id. Used to drop a capture the user decides against before saving. */
fun SnapshotStateList<NoteBlock>.removeBlock(id: String) {
    val index = indexOfFirst { it.id == id }
    if (index >= 0) removeAt(index)
}

/**
 * Guarantees at least one block, so an empty document has a caret to type into.
 *
 * A brand-new note parses to no blocks at all. Without this, the first character would have to
 * create the block it lands in — and creating it destroys the very text field being typed into,
 * closing the keyboard after one keystroke.
 */
fun SnapshotStateList<NoteBlock>.seedIfEmpty() {
    if (isEmpty()) add(NoteBlock.Text(text = ""))
}

/** A fresh, empty, editable document. */
fun emptyNoteBlocks(): SnapshotStateList<NoteBlock> =
    mutableStateListOf<NoteBlock>().apply { seedIfEmpty() }
