package com.electrowiz.silentalarm.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.drawable.Icon
import android.text.format.DateUtils
import androidx.core.app.NotificationCompat
import com.electrowiz.silentalarm.MainActivity
import com.electrowiz.silentalarm.R
import com.electrowiz.silentalarm.data.AlarmItem
import com.electrowiz.silentalarm.data.AlarmScheduler
import com.electrowiz.silentalarm.util.AudioRouter

/**
 * Owns every notification built by [AlarmAudioService].
 *
 * Keeping notification channels, idle/active/snooze/stopped builders, and
 * PendingIntent constants in one file removes the view-layer concerns from
 * the service's playback state machine.
 */
internal class AlarmNotificationController(
    private val service: Service,
    private val scheduler: AlarmScheduler,
    private val audioRouter: AudioRouter
) {
    companion object {
        const val NOTIFICATION_ID = 2001
        const val STOPPED_NOTIFICATION_ID = 2005

        private const val CHANNEL_IDLE = "alarm_idle_channel"
        private const val CHANNEL_ACTIVE = "alarm_active_channel"
        private const val CHANNEL_STOPPED = "alarm_stopped_channel"

        private const val PI_STOP = 2002
        private const val PI_CONTENT = 2003
        private const val PI_SNOOZE = 2004
    }

    fun createChannels() {
        val nm = service.getSystemService(Service.NOTIFICATION_SERVICE) as NotificationManager

        val idleCh = NotificationChannel(
            CHANNEL_IDLE,
            service.getString(R.string.notification_channel_alarm),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = service.getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(idleCh)

        val activeCh = NotificationChannel(
            CHANNEL_ACTIVE,
            service.getString(R.string.notification_channel_active),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = service.getString(R.string.notification_channel_active_desc)
            // The alarm provides its own sound/vibration — the ringing
            // notification itself must stay silent, otherwise every ring
            // starts with an extra default notification beep + buzz.
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        nm.createNotificationChannel(activeCh)

        val stoppedCh = NotificationChannel(
            CHANNEL_STOPPED,
            service.getString(R.string.notification_channel_stopped),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = service.getString(R.string.notification_channel_stopped_desc)
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        nm.createNotificationChannel(stoppedCh)
    }

    fun buildIdleNotification(alarms: List<AlarmItem>): Notification {
        val nextAlarm = scheduler.nextAlarm(alarms)
        val contentText = nextAlarm?.let { alarm ->
            val epoch = scheduler.nextFireEpoch(alarm)
            val relative = DateUtils.getRelativeTimeSpanString(
                epoch,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE
            )
            service.getString(R.string.notification_next_alarm_format, relative)
        } ?: service.getString(R.string.notification_waiting)

        return NotificationCompat.Builder(service, CHANNEL_IDLE)
            .setContentTitle(service.getString(R.string.app_name))
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setShowWhen(false)
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(contentIntent())
            .build()
    }

    /**
     * Build the ringing notification. The subtitle follows the *resolved*
     * playback mode (earphones / speaker / vibrate), not the raw hardware
     * query — so a fallback to vibration while earphones stay plugged in no
     * longer claims to be playing through earphones.
     *
     * A null action is used only for the brief pre-routing window and falls
     * back to the current audio route; the routed path re-posts immediately
     * with the resolved mode.
     */
    fun buildActiveNotification(action: AudioRouter.ResolvedAction? = null): Notification {
        val subtitle = when (action) {
            AudioRouter.ResolvedAction.PLAY_VIA_EARPHONES ->
                service.getString(R.string.notification_earphones)
            AudioRouter.ResolvedAction.PLAY_VIA_SPEAKER ->
                service.getString(R.string.notification_speaker)
            AudioRouter.ResolvedAction.VIBRATE_ONLY ->
                service.getString(R.string.notification_vibrate)
            null -> when (audioRouter.inspectRoute().outputType) {
                AudioRouter.AudioOutputType.EARPHONES_AVAILABLE ->
                    service.getString(R.string.notification_earphones)
                AudioRouter.AudioOutputType.SPEAKER_ONLY ->
                    service.getString(R.string.notification_no_earphones)
            }
        }
        return buildAlarmNotification(
            service.getString(R.string.notification_alarm_active),
            subtitle
        )
    }

    fun buildSnoozeNotification(): Notification =
        buildAlarmNotification(
            service.getString(R.string.notification_snoozing),
            service.getString(R.string.notification_snooze_resume)
        )

    fun postStoppedNotification(lastRingTimeMs: Long) {
        val ringTime = DateUtils.formatDateTime(
            service,
            lastRingTimeMs,
            DateUtils.FORMAT_SHOW_TIME
        )

        val notification = Notification.Builder(service, CHANNEL_STOPPED)
            .setContentTitle(service.getString(R.string.notification_missed_alarm))
            .setContentText(
                service.getString(R.string.notification_last_rang_time_format, ringTime)
            )
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setAutoCancel(true)
            .setContentIntent(contentIntent())
            .build()

        val nm = service.getSystemService(Service.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(STOPPED_NOTIFICATION_ID, notification)
    }

    /** Tap target for every notification — opens the dashboard. */
    private fun contentIntent(): PendingIntent =
        PendingIntent.getActivity(
            service,
            PI_CONTENT,
            Intent(service, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun buildAlarmNotification(title: String, text: String): Notification {
        val stopPi = PendingIntent.getService(
            service,
            PI_STOP,
            Intent(service, AlarmAudioService::class.java).apply {
                action = AlarmScheduler.ACTION_STOP_ALARM
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val snoozePi = PendingIntent.getService(
            service,
            PI_SNOOZE,
            Intent(service, AlarmAudioService::class.java).apply {
                action = AlarmScheduler.ACTION_SNOOZE_ALARM
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val snoozeAction = Notification.Action.Builder(
            Icon.createWithResource(service, R.drawable.ic_notification_snooze),
            service.getString(R.string.snooze_5min),
            snoozePi
        ).build()

        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(service, R.drawable.ic_notification_stop),
            service.getString(R.string.stop),
            stopPi
        ).build()

        return Notification.Builder(service, CHANNEL_ACTIVE)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setContentIntent(contentIntent())
            .setDeleteIntent(snoozePi)
            .addAction(snoozeAction)
            .addAction(stopAction)
            .setStyle(Notification.MediaStyle().setShowActionsInCompactView(0, 1))
            .build()
    }
}
