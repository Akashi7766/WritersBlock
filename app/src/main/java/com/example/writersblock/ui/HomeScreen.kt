package com.example.writersblock.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation3.runtime.NavKey
import com.example.writersblock.Dashboard
import com.example.writersblock.FileBrowser
import com.example.writersblock.FileEditor
import com.example.writersblock.data.FileRepository
import com.example.writersblock.data.NoteItem
import com.example.writersblock.service.OverlayService
import com.example.writersblock.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The way in.
 *
 * Three actions, each doing a different thing. It previously offered OPEN FOLDER and OPEN FILE as
 * separate cards, both of which navigated to the same browser — two doors into one room, which read as
 * a bug to anyone who pressed both.
 */
@Composable
fun HomeScreen(
    onNavigate: (NavKey) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var recentNotes by remember { mutableStateOf<List<NoteItem>>(emptyList()) }
    var serviceRunning by remember { mutableStateOf(OverlayService.isRunning) }

    LaunchedEffect(Unit) {
        serviceRunning = OverlayService.isRunning
        recentNotes = FileRepository.getRecentNotes(context)
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = SlateBlack
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = Gap.screenH),
            verticalArrangement = Arrangement.spacedBy(Gap.md)
        ) {
            item {
                Header(
                    serviceRunning = serviceRunning,
                    onSettings = { onNavigate(Dashboard) },
                    onEnableService = { onNavigate(Dashboard) }
                )
            }

            item {
                ScanTile(
                    enabled = serviceRunning,
                    onClick = {
                        // The capture runs inside the service, which owns the projection lifecycle. With
                        // it off there is nothing to hand the projection to, so the tap goes to settings.
                        if (OverlayService.capture(context)) {
                            Toast.makeText(context, "Choose what to scan", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(
                                context,
                                "Turn on the overlay service to scan",
                                Toast.LENGTH_LONG
                            ).show()
                            onNavigate(Dashboard)
                        }
                    }
                )
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(Gap.md)) {
                    SecondaryTile(
                        label = "BROWSE",
                        hint = "Notes and folders",
                        icon = { tint -> IconFolder(tint = tint) },
                        onClick = { onNavigate(FileBrowser(null)) },
                        modifier = Modifier.weight(1f)
                    )
                    SecondaryTile(
                        label = "NEW NOTE",
                        hint = "Start writing",
                        icon = { tint -> IconPlus(tint = tint) },
                        onClick = { onNavigate(FileEditor(null, null)) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            item {
                SectionLabel(
                    text = "RECENT NOTES",
                    modifier = Modifier.padding(top = Gap.lg, bottom = Gap.hairline)
                )
            }

            if (recentNotes.isEmpty()) {
                item {
                    EmptyState(
                        title = "Nothing written yet",
                        hint = "Tap NEW NOTE to begin, or scan a screen to keep what is on it."
                    )
                }
            } else {
                items(recentNotes) { note ->
                    RecentNoteRow(
                        note = note,
                        onClick = { onNavigate(FileEditor(note.path, null)) }
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(Gap.xl)) }
        }
    }
}

@Composable
private fun Header(
    serviceRunning: Boolean,
    onSettings: () -> Unit,
    onEnableService: () -> Unit
) {
    Column(modifier = Modifier.padding(top = Gap.xl, bottom = Gap.xs)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                SectionLabel(text = "NOTES")
                Text(
                    text = "WRITERSBLOCK",
                    style = MaterialTheme.typography.headlineMedium.copy(letterSpacing = 1.5.sp),
                    color = TextWhite,
                    maxLines = 1
                )
            }

            IconAction(
                label = "Settings",
                onClick = onSettings
            ) { tint -> IconSliders(tint = tint) }
        }

        // The scan tile is dead without the service, and the switch for it lives two screens away.
        // Saying so here is cheaper than letting the user find out by tapping.
        if (!serviceRunning) {
            Spacer(modifier = Modifier.height(Gap.sm))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(Corner.small))
                    .clickable(onClick = onEnableService)
                    .padding(vertical = Gap.xs, horizontal = Gap.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusDot(active = false)
                Spacer(modifier = Modifier.width(Gap.sm))
                Text(
                    text = "Overlay is off — tap to enable scanning",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
                )
            }
        }
    }
}

