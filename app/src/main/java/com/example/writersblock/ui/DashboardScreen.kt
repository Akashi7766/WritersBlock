package com.example.writersblock.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.writersblock.service.OverlayService
import com.example.writersblock.theme.Blood
import com.example.writersblock.theme.Ember
import com.example.writersblock.theme.Gap
import com.example.writersblock.theme.Gold
import com.example.writersblock.theme.Lavender
import com.example.writersblock.theme.Mist
import com.example.writersblock.theme.Outline
import com.example.writersblock.theme.Parchment
import com.example.writersblock.theme.Stone

/** Permissions, the drawer's on/off switch, and how to use it. */
@Composable
fun DashboardScreen(
    isServiceRunning: Boolean,
    onServiceToggle: (Boolean) -> Unit,
    hasOverlayPermission: Boolean,
    onRequestOverlayPermission: () -> Unit,
    hasNotificationPermission: Boolean,
    onRequestNotificationPermission: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    CastleBackdrop(modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Gap.screenH),
            verticalArrangement = Arrangement.spacedBy(Gap.md),
        ) {
            ScreenHeader(title = "Settings", onBack = onBack)

            PennantLabel("PERMISSIONS")
            // Both are needed before the switch does anything. Capture consent is not one of them:
            // Android asks for it on every scan, so there is nothing to grant up front.
            PermissionRow(
                title = "Draw over other apps",
                detail = "For the floating handle and the drawer",
                granted = hasOverlayPermission,
                onClick = onRequestOverlayPermission,
            )
            PermissionRow(
                title = "Notifications",
                detail = "Android requires one while the drawer runs",
                granted = hasNotificationPermission,
                onClick = onRequestNotificationPermission,
            )

            PennantLabel("THE DRAWER", modifier = Modifier.padding(top = Gap.md))
            StoneCard(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isServiceRunning) "Awake" else "Asleep",
                            style = MaterialTheme.typography.titleLarge,
                            color = Lavender,
                        )
                        Text(
                            text = if (isServiceRunning) {
                                "A scroll waits on the right edge of your screen."
                            } else {
                                "No handle, no scanning."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = Mist,
                        )
                    }
                    Switch(
                        checked = isServiceRunning,
                        onCheckedChange = onServiceToggle,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Parchment,
                            checkedTrackColor = Ember,
                            checkedBorderColor = Outline,
                            uncheckedThumbColor = Mist,
                            uncheckedTrackColor = Stone,
                            uncheckedBorderColor = Outline,
                        ),
                    )
                }
                if (isServiceRunning) {
                    Spacer(Modifier.height(Gap.md))
                    RoyalButton(
                        label = "Unroll the Drawer",
                        kind = ButtonKind.Primary,
                        onClick = { OverlayService.openDrawer(context) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            PennantLabel("HOW TO USE", modifier = Modifier.padding(top = Gap.md))
            StoneCard(modifier = Modifier.fillMaxWidth()) {
                listOf(
                    "Wake the drawer above. A small scroll appears on the right edge of the screen.",
                    "Tap the scroll, or flick it left, to unroll the drawer. Drag it up or down to move it.",
                    "In the drawer, Scan captures the screen. Drag a box over what you want, then Keep + Read.",
                    "The picture and its words land in the drawer. Fix any misread words by typing.",
                    "Save writes it to a new note, or binds the drawer to one you keep adding to.",
                ).forEachIndexed { index, step ->
                    Row(modifier = Modifier.padding(vertical = Gap.xs)) {
                        Text(
                            text = "${index + 1}",
                            style = MaterialTheme.typography.titleSmall,
                            color = Gold,
                            modifier = Modifier.width(22.dp),
                        )
                        Text(text = step, style = MaterialTheme.typography.bodySmall, color = Lavender)
                    }
                }
            }

            Spacer(Modifier.height(CastleWallHeight))
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    detail: String,
    granted: Boolean,
    onClick: () -> Unit,
) {
    StoneCard(modifier = Modifier.fillMaxWidth(), onClick = if (granted) null else onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (granted) IconCheck(tint = Gold, size = 20.dp) else StatusDot(active = false, size = 12.dp)
            Spacer(Modifier.width(Gap.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = Lavender)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = Mist)
            }
            Text(
                text = if (granted) "Granted" else "Grant",
                style = MaterialTheme.typography.labelMedium,
                color = if (granted) Mist else Blood,
            )
        }
    }
}
