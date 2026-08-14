package com.electrowiz.silentalarm

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.electrowiz.silentalarm.ui.screens.AlarmDashboardScreen
import com.electrowiz.silentalarm.ui.theme.SilentAlarmTheme
import com.electrowiz.silentalarm.ui.viewmodel.AlarmViewModel

/**
 * Single-activity host for the earphone alarm dashboard.
 *
 * Handles runtime permission requests, ringtone picking, and battery
 * optimization exemption. The UI is fully Compose-driven via [AlarmDashboardScreen].
 */
class MainActivity : AppCompatActivity() {

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

    /**
     * Battery-exemption request. The callback fires when the user returns
     * from the system page, so the status refresh does not depend on the ROM
     * returning focus to the activity.
     */
    private val batteryExemptionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.refreshStatus()
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
                        onPickRingtone = { launchRingtonePicker() },
                        onRequestNotificationPermission = ::requestNotificationPermission,
                        onRequestBatteryExemption = ::launchBatteryExemption
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

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            // No runtime permission before 13; notifications are toggled in app settings.
            openAppNotificationSettings()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        // The dialog only reappears after a plain "deny". Once the permission
        // is revoked via Settings (or "don't ask again"), launch() silently
        // returns without showing anything — open the settings page instead.
        if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            openAppNotificationSettings()
        }
    }

    private fun openAppNotificationSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            )
        } catch (e: Exception) {
            Log.e(TAG, "App notification settings unavailable", e)
        }
    }

    private fun launchBatteryExemption() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            viewModel.showBatteryAlreadyExempt()
            return
        }
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .apply { data = "package:$packageName".toUri() }
        try {
            batteryExemptionLauncher.launch(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Exemption request unavailable — opening settings list", e)
            try {
                batteryExemptionLauncher.launch(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                )
            } catch (e2: Exception) {
                Log.e(TAG, "Battery optimization settings unavailable", e2)
            }
        }
    }

}