/** The one action this app exists for, so it gets the full width and the only filled surface. */
@Composable
private fun ScanTile(
    enabled: Boolean,
    onClick: () -> Unit
) {
    val container = if (enabled) PureWhite else SlateDark
    val content = if (enabled) PureBlack else TextMuted

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Corner.large))
            .background(container, RoundedCornerShape(Corner.large))
            .clickable(onClick = onClick)
            .padding(horizontal = Gap.lg, vertical = Gap.lg),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconScan(tint = content, size = 32.dp)
        Spacer(modifier = Modifier.width(Gap.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "SCAN SCREEN",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = content
            )
            Spacer(modifier = Modifier.height(Gap.hairline))
            Text(
                text = if (enabled) {
                    "Screenshot it, mark the part you want, keep the words"
                } else {
                    "Turn the overlay on to use this"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) PureBlack.copy(alpha = 0.7f) else TextMuted
            )
        }
        if (enabled) {
            IconChevronRight(tint = content, size = 20.dp)
        }
    }
}

@Composable
private fun SecondaryTile(
    label: String,
    hint: String,
    icon: @Composable (Color) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(Corner.large))
            .background(SlateDark, RoundedCornerShape(Corner.large))
            .border(BorderStroke(1.dp, BorderGray), RoundedCornerShape(Corner.large))
            .clickable(onClick = onClick)
            .padding(Gap.md),
        verticalArrangement = Arrangement.spacedBy(Gap.sm)
    ) {
        icon(TextWhite)
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = TextWhite
        )
        Text(
            text = hint,
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun RecentNoteRow(
    note: NoteItem,
    onClick: () -> Unit
) {
    val dateStr = remember(note.lastModified) {
        SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date(note.lastModified))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Corner.medium))
            .background(SlateDark, RoundedCornerShape(Corner.medium))
            .border(BorderStroke(1.dp, BorderGray), RoundedCornerShape(Corner.medium))
            .clickable(onClick = onClick)
            .padding(horizontal = Gap.md, vertical = Gap.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconNote(tint = TextMuted, size = 20.dp)
        Spacer(modifier = Modifier.width(Gap.md))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = note.name,
                style = MaterialTheme.typography.titleSmall,
                color = TextWhite,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (note.contentSnippet.isNotEmpty()) {
                Spacer(modifier = Modifier.height(Gap.hairline))
                Text(
                    text = note.contentSnippet,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.width(Gap.sm))
        Text(
            text = dateStr,
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted
        )
        Spacer(modifier = Modifier.width(Gap.xs))
        IconChevronRight(tint = TextMuted, size = 16.dp)
    }
}

// ── Shared pieces ─────────────────────────────────────────────────────────────────────────────
//
// Small enough to live here rather than in a file of their own, but used by every screen, so they
// are internal rather than private.

/** The small-caps eyebrow that names a screen or a section. */
@Composable
internal fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = TextMuted
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = modifier
    )
}

/** A circular glyph button, sized to stay a comfortable target without a Material ripple. */
@Composable
internal fun IconAction(
    label: String,
    onClick: () -> Unit,
    size: Dp = 40.dp,
    icon: @Composable (Color) -> Unit
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(Corner.medium))
            .clickable(onClick = onClick)
            .semanticsLabel(label),
        contentAlignment = Alignment.Center
    ) {
        icon(TextGray)
    }
}

/** A filled or hollow dot, for a granted or missing permission. */
@Composable
internal fun StatusDot(active: Boolean, size: Dp = 6.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .background(if (active) PureWhite else Danger, RoundedCornerShape(size / 2))
    )
}

@Composable
internal fun EmptyState(
    title: String,
    hint: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Gap.xxl, horizontal = Gap.lg),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = TextGray,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(Gap.xs))
        Text(
            text = hint,
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
            textAlign = TextAlign.Center
        )
    }
}

/** One text label for a screen reader, on a control that is only a drawn shape. */
internal fun Modifier.semanticsLabel(label: String): Modifier =
    this.semantics { contentDescription = label }
