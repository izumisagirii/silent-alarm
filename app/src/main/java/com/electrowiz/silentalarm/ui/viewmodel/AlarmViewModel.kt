package com.electrowiz.silentalarm.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.electrowiz.silentalarm.daemon.ShizukuDaemonManager
import com.electrowiz.silentalarm.data.AlarmItem
import com.electrowiz.silentalarm.data.AlarmPreferences
import com.electrowiz.silentalarm.data.AlarmScheduler
import com.electrowiz.silentalarm.data.NoEarphoneAction
import com.electrowiz.silentalarm.data.TimeoutAction
import com.electrowiz.silentalarm.service.AlarmTileService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
//import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.net.toUri

/**
 * Central ViewModel.
 *
 * Reads from DataStore (alarms + settings), exposes StateFlows for the UI,
 * and handles side effects: AlarmManager scheduling, Shizuku anti-kill,
 * QS tile sync. All blocking I/O runs on Dispatchers.IO.
 */
class AlarmViewModel(application: Application) : AndroidViewModel(application) {

    private val preferences = AlarmPreferences(application)
    private val scheduler = AlarmScheduler(application)
    private val shizukuManager = ShizukuDaemonManager(application)

    // ── Alarm List (from DataStore, sorted by time) ──────────────────────

    val alarms: StateFlow<List<AlarmItem>> = preferences.getAlarms()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ── Global Settings ──────────────────────────────────────────────────

