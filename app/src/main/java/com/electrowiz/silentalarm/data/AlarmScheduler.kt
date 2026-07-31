package com.electrowiz.silentalarm.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.electrowiz.silentalarm.service.AlarmAudioService
import java.util.Calendar

/**
 * Schedules exact-alarm triggers via [AlarmManager.setAlarmClock] and arms
 * keep-alive recovery alarms via [AlarmManager.setExactAndAllowWhileIdle].
 *
 * Each alarm gets a unique [PendingIntent] via a per-ID request code so
 * individual alarms can be cancelled without affecting others. Uses
 * [AlarmManager.AlarmClockInfo] for the highest scheduler priority.
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

        /** Action for the keep-alive recovery alarm. */
        const val ACTION_KEEP_ALIVE = "com.electrowiz.silentalarm.ACTION_KEEP_ALIVE"

        /** Action that stops active playback and/or the idle foreground service. */
        const val ACTION_STOP_ALARM = "com.electrowiz.silentalarm.ACTION_STOP_ALARM"

        /** Intent extra key for the alarm ID. */
        const val EXTRA_ALARM_ID = "alarm_id"

        private const val KEEP_ALIVE_REQUEST_CODE = 8001

        /** Keep-alive heartbeat interval when the app is backgrounded without Shizuku. */
        private const val KEEP_ALIVE_INTERVAL_MS = 6 * 60 * 60 * 1000L

        /**
         * Delay used after boot. Android 15+ forbids starting a mediaPlayback
         * foreground service from BOOT_COMPLETED directly, so we use an exact
         * alarm to start it instead.
         */
        private const val BOOT_RECOVERY_DELAY_MS = 15_000L
    }

    private val alarmManager: AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    // ── Public API ───────────────────────────────────────────────────────

    /** Whether exact alarms can currently be scheduled on this device. */
    fun canScheduleExactAlarms(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    /**
     * Single reconciliation point for alarm state and keep-alive state.
     *
     * Cancels stale alarms, schedules enabled ones, and then either arms the
     * keep-alive recovery alarm or tears the service down when nothing is enabled.
     *
     * @param startServiceNow when false, the foreground service is not started
     * directly. This is used from boot because Android 15+ blocks direct
     * mediaPlayback foreground-service starts from BOOT_COMPLETED; the recovery
     * exact alarm starts it a moment later.
     */
    fun reconcile(alarms: List<AlarmItem>, startServiceNow: Boolean = true) {
        val enabled = alarms.filter { it.enabled }
        cancelAll(alarms)
        enabled.forEach { scheduleOne(it) }

        if (enabled.isEmpty()) {
            cancelKeepAlive()
            stopAlarm()
            return
        }

        val recoveryDelay = if (startServiceNow) KEEP_ALIVE_INTERVAL_MS else BOOT_RECOVERY_DELAY_MS
        scheduleKeepAlive(recoveryDelay)
        if (startServiceNow) {
            startIdleService()
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

        val triggerEpoch = computeNextFireEpoch(item.hour, item.minute, item.daysOfWeek)
        if (triggerEpoch <= System.currentTimeMillis()) {
            Log.w(TAG, "Alarm '${item.label}' trigger time is in the past — skipping")
            return
        }

        val pendingIntent = buildPendingIntentById(item.id, ACTION_ALARM_TRIGGER)
        val info = AlarmManager.AlarmClockInfo(triggerEpoch, null)

        try {
            alarmManager.setAlarmClock(info, pendingIntent)
            Log.i(TAG, "Alarm '${item.label}' scheduled for $triggerEpoch")
        } catch (e: SecurityException) {
            Log.e(TAG, "SCHEDULE_EXACT_ALARM permission missing", e)
        }
    }

    /**
     * Cancel the alarm identified by [alarmId].
     */
    fun cancelAlarm(alarmId: String) {
        val pi = buildPendingIntentById(alarmId, ACTION_ALARM_TRIGGER)
        alarmManager.cancel(pi)
        pi.cancel()
        Log.d(TAG, "Cancelled alarm $alarmId")
    }

    /**
     * Cancel every alarm currently scheduled by this app.
     * Used before re-scheduling or on user request.
     */
    fun cancelAll(alarms: List<AlarmItem>) {
        alarms.forEach { cancelAlarm(it.id) }
        Log.i(TAG, "Cancelled all ${alarms.size} alarms")
    }

    /**
     * Start [AlarmAudioService] in idle mode (no alarm playback).
     * Used whenever at least one alarm is enabled.
     */
    fun startIdleService() {
        val intent = Intent(context, AlarmAudioService::class.java)
        try {
            context.startForegroundService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start idle service", e)
        }
        Log.d(TAG, "Idle service started for keep-alive")
    }

    /**
     * Arm the keep-alive exact alarm. If the process is killed, this alarm can
     * still wake the app and restart the foreground service.
     */
    fun scheduleKeepAlive(delayMs: Long = KEEP_ALIVE_INTERVAL_MS) {
        if (!canScheduleExactAlarms()) {
            Log.w(TAG, "Exact alarm permission missing — keep-alive alarm skipped")
            return
        }

        val pi = buildKeepAlivePendingIntent()
        val triggerAt = System.currentTimeMillis() + delayMs.coerceAtLeast(0L)
        try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            Log.d(TAG, "Keep-alive alarm armed in ${delayMs / 1000}s")
        } catch (e: SecurityException) {
            Log.e(TAG, "SCHEDULE_EXACT_ALARM permission missing", e)
        }
    }

    /** Cancel the keep-alive exact alarm. */
    fun cancelKeepAlive() {
        val pi = buildKeepAlivePendingIntent()
        alarmManager.cancel(pi)
        pi.cancel()
        Log.d(TAG, "Keep-alive alarm cancelled")
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
        context.startForegroundService(intent)
        Log.i(TAG, "Test alarm triggered")
    }

    /**
     * Send a stop intent to [AlarmAudioService] to halt any active alarm.
     */
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

    /** Derive a stable, unique request code from the alarm's UUID. */
    private fun requestCodeFor(alarmId: String): Int {
        return REQUEST_CODE_BASE + (alarmId.hashCode() and 0x7FFF) // keep positive & bounded
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

    /** Build the singleton [PendingIntent] used by the keep-alive exact alarm. */
    private fun buildKeepAlivePendingIntent(): PendingIntent {
        val intent = Intent(context, AlarmAudioService::class.java).apply {
            action = ACTION_KEEP_ALIVE
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getForegroundService(
            context,
            KEEP_ALIVE_REQUEST_CODE,
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
    private fun computeNextFireEpoch(hour: Int, minute: Int, daysOfWeek: Set<Int>): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
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
            val candidate = Calendar.getInstance().apply {
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
