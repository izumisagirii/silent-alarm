package com.example.silentalarm.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.silentalarm.MainActivity
import com.example.silentalarm.R
import com.example.silentalarm.data.AlarmPreferences
import com.example.silentalarm.data.AlarmScheduler
import com.example.silentalarm.util.AudioRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.core.net.toUri

/**
 * Foreground service for alarm audio playback.
 *
 * ## Entry Points (via intent actions)
 * - [AlarmScheduler.ACTION_ALARM_TRIGGER] — scheduled alarm from AlarmManager
 * - [AlarmScheduler.ACTION_TEST_ALARM] — instant test fire from UI button
 * - [ACTION_STOP_ALARM] — stop from notification action or UI button
 *
 * ## Audio Routing
 * 1. Detect earphone-type output devices via [AudioManager.getDevices].
 * 2. Earphones present → play ringtone on earphones (with 500ms silent wake-up).
 * 3. No earphones → vibrate or speaker fallback per user preference.
 *
 * ## Lifecycle
 * Runs as `FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK` with a persistent notification.
 * Releases all resources (MediaPlayer, WakeLock, Vibrator) in [onDestroy].
 */
class AlarmAudioService : Service() {

    companion object {
        private const val TAG = "AlarmAudioService"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_IDLE = "alarm_idle_channel"
        private const val CHANNEL_ACTIVE = "alarm_active_channel"

        /** Stop the currently playing alarm. */
        const val ACTION_STOP_ALARM = "com.example.silentalarm.ACTION_STOP_ALARM"
    }

    // ── Dependencies ─────────────────────────────────────────────────────
    private lateinit var audioManager: AudioManager
    private lateinit var audioRouter: AudioRouter
    private lateinit var preferences: AlarmPreferences
    private lateinit var scheduler: AlarmScheduler

    // ── Playback Resources ───────────────────────────────────────────────
    private var mediaPlayer: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var vibrator: Vibrator? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ── Service Lifecycle ────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        audioRouter = AudioRouter(audioManager)
        preferences = AlarmPreferences(this)
        scheduler = AlarmScheduler(this)
        createNotificationChannel()
        Log.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val alarmId = intent?.getStringExtra(AlarmScheduler.EXTRA_ALARM_ID)
        Log.i(TAG, "onStartCommand action=$action alarmId=$alarmId")

        // STOP action: shutdown cleanly, no foreground notification needed
        if (action == ACTION_STOP_ALARM) {
            stopAlarm()
            return START_NOT_STICKY
        }

        // MUST call startForeground() within 5s of startForegroundService().
        // Use a minimal silent notification when idle (watchdog revive),
        // and the richer alarm notification when actually playing.
        val isAlarmAction = action == AlarmScheduler.ACTION_ALARM_TRIGGER ||
                            action == AlarmScheduler.ACTION_TEST_ALARM

        startForeground(NOTIFICATION_ID,
            if (isAlarmAction) buildActiveNotification() else buildIdleNotification())
        acquireWakeLock()

        if (isAlarmAction) {
            serviceScope.launch {
                executeAlarmRoutine()
                handlePostAlarm(alarmId)
            }
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseMediaPlayer()
        releaseVibrator()
        releaseWakeLock()
        releaseAudioFocus()
        serviceScope.cancel()
        Log.i(TAG, "Service destroyed — all resources released")
        super.onDestroy()
    }

    // ── Alarm Routine ────────────────────────────────────────────────────

    /**
     * Read user preferences, detect audio output topology, and execute
     * the appropriate playback action (earphones, speaker, or vibrate).
     * Uses the user's configured volumes — no forced override.
     */
    private suspend fun executeAlarmRoutine() {
        val outputType = audioRouter.detectOutputType()
        val action = audioRouter.resolveAction(
            outputType,
            preferences.noEarphoneAction.first()
        )
        val earphoneVol = preferences.earphoneVolume.first()
        val speakerVol = preferences.speakerVolume.first()
        val ringtone = preferences.globalRingtoneUri.first()
        val uri = resolveRingtoneUri(ringtone)

        Log.i(TAG, "Routing: output=$outputType action=$action earVol=$earphoneVol spkVol=$speakerVol")

        when (action) {
            AudioRouter.ResolvedAction.PLAY_VIA_EARPHONES ->
                playAudio(uri, earphoneVol, audioRouter.findEarphoneDevice())
            AudioRouter.ResolvedAction.PLAY_VIA_SPEAKER ->
                playAudio(uri, speakerVol, audioRouter.findSpeakerDevice())
            AudioRouter.ResolvedAction.VIBRATE_ONLY ->
                startRepeatingVibration()
        }
    }

    // ── Audio Playback ───────────────────────────────────────────────────

