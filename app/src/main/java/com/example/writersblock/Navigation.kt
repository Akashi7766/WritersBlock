package com.example.writersblock

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.example.writersblock.ui.DashboardScreen
import com.example.writersblock.ui.FileBrowserScreen
import com.example.writersblock.ui.FileEditorScreen
import com.example.writersblock.ui.HomeScreen

@Composable
fun MainNavigation(
    isServiceRunning: Boolean,
    onServiceToggle: (Boolean) -> Unit,
    hasOverlayPermission: Boolean,
    onRequestOverlayPermission: () -> Unit,
    hasNotificationPermission: Boolean,
    onRequestNotificationPermission: () -> Unit
) {
    val backStack = rememberNavBackStack(Home)

    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryProvider = entryProvider {
            entry<Home> {
                HomeScreen(
                    serviceRunning = isServiceRunning,
                    onNavigate = { dest -> backStack.add(dest) }
                )
            }

            entry<FileBrowser> { key ->
                FileBrowserScreen(
                    folderPath = key.folderPath,
                    onNavigate = { dest -> backStack.add(dest) },
                    onBack = { backStack.removeLastOrNull() }
                )
            }

            entry<FileEditor> { key ->
                FileEditorScreen(
                    filePath = key.filePath,
                    folderPath = key.folderPath,
                    onBack = { backStack.removeLastOrNull() }
                )
            }

            entry<Dashboard> {
                DashboardScreen(
                    isServiceRunning = isServiceRunning,
                    onServiceToggle = onServiceToggle,
                    hasOverlayPermission = hasOverlayPermission,
                    onRequestOverlayPermission = onRequestOverlayPermission,
                    hasNotificationPermission = hasNotificationPermission,
                    onRequestNotificationPermission = onRequestNotificationPermission,
                    onBack = { backStack.removeLastOrNull() }
                )
            }
        }
    )
}
