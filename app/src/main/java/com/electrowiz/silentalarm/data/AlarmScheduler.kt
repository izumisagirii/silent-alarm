package com.electrowiz.silentalarm.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.electrowiz.silentalarm.service.AlarmAudioService
import java.util.Calendar

/**
 * Schedules and cancels exact-alarm triggers via [AlarmManager.setAlarmClock].
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

        /** Intent extra key for the alarm ID. */
        const val EXTRA_ALARM_ID = "alarm_id"
    }

    private val alarmManager: AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    // ── Public API ───────────────────────────────────────────────────────

    /**
     * Schedule all enabled alarms from [alarms]. Safe to call on every
     * preference change — old intents are overwritten via FLAG_UPDATE_CURRENT.
     */
    fun scheduleAll(alarms: List<AlarmItem>) {
        alarms.filter { it.enabled }.forEach { scheduleOne(it) }
        Log.i(TAG, "Scheduled ${alarms.count { it.enabled }} of ${alarms.size} alarms")
    }

    /**
     * Schedule a single alarm by computing its next fire time,
     * building a unique PendingIntent, and calling setAlarmClock.
     */
    fun scheduleOne(item: AlarmItem) {
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
     * Used to keep the foreground service alive for basic process protection
     * when Shizuku is not available.
     */
    fun startIdleService() {
        val intent = Intent(context, AlarmAudioService::class.java)
        context.startForegroundService(intent)
        Log.d(TAG, "Idle service started for keep-alive")
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
            action = AlarmAudioService.ACTION_STOP_ALARM
        }
        context.startService(intent)
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
        return PendingIntent.getService(context, requestCodeFor(alarmId), intent, flags)
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
