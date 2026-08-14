package com.electrowiz.silentalarm.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.electrowiz.silentalarm.MainActivity
import com.electrowiz.silentalarm.service.AlarmAudioService
import java.util.Calendar
import java.util.TimeZone

/**
 * Schedules exact-alarm triggers via [AlarmManager.setAlarmClock] and the
 * snooze-expiry alarm via [AlarmManager.setExactAndAllowWhileIdle].
 *
 * Each alarm gets a unique [PendingIntent] via a per-ID request code so
 * individual alarms can be cancelled without affecting others. Uses
 * [AlarmManager.AlarmClockInfo] for the highest scheduler priority.
 *
 * The optional notification keep-alive (recovery alarm, idle FGS, watchdog)
 * is owned by KeepAliveController and never touched here.
 */
class AlarmScheduler(private val context: Context) {

    companion object {
        private const val TAG = "AlarmScheduler"

        /** Base request code — each alarm's ID hash is added to avoid collisions. */
        private const val REQUEST_CODE_BASE = 9000

        /** Action set on the intent that triggers [AlarmAudioService]. */
        const val ACTION_ALARM_TRIGGER = "com.electrowiz.silentalarm.ACTION_ALARM_TRIGGER"

        /** Action for a test/instant alarm fire. */
        const val ACTION_TEST_ALARM = "com.electrowiz.silentalarm.ACTION_TEST_ALARM"

        /** Action that stops active playback and/or the idle foreground service. */
        const val ACTION_STOP_ALARM = "com.electrowiz.silentalarm.ACTION_STOP_ALARM"

        /** Action that snoozes active playback for [SNOOZE_DURATION_MS]. */
        const val ACTION_SNOOZE_ALARM = "com.electrowiz.silentalarm.ACTION_SNOOZE_ALARM"

        /** Action fired when a snooze delay expires — resumes ringing. */
        const val ACTION_SNOOZE_EXPIRED = "com.electrowiz.silentalarm.ACTION_SNOOZE_EXPIRED"

        /** Intent extra key for the alarm ID. */
        const val EXTRA_ALARM_ID = "alarm_id"

        /** Snooze duration in milliseconds (fixed 5 minutes). */
        const val SNOOZE_DURATION_MS = 5 * 60 * 1000L

        private const val SNOOZE_EXPIRY_REQUEST_CODE = 8002

        /** Request code for the lock-screen "next alarm" tap target. */
        private const val SHOW_INTENT_REQUEST_CODE = 8003
    }

    private val alarmManager: AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    // ── Public API ───────────────────────────────────────────────────────