    val earphoneVolume: StateFlow<Int> = preferences.earphoneVolume
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 80)

    val speakerVolume: StateFlow<Int> = preferences.speakerVolume
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 60)

    val noEarphoneAction: StateFlow<NoEarphoneAction> = preferences.noEarphoneAction
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), NoEarphoneAction.VIBRATE_ONLY)

    val globalRingtoneUri: StateFlow<String> = preferences.globalRingtoneUri
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    // ── Timeout Settings ──────────────────────────────────────────────

    val timeoutSeconds: StateFlow<Int> = preferences.timeoutSeconds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 300)

    val timeoutAction: StateFlow<TimeoutAction> = preferences.timeoutAction
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TimeoutAction.STOP)

    // ── System Status (polled) ───────────────────────────────────────────

    private val _shizukuConnected = MutableStateFlow(false)
    val shizukuConnected: StateFlow<Boolean> = _shizukuConnected.asStateFlow()

    private val _shizukuPermitted = MutableStateFlow(false)
    val shizukuPermitted: StateFlow<Boolean> = _shizukuPermitted.asStateFlow()

    // ── UI State ─────────────────────────────────────────────────────────

    private val _showTimePicker = MutableStateFlow(false)
    val showTimePicker: StateFlow<Boolean> = _showTimePicker.asStateFlow()

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    private var editingAlarmId: String? = null

    private var shizukuTweaksApplied = false

    // ── Init ──────────────────────────────────────────────────────────────

    init {
        refreshStatusFlags()
        // Keep foreground service alive for basic process protection
        viewModelScope.launch {
//            if (preferences.getAlarms().first().any { it.enabled }) {
                scheduler.startIdleService()
//            }
        }
    }

    // ── Status Refresh ───────────────────────────────────────────────────

    /** Fast (non-blocking) status read — safe for init/onResume. */
    private fun refreshStatusFlags() {
        _shizukuConnected.value = shizukuManager.isShizukuAvailable()
        _shizukuPermitted.value = shizukuManager.isShizukuPermitted()
    }

    /**
     * Full refresh: flags + auto-apply Shizuku tweaks (runs I/O on
     * background thread so it never blocks the UI).
     */
    fun refreshStatus() {
        refreshStatusFlags()

        // Auto-apply anti-kill tweaks on first successful Shizuku connection
        if (_shizukuConnected.value && _shizukuPermitted.value && !shizukuTweaksApplied) {
            shizukuTweaksApplied = true
            viewModelScope.launch {
                withContext(Dispatchers.IO) {
                    shizukuManager.stopWatchdogDaemon()
                    shizukuManager.applyAntiKillingTweaks()
                    shizukuManager.startWatchdogDaemon()
                }
                _snackbarMessage.value = "Shizuku connected — anti-kill protection active"
            }
        }
    }

    fun clearSnackbar() { _snackbarMessage.value = null }

    /** Push current alarm state to the QS tile. */
    private fun syncTile() {
        AlarmTileService.requestTileUpdate(getApplication())
    }

    // ── Alarm CRUD ───────────────────────────────────────────────────────

    fun addAlarm(hour: Int, minute: Int, label: String = "") {
        viewModelScope.launch {
            val item = AlarmItem(hour = hour, minute = minute, label = label)
            preferences.addAlarm(item)
            scheduler.scheduleOne(item)
            scheduler.startIdleService()
            _snackbarMessage.value = "Alarm set for %02d:%02d".format(hour, minute)
            syncTile()
        }
    }

    fun updateAlarm(updated: AlarmItem) {
        viewModelScope.launch {
            preferences.updateAlarm(updated)
            scheduler.cancelAlarm(updated.id)
            if (updated.enabled) {
                scheduler.scheduleOne(updated)
                scheduler.startIdleService()
            }
            syncTile()
        }
    }

    fun deleteAlarm(alarmId: String) {
        viewModelScope.launch {
            preferences.deleteAlarm(alarmId)
            scheduler.cancelAlarm(alarmId)
            _snackbarMessage.value = "Alarm removed"
            syncTile()
        }
    }

    fun toggleAlarm(alarmId: String, enabled: Boolean) {
        viewModelScope.launch {
            preferences.toggleAlarm(alarmId, enabled)
            if (enabled) {
                val alarm = alarms.value.find { it.id == alarmId }
                if (alarm != null) {
                    scheduler.scheduleOne(alarm)
                    scheduler.startIdleService()
                }
            } else {
                scheduler.cancelAlarm(alarmId)
            }
            syncTile()
        }
    }

    // ── Global Settings ──────────────────────────────────────────────────

    fun setEarphoneVolume(v: Int) { viewModelScope.launch { preferences.setEarphoneVolume(v) } }
    fun setSpeakerVolume(v: Int) { viewModelScope.launch { preferences.setSpeakerVolume(v) } }
    fun setNoEarphoneAction(a: NoEarphoneAction) { viewModelScope.launch { preferences.setNoEarphoneAction(a) } }

    fun setRingtone(uri: Uri) {
        viewModelScope.launch { preferences.setGlobalRingtoneUri(uri) }
    }

    // ── Timeout Setters ───────────────────────────────────────────────

    fun setTimeoutSeconds(v: Int) { viewModelScope.launch { preferences.setTimeoutSeconds(v) } }
    fun setTimeoutAction(a: TimeoutAction) { viewModelScope.launch { preferences.setTimeoutAction(a) } }

    // ── Test & Stop ──────────────────────────────────────────────────────

    fun testAlarm() {
        scheduler.scheduleTestAlarm()
        _snackbarMessage.value = "Test alarm triggered"
    }

    fun stopAlarm() {
        scheduler.stopAlarm()
        _snackbarMessage.value = "Alarm stopped"
    }

    // ── Battery Optimization ─────────────────────────────────────────────

    /** Opens system battery-optimization settings so the user can exempt us. */
    fun requestBatteryExemption() {
        val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .apply { data = "package:${getApplication<Application>().packageName}".toUri() }
            .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        getApplication<Application>().startActivity(intent)
    }

    // ── Time Picker ──────────────────────────────────────────────────────

    fun showAddTimePicker() { editingAlarmId = null; _showTimePicker.value = true }
    fun hideTimePicker() { _showTimePicker.value = false }

    fun onTimeSelected(hour: Int, minute: Int) {
        val editing = editingAlarmId
        if (editing != null) {
            val alarm = alarms.value.find { it.id == editing } ?: return
            updateAlarm(alarm.copy(hour = hour, minute = minute))
        } else {
            addAlarm(hour, minute)
        }
        hideTimePicker()
    }

    fun editingAlarm(): AlarmItem? =
        editingAlarmId?.let { id -> alarms.value.find { it.id == id } }

    // ── Shizuku ──────────────────────────────────────────────────────────

    fun requestShizukuPermission() {
        shizukuManager.requestPermissionIfNeeded()
        refreshStatus()
    }

//    /** Manually apply anti-kill tweaks + watchdog (runs off main thread). */
//    fun applyAntiKillingTweaks() {
//        if (!shizukuManager.isShizukuAvailable() || !shizukuManager.isShizukuPermitted()) {
//            _snackbarMessage.value = "Shizuku not available"
//            return
//        }
//        viewModelScope.launch {
//            withContext(Dispatchers.IO) {
//                shizukuManager.applyAntiKillingTweaks()
//                shizukuManager.startWatchdogDaemon()
//            }
//            _snackbarMessage.value = "Anti-kill tweaks applied + watchdog started"
//        }
//    }

    // ── Formatting ───────────────────────────────────────────────────────

    fun formatTime(hour: Int, minute: Int): String = "%02d:%02d".format(hour, minute)

    fun formatSchedule(item: AlarmItem): String {
        if (item.daysOfWeek.isEmpty()) return "One-shot"
        return item.daysOfWeek.sorted()
            .joinToString(",") { DAY_NAMES.getOrElse(it) { "?" } }
    }

    companion object {
        private val DAY_NAMES = arrayOf("", "Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
    }
}