    /**
     * Play [ringtoneUri] through [preferredDevice] at [volumePercent]%.
     *
     * Two-phase playback:
     * 1. 500ms silent WAV wakes the Bluetooth/DAC pipeline (prevents truncation).
     * 2. On completion, loop the user's ringtone indefinitely.
     */
    private fun playAudio(ringtoneUri: Uri, volumePercent: Int, preferredDevice: AudioDeviceInfo?) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (max * volumePercent / 100).coerceIn(0, max), 0)
        requestAudioFocus()

        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build())
            if (preferredDevice != null) {
                setPreferredDevice(preferredDevice)
            }
            setWakeMode(this@AlarmAudioService, PowerManager.PARTIAL_WAKE_LOCK)
            setDataSource(this@AlarmAudioService,
                "android.resource://${packageName}/${R.raw.silent_500ms}".toUri())
            prepare()

            setOnCompletionListener { mp ->
                mp.reset()
                try {
                    mp.setDataSource(this@AlarmAudioService, ringtoneUri)
                    mp.prepare(); mp.isLooping = true; mp.start()
                } catch (e: Exception) {
                    Log.e(TAG, "Ringtone failed, falling back to default alarm", e)
                    fallbackToDefaultAlarm(mp)
                }
            }
            start()
        }
    }

    /** Fall back to the system default alarm sound. */
    private fun fallbackToDefaultAlarm(mp: MediaPlayer) {
        try {
            mp.reset()
            mp.setDataSource(this, android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI)
            mp.prepare(); mp.isLooping = true; mp.start()
        } catch (e: Exception) {
            Log.e(TAG, "Default alarm also failed", e)
        }
    }

    private fun resolveRingtoneUri(stored: String): Uri =
        stored.takeIf { it.isNotBlank() }?.toUri()
            ?: android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI

    /** Request transient audio focus for alarm playback. */
    private fun requestAudioFocus() {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> stopAlarm()
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> mediaPlayer?.pause()
                    AudioManager.AUDIOFOCUS_GAIN -> mediaPlayer?.start()
                }
            }.build()
        audioFocusRequest = req
        audioManager.requestAudioFocus(req)
    }

    // ── Vibration ────────────────────────────────────────────────────────

    /** Start a repeating vibration pattern (500ms on, 300ms off). */
    private fun startRepeatingVibration() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION") getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        vibrator?.vibrate(VibrationEffect.createWaveform(
            longArrayOf(0, 500, 300),
            intArrayOf(0, VibrationEffect.DEFAULT_AMPLITUDE, 0),
            0 // repeat indefinitely
        ))
    }

    // ── Resource Management ──────────────────────────────────────────────

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SilentAlarm::WakeLock").apply {
            acquire(10 * 60 * 1000L)
        }
    }

    private fun releaseMediaPlayer() {
        mediaPlayer?.apply { if (isPlaying) stop(); reset(); release() }
        mediaPlayer = null
    }

    private fun releaseVibrator() { vibrator?.cancel(); vibrator = null }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun releaseAudioFocus() {
        audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        audioFocusRequest = null
    }

    // ── Notification ─────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        // Idle channel: silent, no status bar icon — for watchdog background presence
        val idleCh = NotificationChannel(CHANNEL_IDLE,
            getString(R.string.notification_channel_alarm),
            NotificationManager.IMPORTANCE_MIN).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(idleCh)

        // Active channel: shows status bar icon + stop action — for alarm playback
        val activeCh = NotificationChannel(CHANNEL_ACTIVE,
            "Alarm Playing",
            NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Shown when the alarm is actively playing"
            setShowBadge(false)
        }
        nm.createNotificationChannel(activeCh)
    }

    /** Minimal, silent notification for watchdog background presence. */
    private fun buildIdleNotification(): Notification {
        val contentPi = PendingIntent.getActivity(this, 2003,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_IDLE)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Waiting for next alarm")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(contentPi)
            .build()
    }

    /** Richer notification with stop button shown during alarm playback. */
    private fun buildActiveNotification(): Notification {
        val stopPi = PendingIntent.getService(this, 2002,
            Intent(this, AlarmAudioService::class.java).apply { action = ACTION_STOP_ALARM },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val contentPi = PendingIntent.getActivity(this, 2003,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val subtitle = when (audioRouter.detectOutputType()) {
            AudioRouter.AudioOutputType.EARPHONES_AVAILABLE -> "Playing through earphones"
            AudioRouter.AudioOutputType.SPEAKER_ONLY -> "Earphones not detected"
        }

        // fix stop button not showing
        val stopIcon = Icon.createWithResource(this, android.R.drawable.ic_media_pause)
        val stopAction = Notification.Action.Builder(stopIcon, "Stop", stopPi).build()

        return Notification.Builder(this, CHANNEL_ACTIVE)
            .setContentTitle("Alarm Active")
            .setContentText(subtitle)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true)
            .setContentIntent(contentPi)
            .addAction(stopAction)
            .setStyle(Notification.MediaStyle().setShowActionsInCompactView(0))
            .build()
    }

    /**
     * After the alarm routine completes: disable one-shot alarms so they
     * don't fire again, and re-schedule recurring alarms for the next day.
     * Also pushes a tile update so the QS tile reflects the new state.
     */
    private suspend fun handlePostAlarm(alarmId: String?) {
        if (alarmId == null) return // test alarm or missing ID — nothing to do

        val alarms = preferences.getAlarms().first()
        val alarm = alarms.find { it.id == alarmId } ?: return

        if (alarm.daysOfWeek.isEmpty()) {
            // One-shot: auto-disable so it doesn't fire again tomorrow
            preferences.toggleAlarm(alarmId, false)
            AlarmTileService.requestTileUpdate(this)
            Log.i(TAG, "One-shot alarm '$alarmId' auto-disabled")
        } else {
            // Recurring: re-schedule for next matching day
            scheduler.scheduleOne(alarm)
            Log.i(TAG, "Recurring alarm '$alarmId' re-scheduled")
        }
    }

    /**
     * Stop active alarm playback and transition to idle keep-alive mode.
     * The foreground service stays alive with a silent notification so the
     * process is less likely to be killed before the next scheduled alarm.
     */
    private fun stopAlarm() {
        Log.i(TAG, "Alarm stopped — transitioning to idle keep-alive")
        releaseMediaPlayer()
        releaseVibrator()
        releaseWakeLock()
        releaseAudioFocus()
        // Check whether there are enabled alarms worth protecting
        serviceScope.launch {
            val hasEnabled = preferences.getAlarms().first().any { it.enabled }
            if (hasEnabled) {
                // Update notification to idle — service stays alive
                startForeground(NOTIFICATION_ID, buildIdleNotification())
            } else {
                // No alarms left — stop the service
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }
}