    /** Whether exact alarms can currently be scheduled on this device. */
    fun canScheduleExactAlarms(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    /**
     * Single reconciliation point for alarm state.
     *
     * Cancels stale alarms and schedules enabled ones. This scheduler is
     * deliberately keep-alive-agnostic: the optional notification keep-alive
     * layer listens to the same alarm-state changes through its own sync().
     *
     * @param stopServiceWhenDisabled when false, a disabled one-shot alarm that
     * is currently ringing is left running. The app startup path uses this so
     * opening the app while an alarm is active doesn't stop it.
     */
    fun reconcile(
        alarms: List<AlarmItem>,
        stopServiceWhenDisabled: Boolean = true
    ) {
        val enabled = alarms.filter { it.enabled }
        cancelAll(alarms)
        enabled.forEach { scheduleOne(it) }

        if (enabled.isEmpty()) {
            if (stopServiceWhenDisabled) {
                stopAlarm()
            }
            return
        }
    }

    /**
     * Schedule a single alarm by computing its next fire time,
     * building a unique PendingIntent, and calling setAlarmClock.
     */
    fun scheduleOne(item: AlarmItem) {
        if (!canScheduleExactAlarms()) {
            Log.w(TAG, "Exact alarm permission missing — cannot schedule '${item.label}'")
            return
        }

        val triggerEpoch = nextFireEpoch(item)
        if (triggerEpoch <= System.currentTimeMillis()) {
            Log.w(TAG, "Alarm '${item.label}' trigger time is in the past — skipping")
            return
        }

        val pendingIntent = buildPendingIntentById(item.id, ACTION_ALARM_TRIGGER)
        val info = AlarmManager.AlarmClockInfo(triggerEpoch, buildShowIntent())

        try {
            alarmManager.setAlarmClock(info, pendingIntent)
            Log.i(TAG, "Alarm '${item.label}' scheduled for $triggerEpoch")
        } catch (e: SecurityException) {
            Log.e(TAG, "SCHEDULE_EXACT_ALARM permission missing", e)
        }
    }

    fun cancelAlarm(alarmId: String) {
        val pi = buildPendingIntentById(alarmId, ACTION_ALARM_TRIGGER)
        alarmManager.cancel(pi)
        pi.cancel()
        Log.d(TAG, "Cancelled alarm $alarmId")
    }

    /** Return the enabled alarm that will fire next, or null when none are enabled. */
    fun nextAlarm(alarms: List<AlarmItem>): AlarmItem? =
        alarms.asSequence()
            .filter { it.enabled }
            .minByOrNull { nextFireEpoch(it) }

    /** Exposed so the service can show the same fire time that the scheduler uses. */
    fun nextFireEpoch(item: AlarmItem): Long =
        computeNextFireEpoch(item.hour, item.minute, item.daysOfWeek, timeZoneFor(item))

    private fun timeZoneFor(item: AlarmItem): TimeZone =
        item.timeZoneId.takeIf { it.isNotBlank() }
            ?.let { TimeZone.getTimeZone(it) }
            ?: TimeZone.getDefault()

    /** Used before re-scheduling from [reconcile]. */
    private fun cancelAll(alarms: List<AlarmItem>) {
        alarms.forEach { cancelAlarm(it.id) }
        Log.i(TAG, "Cancelled all ${alarms.size} alarms")
    }

    /**
     * Arm the snooze-expiry exact alarm. When it fires, the service resumes
     * ringing (or recovers it if the process was killed during the snooze).
     *
     * @return true when the exact alarm was armed; false when exact-alarm
     * scheduling is unavailable, so the caller can fall back to an
     * in-process timer.
     */
    fun scheduleSnoozeExpiry(alarmId: String?, delayMs: Long = SNOOZE_DURATION_MS): Boolean {
        if (!canScheduleExactAlarms()) {
            Log.w(TAG, "Exact alarm permission missing — snooze expiry skipped")
            return false
        }

        val pi = buildSnoozeExpiryPendingIntent(alarmId)
        val triggerAt = System.currentTimeMillis() + delayMs.coerceAtLeast(0L)
        return try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            Log.d(TAG, "Snooze expiry armed in ${delayMs / 1000}s")
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "SCHEDULE_EXACT_ALARM permission missing", e)
            false
        }
    }

    /** Cancel any pending snooze-expiry alarm (no-op if none was armed). */
    fun cancelSnoozeExpiry() {
        val pi = buildSnoozeExpiryPendingIntent(null)
        alarmManager.cancel(pi)
        pi.cancel()
        Log.d(TAG, "Snooze expiry cancelled")
    }

    /**
     * Fire the alarm **immediately** for testing purposes.
     * Sends a [ACTION_TEST_ALARM] intent directly to [AlarmAudioService],
     * bypassing the AlarmManager scheduler.
     */
    fun scheduleTestAlarm() {
        val intent = Intent(context, AlarmAudioService::class.java).apply {
            action = ACTION_TEST_ALARM
        }
        try {
            context.startForegroundService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start test alarm service", e)
        }
        Log.i(TAG, "Test alarm triggered")
    }

    fun stopAlarm() {
        val intent = Intent(context, AlarmAudioService::class.java).apply {
            action = ACTION_STOP_ALARM
        }
        try {
            context.startService(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Stop intent not delivered (service may not be running)", e)
        }
        Log.i(TAG, "Stop alarm intent sent")
    }

    // ── Internals ────────────────────────────────────────────────────────

    /**
     * Derive a stable, unique request code from the alarm's UUID.
     *
     * PendingIntent identity ignores extras, so two alarms with the same
     * request code would share one PendingIntent and silently overwrite each
     * other's schedule. Use the full 30-bit hash to keep collisions
     * practically impossible.
     */
    private fun requestCodeFor(alarmId: String): Int {
        return REQUEST_CODE_BASE + (alarmId.hashCode() and 0x3FFFFFFF)
    }

    /** Build a [PendingIntent] for a specific alarm id. */
    private fun buildPendingIntentById(alarmId: String, action: String): PendingIntent {
        val intent = Intent(context, AlarmAudioService::class.java).apply {
            this.action = action
            putExtra(EXTRA_ALARM_ID, alarmId)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getForegroundService(context, requestCodeFor(alarmId), intent, flags)
    }

    /**
     * Build the tap target shown with the system's "next alarm" indicator on
     * the lock screen and status bar. Tapping it opens the app dashboard.
     */
    private fun buildShowIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(context, SHOW_INTENT_REQUEST_CODE, intent, flags)
    }

    /**
     * Build the singleton [PendingIntent] used by the snooze-expiry alarm.
     * [alarmId] rides as an extra; PendingIntent identity ignores extras, so
     * cancel and (re)schedule always address the same instance.
     */
    private fun buildSnoozeExpiryPendingIntent(alarmId: String?): PendingIntent {
        val intent = Intent(context, AlarmAudioService::class.java).apply {
            action = ACTION_SNOOZE_EXPIRED
            putExtra(EXTRA_ALARM_ID, alarmId)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getForegroundService(
            context,
            SNOOZE_EXPIRY_REQUEST_CODE,
            intent,
            flags
        )
    }

    /**
     * Compute the next epoch-millis when this alarm should fire.
     *
     * - If [daysOfWeek] is empty → one-shot: next occurrence of (hour, minute).
     * - If [daysOfWeek] is non-empty → recurring: next matching day-of-week.
     */
    private fun computeNextFireEpoch(
        hour: Int,
        minute: Int,
        daysOfWeek: Set<Int>,
        timeZone: TimeZone
    ): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance(timeZone).apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        if (daysOfWeek.isEmpty()) {
            // One-shot: if time already passed today, move to tomorrow
            if (target.timeInMillis <= now.timeInMillis) {
                target.add(Calendar.DAY_OF_YEAR, 1)
            }
            return target.timeInMillis
        }

        // Recurring: find the next matching day within 7 days
        for (offset in 0..7) {
            val candidate = Calendar.getInstance(timeZone).apply {
                timeInMillis = target.timeInMillis
                add(Calendar.DAY_OF_YEAR, offset)
            }
            val dow = candidate.get(Calendar.DAY_OF_WEEK) // 1=Sun … 7=Sat
            if (dow in daysOfWeek && candidate.timeInMillis > now.timeInMillis) {
                return candidate.timeInMillis
            }
        }
        // Fallback (shouldn't happen): return tomorrow
        target.add(Calendar.DAY_OF_YEAR, 1)
        return target.timeInMillis
    }
}
