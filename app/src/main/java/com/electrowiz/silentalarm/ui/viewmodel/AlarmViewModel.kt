package com.electrowiz.silentalarm.ui.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.electrowiz.silentalarm.data.AlarmItem
import com.electrowiz.silentalarm.data.AlarmPreferences
import com.electrowiz.silentalarm.data.AlarmScheduler
import com.electrowiz.silentalarm.data.NoEarphoneAction
import com.electrowiz.silentalarm.data.TimeoutAction
import com.electrowiz.silentalarm.R
import com.electrowiz.silentalarm.daemon.ShellManager
import com.electrowiz.silentalarm.keepalive.KeepAliveController
import com.electrowiz.silentalarm.service.AlarmAudioService
import com.electrowiz.silentalarm.service.AlarmTileService
import com.electrowiz.silentalarm.util.TimezoneFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.TimeZone

/** Snackbar payload with an optional action (e.g. undo a deletion). */
data class SnackbarEvent(
    val message: String,
    val actionLabel: String? = null,
    val onAction: () -> Unit = {}
)

/**
 * Central ViewModel.
 *
 * Reads from DataStore (alarms + settings), exposes StateFlows for the UI,
 * and handles side effects: AlarmManager scheduling, Shizuku anti-kill,
 * QS tile sync. All blocking I/O runs on Dispatchers.IO.
 */
class AlarmViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "AlarmViewModel"
    }

    private val preferences = AlarmPreferences(application)
    private val scheduler = AlarmScheduler(application)
    private val shellManager = ShellManager.get(application)
    private val keepAliveController = KeepAliveController.get(application)

    // ── Alarm List (from DataStore, sorted by time) ──────────────────────

    val alarms: StateFlow<List<AlarmItem>> = preferences.getAlarms()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ── Global Settings ──────────────────────────────────────────────────

    val earphoneVolume: StateFlow<Int> = preferences.earphoneVolume
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AlarmPreferences.DEFAULT_EARPHONE_VOLUME)

    val speakerVolume: StateFlow<Int> = preferences.speakerVolume
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AlarmPreferences.DEFAULT_SPEAKER_VOLUME)

    val noEarphoneAction: StateFlow<NoEarphoneAction> = preferences.noEarphoneAction
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), NoEarphoneAction.VIBRATE_ONLY)

    val globalRingtoneUri: StateFlow<String> = preferences.globalRingtoneUri
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    // ── Timeout Settings ──────────────────────────────────────────────

    val timeoutSeconds: StateFlow<Int> = preferences.timeoutSeconds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AlarmPreferences.DEFAULT_TIMEOUT_SECONDS)

    val timeoutAction: StateFlow<TimeoutAction> = preferences.timeoutAction
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TimeoutAction.STOP)

    // ── Optional Keep-Alive Toggles ─────────────────────────────────────

    val keepAliveEnabled: StateFlow<Boolean> = preferences.keepAliveEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val privilegedEnabled: StateFlow<Boolean> = preferences.privilegedEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // ── System Status (polled) ───────────────────────────────────────────

    private val _exactAlarmAllowed = MutableStateFlow(scheduler.canScheduleExactAlarms())
    val exactAlarmAllowed: StateFlow<Boolean> = _exactAlarmAllowed.asStateFlow()

    private val _batteryOptimizationIgnored = MutableStateFlow(isIgnoringBatteryOptimizations())
    val batteryOptimizationIgnored: StateFlow<Boolean> = _batteryOptimizationIgnored.asStateFlow()

    /** A privileged backend (Root shell or Shizuku) is ready and permitted. */
    private val _shellReady = MutableStateFlow(false)
    val shellReady: StateFlow<Boolean> = _shellReady.asStateFlow()

    /** Shizuku is up but needs the user's authorization (no root fallback). */
    private val _shizukuPermissionNeeded = MutableStateFlow(false)
    val shizukuPermissionNeeded: StateFlow<Boolean> = _shizukuPermissionNeeded.asStateFlow()

    /** Whether notifications can be posted (POST_NOTIFICATIONS on 13+, always true before). */
    private val _notificationsAllowed = MutableStateFlow(notificationsAllowed())
    val notificationsAllowed: StateFlow<Boolean> = _notificationsAllowed.asStateFlow()

    // ── UI State ─────────────────────────────────────────────────────────

    private val _showTimePicker = MutableStateFlow(false)
    val showTimePicker: StateFlow<Boolean> = _showTimePicker.asStateFlow()

    private val _snackbarMessage = MutableStateFlow<SnackbarEvent?>(null)
    val snackbarMessage: StateFlow<SnackbarEvent?> = _snackbarMessage.asStateFlow()

    /**
     * Whether an alarm is currently ringing or snoozing. Process-local
     * signal from [AlarmAudioService] — the UI and the service share one
     * process, so this tracks the real playback state without polling.
     */
    val alarmActive: StateFlow<Boolean> = AlarmAudioService.playbackActive.asStateFlow()

    val activeAlarmId: StateFlow<String?> = AlarmAudioService.activeAlarmId.asStateFlow()

    private var editingAlarmId: String? = null

    private var privilegedTweaksApplied = false

    // ── Init ──────────────────────────────────────────────────────────────

    init {
        refreshStatusFlags()
        viewModelScope.launch {
            preferences.migrateOrReset()
            val alarms = preferences.getAlarms().first()
            scheduler.reconcile(alarms, stopServiceWhenDisabled = false)
            keepAliveController.syncAsync(alarms.any { it.enabled })
        }
    }

    // ── Status Refresh ───────────────────────────────────────────────────

    /** Fast (non-blocking) status read — safe for init/onResume. */
    private fun refreshStatusFlags() {
        val wasExactAlarmAllowed = _exactAlarmAllowed.value
        _exactAlarmAllowed.value = scheduler.canScheduleExactAlarms()
        _batteryOptimizationIgnored.value = isIgnoringBatteryOptimizations()
        _notificationsAllowed.value = notificationsAllowed()

        viewModelScope.launch {
            val status = withContext(Dispatchers.IO) { shellManager.refresh() }
            _shellReady.value = status == ShellManager.ShellStatus.SU_READY ||
                status == ShellManager.ShellStatus.SHIZUKU_READY
            _shizukuPermissionNeeded.value =
                status == ShellManager.ShellStatus.SHIZUKU_NEEDS_PERMISSION
        }

        // AlarmManager deletes exact alarms when the permission is revoked, so
        // re-arm after the user grants it through system settings.
        if (!wasExactAlarmAllowed && _exactAlarmAllowed.value) {
            viewModelScope.launch {
                val alarms = preferences.getAlarms().first()
                scheduler.reconcile(alarms, stopServiceWhenDisabled = false)
                keepAliveController.syncAsync(alarms.any { it.enabled })
            }
        }
    }

    /**
     * Full refresh: flags + auto-apply privileged tweaks when enabled.
     */
    fun refreshStatus() {
        refreshStatusFlags()

        viewModelScope.launch {
            if (privilegedEnabled.value && !privilegedTweaksApplied) {
                privilegedTweaksApplied = true
                try {
                    val applied = withContext(Dispatchers.IO) {
                        shellManager.applyAntiKillTweaks()
                    }
                    if (applied) {
                        _snackbarMessage.value = SnackbarEvent(msg(R.string.shizuku_activated))
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to apply privileged tweaks", e)
                }
            }
        }
    }

    fun clearSnackbar() { _snackbarMessage.value = null }

    /** Shorthand for a localized snackbar message. */
    private fun msg(id: Int, vararg args: Any): String =
        getApplication<Application>().getString(id, *args)

    /** Push current alarm state to the QS tile. */
    private fun syncTile() {
        AlarmTileService.requestTileUpdate(getApplication())
    }

    /**
     * Common tail for every alarm mutation: persist, then reconcile the
     * AlarmManager schedule, sync the optional keep-alive layer, and refresh
     * the QS tile. Keeps the CRUD paths from drifting apart.
     */
    private suspend fun applyAlarmChange(persist: suspend () -> Unit) {
        persist()
        val alarms = preferences.getAlarms().first()
        scheduler.reconcile(alarms)
        keepAliveController.syncAsync(alarms.any { it.enabled })
        syncTile()
    }

    // ── Alarm CRUD ───────────────────────────────────────────────────────

    fun addAlarm(hour: Int, minute: Int, label: String = "", timeZoneId: String = "") {
        viewModelScope.launch {
            val item = AlarmItem(
                hour = hour,
                minute = minute,
                label = label,
                timeZoneId = timeZoneId
            )
            applyAlarmChange { preferences.addAlarm(item) }
            _snackbarMessage.value = SnackbarEvent(msg(R.string.alarm_set_format, hour, minute))
        }
    }

    fun updateAlarm(updated: AlarmItem) {
        viewModelScope.launch {
            // No explicit cancelAlarm here: reconcile() cancels every alarm
            // still in the list (including this one) before re-scheduling.
            applyAlarmChange { preferences.updateAlarm(updated) }
        }
    }

    fun deleteAlarm(alarmId: String) {
        viewModelScope.launch {
            val deleted = alarms.value.find { it.id == alarmId } ?: return@launch
            // Cancel the AlarmManager entry BEFORE removing the alarm from
            // storage: reconcile() only cancels alarms still present in the
            // list, so a deleted alarm would otherwise keep its schedule and
            // ring later despite being gone from the app.
            if (activeAlarmId.value == alarmId) scheduler.stopAlarm()
            scheduler.cancelAlarm(alarmId)
            applyAlarmChange { preferences.deleteAlarm(alarmId) }
            // Offer an undo action — an accidental delete is one tap away
            // from being restored with all of its settings intact.
            _snackbarMessage.value = SnackbarEvent(
                message = msg(R.string.alarm_removed),
                actionLabel = msg(R.string.undo),
                onAction = {
                    viewModelScope.launch {
                        applyAlarmChange { preferences.addAlarm(deleted) }
                    }
                }
            )
        }
    }

    fun toggleAlarm(alarmId: String, enabled: Boolean) {
        viewModelScope.launch {
            // Cancel first too: if the alarm is disabled close to its fire
            // time, an in-flight trigger must not start playback afterwards.
            if (!enabled && activeAlarmId.value == alarmId) scheduler.stopAlarm()
            scheduler.cancelAlarm(alarmId)
            applyAlarmChange { preferences.toggleAlarm(alarmId, enabled) }
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

    // ── Optional Keep-Alive Toggles ──────────────────────────────────────

    /**
     * Toggle the notification keep-alive layer (idle FGS + recovery alarm +
     * watchdog). Syncing applies the change immediately: turning it off stops
     * the idle service and cancels the recovery alarm.
     */
    fun setKeepAliveEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setKeepAliveEnabled(enabled)
            keepAliveController.syncAsync(preferences.getAlarms().first().any { it.enabled })
        }
    }

    /**
     * Toggle privileged anti-kill (deviceidle whitelist / standby bucket via
     * Root shell or Shizuku). Turning it on applies the whitelist once;
     * turning it off stops the watchdog (whitelist stays until reboot).
     */
    fun setPrivilegedEnabled(enabled: Boolean) {
        viewModelScope.launch {
            var target = enabled
            if (enabled) {
                val applied = withContext(Dispatchers.IO) {
                    shellManager.applyAntiKillTweaks()
                }
                if (!applied) {
                    // No usable backend — roll the switch back instead of
                    // leaving it "on" while doing nothing.
                    target = false
                    _snackbarMessage.value = SnackbarEvent(msg(R.string.shizuku_not_installed))
                } else {
                    _snackbarMessage.value = SnackbarEvent(msg(R.string.shizuku_activated))
                }
            }
            preferences.setPrivilegedEnabled(target)
            keepAliveController.syncAsync(preferences.getAlarms().first().any { it.enabled })
        }
    }

    // ── Test & Stop ──────────────────────────────────────────────────────

    fun testAlarm() {
        scheduler.scheduleTestAlarm()
        _snackbarMessage.value = SnackbarEvent(msg(R.string.test_alarm_triggered))
    }

    fun stopAlarm() {
        if (alarmActive.value) {
            scheduler.stopAlarm()
            _snackbarMessage.value = SnackbarEvent(msg(R.string.alarm_stopped))
        } else {
            _snackbarMessage.value = SnackbarEvent(msg(R.string.no_alarm_ringing))
        }
    }

    // ── Battery Optimization ─────────────────────────────────────────────

    /** Snackbar for the already-exempt case (launch lives in MainActivity). */
    fun showBatteryAlreadyExempt() {
        _snackbarMessage.value = SnackbarEvent(msg(R.string.battery_already_exempt))
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val context = getApplication<Application>()
        val appPackage = context.packageName
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(appPackage)
        } catch (e: Exception) {
            Log.w(TAG, "Battery optimization state unavailable", e)
            false
        }
    }

    private fun notificationsAllowed(): Boolean =
        NotificationManagerCompat.from(getApplication()).areNotificationsEnabled()

    // ── Time Picker ──────────────────────────────────────────────────────

    fun showAddTimePicker() { editingAlarmId = null; _showTimePicker.value = true }
    fun showEditTimePicker(alarmId: String) { editingAlarmId = alarmId; _showTimePicker.value = true }
    fun hideTimePicker() { _showTimePicker.value = false }

    fun onTimeSelected(hour: Int, minute: Int, label: String = "", timeZoneId: String = "") {
        val editing = editingAlarmId
        if (editing != null) {
            val alarm = alarms.value.find { it.id == editing } ?: return
            updateAlarmTime(alarm.id, hour, minute, label, timeZoneId)
        } else {
            addAlarm(hour, minute, label, timeZoneId)
        }
        hideTimePicker()
    }

    /**
     * Update the time of an existing alarm and capture the chosen timezone
     * at this save point. Existing alarms are not rescheduled merely because
     * the system timezone changed; only an explicit edit changes their zone.
     */
    private fun updateAlarmTime(
        alarmId: String,
        hour: Int,
        minute: Int,
        label: String,
        timeZoneId: String
    ) {
        viewModelScope.launch {
            val existing = alarms.value.find { it.id == alarmId } ?: return@launch
            updateAlarm(
                existing.copy(
                    hour = hour,
                    minute = minute,
                    label = label,
                    timeZoneId = timeZoneId
                )
            )
        }
    }

    fun editingAlarm(): AlarmItem? =
        editingAlarmId?.let { id -> alarms.value.find { it.id == id } }

    // ── Privileged Shell ─────────────────────────────────────────────────

    /** Ask Shizuku for authorization when it is the only available backend. */
    fun requestPrivilegedPermission() {
        shellManager.requestPermissionIfNeeded()
        refreshStatus()
    }

    // ── Formatting ───────────────────────────────────────────────────────

    /** Display the alarm's wall-clock time as configured (own-zone hour/minute). */
    fun formatAlarmTime(item: AlarmItem): String =
        "%02d:%02d".format(item.hour, item.minute)

    /** The same alarm instant shown in the system's current timezone. */
    fun formatAlarmLocalTime(item: AlarmItem): String =
        formatEpochInZone(scheduler.nextFireEpoch(item), TimeZone.getDefault())

    /**
     * Small caption for the alarm card: the current-timezone equivalent of
     * the alarm time, shown only when it differs from the alarm's own time.
     * The "本地时间 / Local time" prefix is the user-facing hint.
     */
    fun localTimeCaption(item: AlarmItem): String? {
        if (!item.enabled) return null
        val own = formatAlarmTime(item)
        val local = formatAlarmLocalTime(item)
        return if (own != local) {
            getApplication<Application>().getString(R.string.alarm_local_time_format, local)
        } else {
            null
        }
    }

    /** Return the alarm time that should be shown in the editor, in current-zone wall time. */
    fun alarmPickerHourMinute(item: AlarmItem): Pair<Int, Int> {
        val epoch = scheduler.nextFireEpoch(item)
        val cal = Calendar.getInstance(TimeZone.getDefault()).apply { timeInMillis = epoch }
        return cal.get(Calendar.HOUR_OF_DAY) to cal.get(Calendar.MINUTE)
    }

    fun formatSchedule(item: AlarmItem): String {
        val context = getApplication<Application>()
        if (item.daysOfWeek.isEmpty()) {
            return context.getString(R.string.schedule_one_shot)
        }
        val dayNames = context.resources.getStringArray(R.array.day_names_short)
        return item.daysOfWeek.sorted()
            .joinToString(", ") { dayNames.getOrElse(it) { "?" } }
    }

    /** Compact label for the timezone captured on an individual alarm. */
    fun timezoneLabelForAlarm(item: AlarmItem): String {
        val zone = TimeZone.getTimeZone(
            item.timeZoneId.takeIf { it.isNotBlank() } ?: TimeZone.getDefault().id
        )
        return timezoneLabel(zone)
    }

    private fun timezoneLabel(zone: TimeZone): String {
        return "${zone.id} (${TimezoneFormatter.offsetLabel(zone)})"
    }

    private fun formatEpochInZone(epoch: Long, zone: TimeZone): String {
        val cal = Calendar.getInstance(zone).apply { timeInMillis = epoch }
        return "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
    }
}
