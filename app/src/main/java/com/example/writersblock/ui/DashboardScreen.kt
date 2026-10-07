package com.example.writersblock.ui

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.writersblock.data.FileRepository
import com.example.writersblock.data.NoteItem
import com.example.writersblock.service.OverlayService
import com.example.writersblock.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DashboardScreen(
    isServiceRunning: Boolean,
    onServiceToggle: (Boolean) -> Unit,
    hasOverlayPermission: Boolean,
    onRequestOverlayPermission: () -> Unit,
    hasNotificationPermission: Boolean,
    onRequestNotificationPermission: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var recentCaptures by remember { mutableStateOf<List<NoteItem>>(emptyList()) }

    LaunchedEffect(Unit) {
        recentCaptures = FileRepository.getRecentNotes(context, 4)
    }

    val allGranted = hasOverlayPermission && hasNotificationPermission

    Surface(
        modifier = modifier.fillMaxSize(),
        color = SlateBlack
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = Gap.screenH),
            verticalArrangement = Arrangement.spacedBy(Gap.md),
            contentPadding = PaddingValues(bottom = Gap.xl)
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Gap.md, bottom = Gap.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconAction(label = "Back", onClick = onBack) { tint ->
                        IconBack(tint = tint)
                    }
                    Spacer(modifier = Modifier.width(Gap.sm))
                    Column {
                        SectionLabel(text = "SETTINGS")
                        Text(
                            text = "OVERLAY",
                            style = MaterialTheme.typography.titleLarge,
                            color = TextWhite
                        )
                    }
                }
            }

            // Permissions first: everything below them is inert until they are granted, and the switch
            // in particular will look broken rather than restricted if it is flipped without them.
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(Gap.sm)) {
                    PermissionChip(
                        label = "OVERLAY",
                        detail = if (hasOverlayPermission) "Granted" else "Required",
                        granted = hasOverlayPermission,
                        onClick = onRequestOverlayPermission,
                        modifier = Modifier.weight(1f)
                    )
                    PermissionChip(
                        label = "CAPTURE",
                        detail = if (hasNotificationPermission) "Granted" else "Required",
                        granted = hasNotificationPermission,
                        onClick = onRequestNotificationPermission,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            if (!allGranted) {
                item {
                    Notice(
                        text = "Scanning needs both permissions. Tap a chip above to grant it.",
                        tone = Tone.Warning
                    )
                }
            }

            item {
                ServiceCard(
                    isServiceRunning = isServiceRunning,
                    onServiceToggle = onServiceToggle
                )
            }

            item {
                HowToCard()
            }

            item {
                SectionLabel(
                    text = "RECENT CAPTURES",
                    modifier = Modifier.padding(top = Gap.md)
                )
            }

            if (recentCaptures.isEmpty()) {
                item {
                    EmptyState(
                        title = "No captures yet",
                        hint = "Scanned screens are saved into your notes and listed here."
                    )
                }
            } else {
                items(recentCaptures) { capture ->
                    CaptureRow(note = capture)
                }
            }
        }
    }
}

/** A permission, its state, and the tap that fixes it — as one control. */
@Composable
private fun PermissionChip(
    label: String,
    detail: String,
    granted: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val border = if (granted) BorderBright else Danger.copy(alpha = 0.5f)

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(Corner.medium))
            .background(SlateDark, RoundedCornerShape(Corner.medium))
            .border(BorderStroke(1.dp, border), RoundedCornerShape(Corner.medium))
            .clickable(onClick = onClick)
            .padding(horizontal = Gap.md, vertical = Gap.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (granted) {
            IconCheck(tint = TextWhite, size = 14.dp)
        } else {
            StatusDot(active = false, size = 8.dp)
        }
        Spacer(modifier = Modifier.width(Gap.sm))
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = TextWhite
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = if (granted) TextMuted else Danger
            )
        }
    }
}

