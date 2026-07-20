package com.electrowiz.silentalarm.service

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.electrowiz.silentalarm.R
import com.electrowiz.silentalarm.data.AlarmPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Quick Settings tile for SilentAlarm.
 *
 * **Tap** — toggle all alarms on/off (master switch).
 * **Long-press** — system-controlled (opens app info on most ROMs,
 *   no public API to override).
 * **Visual state** — ACTIVE (highlighted) when any alarm is enabled,
 *   INACTIVE (gray) when all are disabled.
 *
 * Bidirectional sync: the tile updates on every shade-pull ([onStartListening]),
 * and the app pushes updates via [requestTileUpdate] whenever alarms change.
 */
class AlarmTileService : TileService() {

    companion object {
        private const val TAG = "AlarmTileService"

        /**
         * Force the system to call [onStartListening] so the tile reflects
         * the latest alarm state immediately. Called from ViewModel after
         * any alarm mutation.
         */
        fun requestTileUpdate(context: Context) {
            requestListeningState(
                context,
                ComponentName(context, AlarmTileService::class.java)
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var preferences: AlarmPreferences

    override fun onCreate() {
        super.onCreate()
        preferences = AlarmPreferences(this)
    }

    /** Initialize tile to inactive state when first added. */
    override fun onTileAdded() {
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = getString(R.string.tile_label)
            updateTile()
        }
    }

    /** Refreshed every time the user pulls down the notification shade. */
    override fun onStartListening() {
        scope.launch {
            try {
                val hasEnabled = preferences.getAlarms().first().any { it.enabled }
                setTileState(hasEnabled)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read alarm state", e)
            }
        }
    }

    /** Tap = toggle all alarms as a master switch. */
    override fun onClick() {
        scope.launch {
            try {
                val alarms = preferences.getAlarms().first()
                val newState = !alarms.any { it.enabled }
                alarms.forEach { preferences.toggleAlarm(it.id, newState) }
                setTileState(newState)
                Log.i(TAG, "Master toggle: all alarms ${if (newState) "ON" else "OFF"}")
            } catch (e: Exception) {
                Log.e(TAG, "Toggle failed", e)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun setTileState(active: Boolean) {
        qsTile?.apply {
            state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = getString(R.string.tile_label)
            subtitle = if (active) getString(R.string.tile_active)
                       else getString(R.string.tile_inactive)
            updateTile()
        }
    }
}
