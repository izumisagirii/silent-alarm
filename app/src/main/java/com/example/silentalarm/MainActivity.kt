package com.example.silentalarm

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.example.silentalarm.ui.screens.AlarmDashboardScreen
import com.example.silentalarm.ui.theme.SilentAlarmTheme
import com.example.silentalarm.ui.viewmodel.AlarmViewModel

/**
 * Single-activity host for the earphone alarm dashboard.
 *
 * Handles runtime permission requests, ringtone picking, and battery
 * optimization exemption. The UI is fully Compose-driven via [AlarmDashboardScreen].
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private val viewModel: AlarmViewModel by viewModels()

    // Ringtone file picker (audio/*) — global, applies to all alarms.
    private val ringtonePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.setRingtone(uri)
            Log.i(TAG, "Ringtone set: $uri")
        }
    }

    // Android 13+ notification permission.
    private val notificationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.i(TAG, "Notification permission: ${if (granted) "granted" else "denied"}")
    }

    // ── Lifecycle ────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestPermissions()

        setContent {
            SilentAlarmTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AlarmDashboardScreen(
                        viewModel = viewModel,
                        onPickRingtone = { launchRingtonePicker() }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshStatus()
    }

    // ── Permissions ──────────────────────────────────────────────────────

    private fun requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // ── Navigation ───────────────────────────────────────────────────────

    private fun launchRingtonePicker() {
        try {
            ringtonePicker.launch(arrayOf("audio/*"))
        } catch (e: Exception) {
            Log.e(TAG, "Ringtone picker failed", e)
        }
    }

}
