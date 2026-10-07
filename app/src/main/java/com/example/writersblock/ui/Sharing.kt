package com.example.writersblock.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.core.content.FileProvider
import com.example.writersblock.data.DocxWriter
import com.example.writersblock.data.NoteBlock
import com.example.writersblock.data.PdfWriter
import java.io.File
import java.io.OutputStream

/** What a note can be exported as. */
enum class ExportFormat(val extension: String, val mime: String, val label: String) {
    PDF("pdf", "application/pdf", "PDF"),
    WORD("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "Word"),
}

private fun uriFor(context: Context, file: File): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.files", file)

/** Writes the note in [format] to [out]. */
fun writeExport(
    format: ExportFormat,
    title: String,
    blocks: List<NoteBlock>,
    resolveImage: (String) -> File?,
    out: OutputStream,
) {
    when (format) {
        ExportFormat.PDF -> PdfWriter.write(title, blocks, resolveImage, out)
        ExportFormat.WORD -> DocxWriter.write(title, blocks, resolveImage, ::pixelSize, out)
    }
}

private fun pixelSize(file: File): Pair<Int, Int>? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, options)
    return if (options.outWidth > 0 && options.outHeight > 0) options.outWidth to options.outHeight else null
}

/** Exports to a temporary file and hands it to the share sheet: Drive, Gmail, a notes app, anything. */
fun shareExport(context: Context, format: ExportFormat, title: String, blocks: List<NoteBlock>, resolveImage: (String) -> File?) {
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    val file = File(dir, safeFileName(title) + "." + format.extension)
    file.outputStream().use { writeExport(format, title, blocks, resolveImage, it) }
    val uri = uriFor(context, file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = format.mime
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, title)
        clipData = ClipData.newRawUri(title, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    startChooser(context, send, "Send ${format.label} file")
}

/**
 * Shares a note's words and pictures with another app — Keep, Samsung Notes, OneNote, a message.
 *
 * The words travel as plain text and the pictures as attachments, which is the form every notes app
 * accepts from the share sheet.
 */
fun shareNote(context: Context, title: String, blocks: List<NoteBlock>, resolveImage: (String) -> File?) {
    val text = blocks.filterIsInstance<NoteBlock.Text>().joinToString("") { it.text }.trim()
    val images = blocks.filterIsInstance<NoteBlock.Image>()
        .mapNotNull { resolveImage(it.ref)?.takeIf(File::isFile) }
        .map { uriFor(context, it) }

    val send = if (images.isEmpty()) {
        Intent(Intent.ACTION_SEND).apply { type = "text/plain" }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(images))
            clipData = ClipData.newRawUri(title, images.first()).apply {
                images.drop(1).forEach { addItem(ClipData.Item(it)) }
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    send.putExtra(Intent.EXTRA_SUBJECT, title)
    send.putExtra(Intent.EXTRA_TITLE, title)
    if (text.isNotEmpty()) send.putExtra(Intent.EXTRA_TEXT, text)
    startChooser(context, send, "Send to another app")
}

private fun startChooser(context: Context, send: Intent, title: String) {
    val chooser = Intent.createChooser(send, title).apply {
        // The drawer shares from a service, which has no activity task to start one in.
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(chooser)
}

/** A title made safe to use as a file name. */
fun safeFileName(title: String): String =
    title.replace(Regex("[\\\\/:*?\"<>|\\n]"), " ").trim().ifEmpty { "note" }.take(80)

/**
 * Turns a "Share → WritersBlock" intent into a new note in the library, and returns it.
 *
 * Shared pictures are copied into the note's own assets folder straight away: the URI another app
 * hands over is only readable for as long as that share lasts.
 */
fun importShare(context: Context, intent: Intent): File? {
    val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
    val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
    val uris: List<Uri> = when (intent.action) {
        Intent.ACTION_SEND_MULTIPLE ->
            androidx.core.content.IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        else -> listOfNotNull(androidx.core.content.IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
    }
    if (text.isBlank() && uris.isEmpty()) return null

    val name = subject.ifBlank { text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty() }
        .let(::safeFileName).take(40).trim()
        .ifEmpty { "Shared " + java.text.SimpleDateFormat("d MMM HH-mm", java.util.Locale.getDefault()).format(java.util.Date()) }
    val note = com.example.writersblock.data.FileRepository.createFile(context, null, name) ?: return null

    val blocks = mutableListOf<NoteBlock>()
    if (text.isNotBlank()) blocks += NoteBlock.Text(text = text.trimEnd() + "\n")
    val assets = com.example.writersblock.data.FileRepository.ensureAssetsDir(note)
    uris.forEachIndexed { i, uri ->
        val ext = when (context.contentResolver.getType(uri)) {
            "image/jpeg" -> "jpg"
            "image/webp" -> "webp"
            else -> "png"
        }
        val target = File(assets ?: return@forEachIndexed, "shared_${System.currentTimeMillis()}_$i.$ext")
        val copied = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
        }.getOrNull() != null
        if (copied) {
            blocks += NoteBlock.Image(alt = "shared picture", ref = com.example.writersblock.data.FileRepository.refFor(note, target))
        }
    }
    com.example.writersblock.data.FileRepository.writeFile(note.absolutePath, com.example.writersblock.data.serializeNote(blocks))
    return note
}

/**
 * Export as PDF or Word: save it where you choose (Downloads, Drive…) through the system's file
 * picker, or hand it straight to another app.
 */
@androidx.compose.runtime.Composable
fun ExportDialog(
    title: String,
    blocks: List<NoteBlock>,
    resolveImage: (String) -> File?,
    onDismiss: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    fun saveTo(format: ExportFormat, uri: Uri?) {
        if (uri == null) return
        val ok = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { writeExport(format, title, blocks, resolveImage, it) }
        }.getOrNull() != null
        android.widget.Toast.makeText(
            context,
            if (ok) "Exported as ${format.label}" else "Could not export",
            android.widget.Toast.LENGTH_SHORT,
        ).show()
        if (ok) onDismiss()
    }

    val savePdf = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument(ExportFormat.PDF.mime)
    ) { saveTo(ExportFormat.PDF, it) }
    val saveWord = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument(ExportFormat.WORD.mime)
    ) { saveTo(ExportFormat.WORD, it) }

    ScrollDialog(title = "Export", onDismiss = onDismiss) {
        ExportFormat.entries.forEach { format ->
            ScrollLabel(if (format == ExportFormat.PDF) "PDF DOCUMENT" else "WORD DOCUMENT (.DOCX)")
            androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(com.example.writersblock.theme.Gap.xs))
            androidx.compose.foundation.layout.Row(
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(com.example.writersblock.theme.Gap.sm),
            ) {
                RoyalButton(
                    label = "Save",
                    kind = ButtonKind.Primary,
                    modifier = androidx.compose.ui.Modifier.weight(1f),
                    onClick = {
                        val name = safeFileName(title) + "." + format.extension
                        if (format == ExportFormat.PDF) savePdf.launch(name) else saveWord.launch(name)
                    },
                )
                RoyalButton(
                    label = "Share",
                    kind = ButtonKind.Parchment,
                    modifier = androidx.compose.ui.Modifier.weight(1f),
                    onClick = {
                        shareExport(context, format, title, blocks, resolveImage)
                        onDismiss()
                    },
                )
            }
            androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(com.example.writersblock.theme.Gap.md))
        }
        RoyalButton(
            label = "Close",
            kind = ButtonKind.Parchment,
            modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
            onClick = onDismiss,
        )
    }
}
