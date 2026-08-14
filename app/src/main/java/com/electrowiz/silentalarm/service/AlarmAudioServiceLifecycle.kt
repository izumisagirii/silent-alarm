package com.electrowiz.silentalarm.service

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "AlarmAudioService"

/**
 * Refresh the idle notification every minute while no alarm is playing.
 *
 * Updates are posted through [NotificationManager.notify] rather than
 * [android.app.Service.startForeground], so the notification content can stay
 * current without re-posting the foreground-service notification and moving it
 * to the top of the shade every minute.
 */
internal fun AlarmAudioService.startIdleRefreshTicker() {
    idleRefreshJob?.cancel()
    idleRefreshJob = serviceScope.launch {
        while (true) {
            delay(60_000L)
            if (state != AlarmAudioService.PlaybackState.Idle) break
            try {
                val alarms = withContext(Dispatchers.IO) {
                    preferences.getAlarms().first()
                }
                if (alarms.any { it.enabled }) {
                    val notificationManager =
                        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    notificationManager.notify(
                        AlarmNotificationController.NOTIFICATION_ID,
                        notifications.buildIdleNotification(alarms)
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to refresh idle notification", e)
            }
        }
    }
}

/**
 * After the alarm routine completes: disable one-shot alarms so they don't
 * fire again, and re-schedule recurring alarms for the next day.
 */
internal suspend fun AlarmAudioService.handlePostAlarm(alarmId: String?) {
    if (alarmId == null) return

    val alarms = preferences.getAlarms().first()
    val alarm = alarms.find { it.id == alarmId } ?: return
    if (!alarm.enabled) return

    if (alarm.daysOfWeek.isEmpty()) {
        withContext(NonCancellable) {
            preferences.toggleAlarm(alarmId, false)
            val remaining = preferences.getAlarms().first()
            keepAliveController.syncAsync(remaining.any { it.enabled })
        }
        scheduler.cancelAlarm(alarmId)
        AlarmTileService.requestTileUpdate(this)
        Log.i(TAG, "One-shot alarm '$alarmId' auto-disabled")
    } else {
        scheduler.scheduleOne(alarm)
        keepAliveController.syncAsync(true)
        Log.i(TAG, "Recurring alarm '$alarmId' re-scheduled")
    }
}
