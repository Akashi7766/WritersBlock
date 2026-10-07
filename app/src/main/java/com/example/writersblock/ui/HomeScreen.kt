package com.example.writersblock.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import com.example.writersblock.Dashboard
import com.example.writersblock.FileBrowser
import com.example.writersblock.FileEditor
import com.example.writersblock.data.FileRepository
import com.example.writersblock.data.NoteItem
import com.example.writersblock.service.OverlayService
import com.example.writersblock.theme.Gap
import com.example.writersblock.theme.Gold
import com.example.writersblock.theme.Lavender
import com.example.writersblock.theme.Mist
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The great hall: scan, browse, start writing, and what was written last. */
@Composable
fun HomeScreen(
    serviceRunning: Boolean,
    onNavigate: (NavKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var recentNotes by remember { mutableStateOf<List<NoteItem>>(emptyList()) }
    LaunchedEffect(Unit) {
        recentNotes = FileRepository.getRecentNotes(context)
    }

    CastleBackdrop(modifier) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = Gap.screenH),
            verticalArrangement = Arrangement.spacedBy(Gap.md),
        ) {
            item {
                Header(
                    serviceRunning = serviceRunning,
                    onSettings = { onNavigate(Dashboard) },
                )
            }

            item {
                RoyalButton(
                    label = "Scan the Screen",
                    kind = ButtonKind.Primary,
                    height = 64.dp,
                    icon = { tint -> IconScan(tint = tint, size = 24.dp) },
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        // The capture needs the service to hold the projection, so without it the tap
                        // goes to the switch rather than doing nothing.
                        if (!OverlayService.capture(context)) {
                            Toast.makeText(context, "Wake the drawer first to scan", Toast.LENGTH_LONG).show()
                            onNavigate(Dashboard)
                        }
                    },
                )
                Text(
                    text = if (serviceRunning) {
                        "Mark a part of any screen and keep its picture and words."
                    } else {
                        "The drawer is asleep. Wake it in Settings to scan."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Mist,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Gap.sm),
                )
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(Gap.md)) {
                    HallTile(
                        label = "Library",
                        hint = "Notes and folders",
                        icon = { IconFolder(tint = Gold, size = 26.dp) },
                        onClick = { onNavigate(FileBrowser(null)) },
                        modifier = Modifier.weight(1f),
                    )
                    HallTile(
                        label = "New Note",
                        hint = "Start writing",
                        icon = { IconQuill(tint = Gold, size = 26.dp) },
                        onClick = { onNavigate(FileEditor(null, null)) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            item { PennantLabel("RECENT NOTES", modifier = Modifier.padding(top = Gap.lg)) }

            if (recentNotes.isEmpty()) {
                item {
                    EmptyState(
                        title = "Nothing written yet",
                        hint = "Start a new note, or scan a screen to keep what is on it.",
                    )
                }
            } else {
                items(recentNotes, key = { it.path }) { note ->
                    NoteRow(
                        title = note.name,
                        snippet = note.contentSnippet,
                        trailing = shortDate(note.lastModified),
                        isFolder = false,
                        onClick = { onNavigate(FileEditor(note.path, null)) },
                    )
                }
            }

            // Room for the castle wall, so the last note is not drawn over the towers.
            item { Spacer(Modifier.height(CastleWallHeight)) }
        }
    }
}

@Composable
private fun Header(serviceRunning: Boolean, onSettings: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = Gap.md),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            IconAction(
                label = "Settings",
                onClick = onSettings,
                icon = { tint -> IconSliders(tint = tint, size = 22.dp) },
            )
        }

        Banner(text = "WritersBlock", style = MaterialTheme.typography.headlineMedium)

        if (!serviceRunning) {
            Spacer(Modifier.height(Gap.md))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onSettings)
                    .padding(horizontal = Gap.md, vertical = Gap.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(active = false)
                Spacer(Modifier.width(Gap.sm))
                Text("The drawer is asleep: tap to wake it", style = MaterialTheme.typography.bodySmall, color = Mist)
            }
        }
    }
}

@Composable
private fun HallTile(
    label: String,
    hint: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    StoneCard(modifier = modifier, onClick = onClick, contentPadding = Gap.lg) {
        icon()
        Spacer(Modifier.height(Gap.md))
        Text(label, style = MaterialTheme.typography.titleMedium, color = Lavender)
        Text(hint, style = MaterialTheme.typography.bodySmall, color = Mist, maxLines = 1)
    }
}

internal fun shortDate(millis: Long): String =
    SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(millis))