@Composable
private fun ServiceCard(
    isServiceRunning: Boolean,
    onServiceToggle: (Boolean) -> Unit
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Corner.large))
            .background(SlateDark, RoundedCornerShape(Corner.large))
            .border(BorderStroke(1.dp, BorderGray), RoundedCornerShape(Corner.large))
            .padding(Gap.md)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Overlay service",
                    style = MaterialTheme.typography.titleSmall,
                    color = TextWhite
                )
                Spacer(modifier = Modifier.height(Gap.hairline))
                Text(
                    text = if (isServiceRunning) {
                        "Running — the handle is on your screen edge"
                    } else {
                        "Off — no scanning, no floating drawer"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
                )
            }

            Switch(
                checked = isServiceRunning,
                onCheckedChange = onServiceToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = PureBlack,
                    checkedTrackColor = PureWhite,
                    uncheckedThumbColor = TextMuted,
                    uncheckedTrackColor = SlateBlack,
                    uncheckedBorderColor = BorderGray
                )
            )
        }

        if (isServiceRunning) {
            Spacer(modifier = Modifier.height(Gap.md))
            Row(horizontalArrangement = Arrangement.spacedBy(Gap.sm)) {
                FilledAction(
                    label = "OPEN DRAWER",
                    icon = { tint -> IconChevronRight(tint = tint, size = 14.dp) },
                    onClick = { OverlayService.openDrawer(context) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun HowToCard() {
    val steps = listOf(
        "Turn the overlay service on. A handle appears against the right edge of the screen.",
        "Tap the handle to slide the drawer out, drag it to move it, flick it left to open it.",
        "Tap SCAN on the handle — or SCAN SCREEN from the home screen — then drag a box over what you want read.",
        "KEEP + READ puts the picture and its text into the drawer.",
        "BIND the drawer to a note to keep adding to it from anywhere, then SAVE when you are done."
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Corner.large))
            .background(SlateDark, RoundedCornerShape(Corner.large))
            .border(BorderStroke(1.dp, BorderGray), RoundedCornerShape(Corner.large))
            .padding(Gap.md),
        verticalArrangement = Arrangement.spacedBy(Gap.sm)
    ) {
        SectionLabel(text = "HOW TO USE")
        steps.forEachIndexed { index, step ->
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = "${index + 1}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    modifier = Modifier.width(Gap.lg)
                )
                Text(
                    text = step,
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                    color = TextGray,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun CaptureRow(note: NoteItem) {
    val dateStr = remember(note.lastModified) {
        SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date(note.lastModified))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Corner.medium))
            .background(SlateDark, RoundedCornerShape(Corner.medium))
            .border(BorderStroke(1.dp, BorderGray), RoundedCornerShape(Corner.medium))
            .padding(Gap.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconNote(tint = TextMuted, size = 18.dp)
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
    }
}

// ── Shared pieces ─────────────────────────────────────────────────────────────────────────────

/** Which way a notice reads: a warning to act on, or a neutral remark. */
internal enum class Tone { Warning, Neutral }

@Composable
internal fun Notice(text: String, tone: Tone) {
    val accent = if (tone == Tone.Warning) Danger else BorderBright

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Corner.medium))
            .background(SlateBlack, RoundedCornerShape(Corner.medium))
            .border(BorderStroke(1.dp, accent.copy(alpha = 0.6f)), RoundedCornerShape(Corner.medium))
            .padding(horizontal = Gap.md, vertical = Gap.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatusDot(active = false, size = 8.dp)
        Spacer(modifier = Modifier.width(Gap.sm))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = TextGray
        )
    }
}

/** The one filled button on a screen. Used where there is a single obvious next action. */
@Composable
internal fun FilledAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: (@Composable (Color) -> Unit)? = null
) {
    Row(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(Corner.medium))
            .background(PureWhite, RoundedCornerShape(Corner.medium))
            .clickable(onClick = onClick)
            .padding(horizontal = Gap.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = PureBlack
        )
        if (icon != null) {
            Spacer(modifier = Modifier.width(Gap.sm))
            icon(PureBlack)
        }
    }
}
