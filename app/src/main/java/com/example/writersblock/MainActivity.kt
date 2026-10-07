package com.example.writersblock

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.example.writersblock.service.OverlayService
import com.example.writersblock.theme.WritersBlockTheme
import com.example.writersblock.ui.importShare

class MainActivity : ComponentActivity() {

    private var hasOverlayPermission by mutableStateOf(false)
    private var hasNotificationPermission by mutableStateOf(false)
    private var isServiceRunning by mutableStateOf(false)

    /** A note made from something shared into the app, waiting to be opened in the editor. */
    private var sharedNotePath by mutableStateOf<String?>(null)

    // Standard runtime permission launcher for POST_NOTIFICATIONS (Android 13+)
    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            hasNotificationPermission = isGranted
            if (isGranted) {
                Toast.makeText(this, "Notification permission granted", Toast.LENGTH_SHORT).show()
                tryStartServiceIfPermissionsMet()
            } else {
                Toast.makeText(this, "Notification permission is required to run the background overlay", Toast.LENGTH_LONG).show()
            }
        }

    // Special settings permission launcher for SYSTEM_ALERT_WINDOW (Draw overlays)
    private val requestOverlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            updatePermissionStates()
            if (hasOverlayPermission) {
                Toast.makeText(this, "Overlay permission granted", Toast.LENGTH_SHORT).show()
                tryStartServiceIfPermissionsMet()
            } else {
                Toast.makeText(this, "Overlay permission is required to display floating widgets", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Light system-bar icons always: every screen is the night sky, whatever the system theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )

        updatePermissionStates()
        // Only on a fresh start: a recreated activity would import the same share a second time.
        if (savedInstanceState == null) handleShare(intent)

        setContent {
            WritersBlockTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainNavigation(
                        isServiceRunning = isServiceRunning,
                        onServiceToggle = { shouldStart ->
                            if (shouldStart) {
                                handleServiceActivation()
                            } else {
                                stopOverlayService()
                            }
                        },
                        hasOverlayPermission = hasOverlayPermission,
                        onRequestOverlayPermission = {
                            requestOverlayPermission()
                        },
                        hasNotificationPermission = hasNotificationPermission,
                        onRequestNotificationPermission = {
                            requestNotificationPermission()
                        },
                        sharedNotePath = sharedNotePath,
                        onSharedNoteOpened = { sharedNotePath = null }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** "Share → WritersBlock": file what was shared as a new note and open it. */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND && intent?.action != Intent.ACTION_SEND_MULTIPLE) return
        val note = importShare(this, intent)
        if (note == null) {
            Toast.makeText(this, "Nothing in that share could be kept", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Kept as ${note.nameWithoutExtension}", Toast.LENGTH_SHORT).show()
            sharedNotePath = note.absolutePath
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStates()
    }

    private fun updatePermissionStates() {
        // Check overlay drawing permission
        hasOverlayPermission = Settings.canDrawOverlays(this)
        
        // Check notification post permission (required on Android 13+)
        hasNotificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Automatic permission on older versions
        }
        
        // Synchronize service running state
        isServiceRunning = OverlayService.isRunning
    }

    private fun handleServiceActivation() {
        if (!hasNotificationPermission) {
            requestNotificationPermission()
        } else if (!hasOverlayPermission) {
            requestOverlayPermission()
        } else {
            startOverlayService()
        }
    }

    private fun tryStartServiceIfPermissionsMet() {
        if (hasOverlayPermission && hasNotificationPermission) {
            startOverlayService()
        }
    }

    private fun startOverlayService() {
        OverlayService.start(this)
        isServiceRunning = true
    }

    private fun stopOverlayService() {
        OverlayService.stop(this)
        isServiceRunning = false
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                Toast.makeText(this, "Please enable notification permission to keep the overlay active", Toast.LENGTH_LONG).show()
            }
            requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            hasNotificationPermission = true
        }
    }

    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Enable 'Display over other apps' in Settings", Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            requestOverlayPermissionLauncher.launch(intent)
        } else {
            hasOverlayPermission = true
        }
    }
}
