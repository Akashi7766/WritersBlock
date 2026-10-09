package com.example.writersblock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuItem
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuSession
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.writersblock.theme.Corner
import com.example.writersblock.theme.Gap
import com.example.writersblock.theme.Ink
import com.example.writersblock.theme.Parchment
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * The Cut / Copy / Paste / Select all bar for text in the drawer, drawn inside the drawer's own window.
 *
 * Compose normally hands this menu to Android's floating action mode, which the activity's window
 * provides. The drawer is a window a service added straight to the window manager, so there is no
 * such provider: the request was silently refused and selecting text in the drawer offered no way to
 * copy or paste it. [DrawerTextMenuHost] draws the menu instead, with the same items Compose builds.
 */
class DrawerTextMenu : TextContextMenuProvider, TextToolbar {

    /** What the menu offers and where: one label and action per item, the selection's bounds. */
    internal class Shown(
        val items: () -> List<Pair<String, () -> Unit>>,
        val bounds: (LayoutCoordinates) -> Rect,
    )

    internal var shown by mutableStateOf<Shown?>(null)
        private set

    // ── Legacy path: text fields that still ask for a TextToolbar ────────────────────────────────

    override val status: TextToolbarStatus
        get() = if (shown != null) TextToolbarStatus.Shown else TextToolbarStatus.Hidden

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {
        val items = listOfNotNull(
            onCutRequested?.let { "Cut" to it },
            onCopyRequested?.let { "Copy" to it },
            onPasteRequested?.let { "Paste" to it },
            onSelectAllRequested?.let { "Select all" to it },
        )
        // The rect is already in the drawer window's root coordinates, which is where the host sits.
        shown = Shown(items = { items }, bounds = { rect })
    }

    override fun hide() {
        shown = null
    }

    // ── New path: the context-menu provider ─────────────────────────────────────────────────────

    /** Suspends for as long as the menu is up, as the provider contract requires. */
    override suspend fun showTextContextMenu(dataProvider: TextContextMenuDataProvider) {
        suspendCancellableCoroutine { continuation ->
            val session = object : TextContextMenuSession {
                override fun close() {
                    shown = null
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }
            shown = Shown(
                items = {
                    dataProvider.data().components.filterIsInstance<TextContextMenuItem>()
                        .map { item -> item.label to { item.onClick(session) } }
                },
                bounds = { coordinates -> dataProvider.contentBounds(coordinates) },
            )
            continuation.invokeOnCancellation { shown = null }
        }
    }
}

/** Draws [menu] over the content it is placed above. Must fill the same area as the text it serves. */
@Composable
fun DrawerTextMenuHost(menu: DrawerTextMenu, modifier: Modifier = Modifier) {
    val current = menu.shown ?: return
    val density = LocalDensity.current
    var hostCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var hostSize by remember { mutableStateOf(IntSize.Zero) }
    var barSize by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { hostCoordinates = it }
            .onSizeChanged { hostSize = it }
    ) {
        val coordinates = hostCoordinates ?: return@Box
        val items = current.items()
        if (items.isEmpty()) return@Box

        Row(
            modifier = Modifier
                .offset {
                    // Above the selection when there is room, below it when there is not, and always
                    // inside the window.
                    val bounds = current.bounds(coordinates)
                    val anchorX = bounds.center.x
                    val gap = with(density) { Gap.sm.toPx() }
                    val above = bounds.top - barSize.height - gap
                    val y = if (above >= 0f) above else bounds.bottom + gap
                    val x = (anchorX - barSize.width / 2f)
                        .coerceIn(0f, (hostSize.width - barSize.width).coerceAtLeast(0).toFloat())
                    IntOffset(x.roundToInt(), y.coerceIn(0f, (hostSize.height - barSize.height).toFloat().coerceAtLeast(0f)).roundToInt())
                }
                .onSizeChanged { barSize = it }
                .background(Ink, RoundedCornerShape(Corner.medium))
                .border(1.5.dp, Parchment.copy(alpha = 0.4f), RoundedCornerShape(Corner.medium))
                .padding(horizontal = Gap.xs),
        ) {
            items.forEach { (label, action) ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = Parchment,
                    modifier = Modifier
                        .clickable { action() }
                        .padding(horizontal = Gap.md, vertical = Gap.md),
                )
            }
        }
    }
}
