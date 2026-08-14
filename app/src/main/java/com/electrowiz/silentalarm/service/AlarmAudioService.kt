package com.electrowiz.silentalarm.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Intent
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.IBinder
import android.os.PowerManager
import android.os.Vibrator
import android.util.Log
import com.electrowiz.silentalarm.data.AlarmPreferences
import com.electrowiz.silentalarm.data.AlarmScheduler
import com.electrowiz.silentalarm.keepalive.KeepAliveController
import com.electrowiz.silentalarm.util.AudioRouter
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Foreground service for alarm audio playback.
 *
 * ## Entry Points (via intent actions)
 * - [AlarmScheduler.ACTION_ALARM_TRIGGER] — scheduled alarm from AlarmManager
 * - [AlarmScheduler.ACTION_TEST_ALARM] — instant test fire from UI button
 * - [KeepAliveController.ACTION_KEEP_ALIVE] — background recovery alarm
 * - [AlarmScheduler.ACTION_STOP_ALARM] — stop from notification action or UI button
 * - [AlarmScheduler.ACTION_SNOOZE_ALARM] — snooze from notification swipe or button
 * - [AlarmScheduler.ACTION_SNOOZE_EXPIRED] — snooze delay elapsed, resume ringing
 *
 * ## State Machine
 * Playback state is a sealed type ([PlaybackState]) mutated only on the main
 * thread inside [serviceScope], so intent handlers are fully serialized and
 * cannot interleave. A STOP cancels any in-flight trigger routine, keeping
 * the "stop wins over trigger" semantics without boolean flag races.
 *
 * ## Notification
 * The ringing notification has Snooze/Stop actions, and its delete intent
 * snoozes the alarm for 5 minutes — swiping it away (Android 13+ allows
 * dismissing FGS notifications) is never a dead end: the snooze notification
 * returns immediately and the stop button stays reachable.
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

        /**
         * Process-local playback activity signal for the UI (stop button
         * state). Service and UI share one process; on process death the
         * alarm dies with it, so a false default stays accurate.
         */
        val playbackActive = MutableStateFlow(false)

        val activeAlarmId = MutableStateFlow<String?>(null)

        /** Whether the service process is currently running (any state). */
        val serviceAlive = MutableStateFlow(false)
    }

    /**
     * Playback state machine. Only mutated inside [serviceScope] coroutines
     * on the main thread; read freely elsewhere for guards.
     */
    internal sealed interface PlaybackState {
        data object Idle : PlaybackState
        data class Ringing(val alarmId: String?) : PlaybackState
        data class Snoozing(val alarmId: String?) : PlaybackState
    }

    /** Single mutation point — keeps [playbackActive] in sync with the state. */
    internal fun updatePlaybackState(newState: PlaybackState) {
        state = newState
        playbackActive.value = newState is PlaybackState.Ringing || newState is PlaybackState.Snoozing
        activeAlarmId.value = when (newState) {
            is PlaybackState.Ringing -> newState.alarmId
            is PlaybackState.Snoozing -> newState.alarmId
            PlaybackState.Idle -> null
        }
    }

    /**
     * Post the active ringing notification reflecting the actual playback
     * mode. A null action falls back to the current audio route and is only
     * used for the brief pre-routing window — the routed path re-posts with
     * the resolved mode immediately after.
     */
    internal fun postActiveNotification(action: AudioRouter.ResolvedAction?) {
        startForeground(
            AlarmNotificationController.NOTIFICATION_ID,
            notifications.buildActiveNotification(action)
        )
    }

    // ── Dependencies ─────────────────────────────────────────────────────
    internal lateinit var audioManager: AudioManager
    internal lateinit var audioRouter: AudioRouter
    internal lateinit var preferences: AlarmPreferences
    internal lateinit var scheduler: AlarmScheduler
    internal lateinit var notifications: AlarmNotificationController
    internal lateinit var keepAliveController: KeepAliveController

    // ── Playback Resources ───────────────────────────────────────────────
    internal var mediaPlayer: MediaPlayer? = null
    internal var wakeLock: PowerManager.WakeLock? = null
    internal var vibrator: Vibrator? = null
    internal var audioFocusRequest: AudioFocusRequest? = null
    internal var autoStopJob: Job? = null
    internal var idleRefreshJob: Job? = null
    private var snoozeJob: Job? = null
    private var triggerJob: Job? = null
    private var postAlarmJob: Job? = null
    internal val previousVolumes = mutableMapOf<Int, Int>()
    internal var state: PlaybackState = PlaybackState.Idle
    /** Resolved playback mode of the current ring session (drives the notification text). */
    internal var activeAction: AudioRouter.ResolvedAction? = null
    private var lastRingTimeMs = 0L
    internal var noisyReceiver: BroadcastReceiver? = null
    internal val defaultAlarmUri = android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI

    internal val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    internal val playbackMutex = Mutex()

    // ── Service Lifecycle ────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        serviceAlive.value = true
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        audioRouter = AudioRouter(audioManager)
        preferences = AlarmPreferences(this)
        scheduler = AlarmScheduler(this)
        keepAliveController = KeepAliveController.get(this)
        notifications = AlarmNotificationController(this, scheduler, audioRouter)
        notifications.createChannels()
        Log.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val alarmId = intent?.getStringExtra(AlarmScheduler.EXTRA_ALARM_ID)
        Log.i(TAG, "onStartCommand action=$action alarmId=$alarmId")

        when (action) {
            AlarmScheduler.ACTION_STOP_ALARM -> {
                // No foreground post needed here: either the service is
                // already foreground (its notification stays until the
                // handler replaces it) or it was plain-started just to stop,
                // in which case the handler decides whether to promote to
                // the idle keep-alive.
                triggerJob?.cancel()
                triggerJob = null
                // Manual stop — no "alarm stopped" notification. That one is
                // reserved for alarms the user missed (auto-timeout).
                serviceScope.launch { playbackMutex.withLock { handleStop(notifyStopped = false) } }
            }

            AlarmScheduler.ACTION_ALARM_TRIGGER,
            AlarmScheduler.ACTION_TEST_ALARM -> {
                startRinging(alarmId, reRing = false, runPostAlarm = true)
            }

            AlarmScheduler.ACTION_SNOOZE_ALARM -> {
                serviceScope.launch { playbackMutex.withLock { handleSnooze() } }
            }

            AlarmScheduler.ACTION_SNOOZE_EXPIRED -> {
                if (state == PlaybackState.Idle) {
                    startRinging(alarmId, reRing = true, runPostAlarm = false)
                } else {
                    serviceScope.launch { playbackMutex.withLock { handleSnoozeExpired() } }
                }
            }

            else -> {
                // KEEP_ALIVE (recovery heartbeat) or a sticky restart with no intent.
                if (state != PlaybackState.Idle) {
                    // Ringing/snoozing — leave the notification untouched and
                    // only re-arm the recovery alarm.
                    keepAliveController.scheduleRecovery()
                } else if (idleRefreshJob?.isActive == true) {
                    // Routine heartbeat while the idle FGS is already up:
                    // silently re-arm. No startForeground, no notification
                    // re-post — the visible notification stays in place.
                    keepAliveController.scheduleRecovery()
                } else {
                    // Fresh start after process death (real recovery): post
                    // the idle placeholder synchronously to satisfy the 5s
                    // FGS deadline, then let transitionToIdle refresh it with
                    // the actual next-alarm time.
                    startForeground(
                        AlarmNotificationController.NOTIFICATION_ID,
                        notifications.buildIdleNotification(emptyList())
                    )
                    // cancelPendingSnooze = false: if the process died while
                    // snoozing, the AlarmManager-backed snooze-expiry alarm is
                    // still armed and must be left alone so it can resume
                    // ringing. An explicit stop/snooze cancels it instead.
                    serviceScope.launch {
                        playbackMutex.withLock {
                            // Re-check inside the lock: a trigger may have
                            // started while this coroutine was queued, and it
                            // must not tear down the ringing session.
                            if (state == PlaybackState.Idle) {
                                transitionToIdle(
                                    showStoppedNotification = false,
                                    cancelPendingSnooze = false
                                )
                            }
                        }
                    }
                }
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        triggerJob?.cancel(); triggerJob = null
        autoStopJob?.cancel(); autoStopJob = null
        idleRefreshJob?.cancel(); idleRefreshJob = null
        snoozeJob?.cancel(); snoozeJob = null
        postAlarmJob?.cancel(); postAlarmJob = null
        // Cancel the scope before tearing down so no queued listener callback
        // (completion / error / audio-focus) can run during release. All
        // playback mutations run on the main dispatcher, so this release
        // sequence is atomic with respect to the playback mutex holders.
        serviceScope.cancel()
        serviceAlive.value = false
        updatePlaybackState(PlaybackState.Idle)
        releaseMediaPlayer()
        releaseVibrator()
        releaseWakeLock()
        releaseAudioFocus()
        restoreVolume()
        Log.i(TAG, "Service destroyed — all resources released")
        super.onDestroy()
    }

    // ── Intent Handlers (serialized on the main thread) ──────────────────

    /**
     * Promote the service to foreground with the active notification first,
     * then run the trigger path on the playback mutex. Posting synchronously
     * satisfies the 5s foreground-service deadline and avoids the idle→active
     * same-ID replacement race seen on OEM ROMs.
     */
    private fun startRinging(alarmId: String?, reRing: Boolean, runPostAlarm: Boolean) {
        idleRefreshJob?.cancel(); idleRefreshJob = null
        postActiveNotification(null)
        triggerJob?.cancel()
        triggerJob = serviceScope.launch {
            playbackMutex.withLock { handleTrigger(alarmId, reRing, runPostAlarm) }
        }
    }

    /**
     * Validate and start an alarm session.
     *
     * @param reRing true when this is a snooze re-ring: the alarm only needs
     * to still exist, because one-shot alarms auto-disable themselves at the
     * first trigger and are already disabled while snoozed.
     */
    private suspend fun handleTrigger(
        alarmId: String?,
        reRing: Boolean,
        runPostAlarm: Boolean = !reRing
    ) {
        // The alarm may have been deleted or disabled since it was armed
        // (e.g. process died between the delete write and the cancel).
        // Verify before ringing; cancel the stale schedule and stay silent
        // instead of playing a ghost alarm.
        val alarms = preferences.getAlarms().first()
        val stale = when {
            alarmId == null -> false // test alarm (or snoozed test) — always allowed
            reRing -> alarms.none { it.id == alarmId }
            else -> alarms.none { it.id == alarmId && it.enabled }
        }
        if (stale) {
            Log.w(TAG, "Stale trigger for '$alarmId' — alarm no longer exists or is disabled")
            if (alarmId != null) scheduler.cancelAlarm(alarmId)
            // If another alarm is already ringing or snoozed, leave it alone.
            if (state == PlaybackState.Idle) {
                transitionToIdle(showStoppedNotification = false)
            }
            return
        }

        // A fresh trigger supersedes any pending snooze from a previous session.
        snoozeJob?.cancel(); snoozeJob = null
        scheduler.cancelSnoozeExpiry()

        lastRingTimeMs = System.currentTimeMillis()
        updatePlaybackState(PlaybackState.Ringing(alarmId))
        acquireWakeLock()
        try {
            executeAlarmRoutine()
            if (runPostAlarm && alarmId != null) runPostAlarm(alarmId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Never leave the service "ringing" with no sound (e.g. a
            // DataStore read fails): tear down into the idle keep-alive.
            Log.e(TAG, "Alarm routine failed — stopping", e)
            transitionToIdle(showStoppedNotification = false)
        }
    }

    /**
     * Post-trigger bookkeeping (disable one-shot / reschedule recurring) runs
     * in its own non-cancellable job. A later trigger or a stop — both of
     * which legitimately cancel the playback session — must never swallow
     * these writes: otherwise a one-shot alarm stays enabled and a recurring
     * alarm is not re-armed until the app is opened again.
     */
    private fun runPostAlarm(alarmId: String) {
        postAlarmJob = serviceScope.launch {
            withContext(NonCancellable) { handlePostAlarm(alarmId) }
        }
    }

    /**
     * Snooze the ringing alarm for [AlarmScheduler.SNOOZE_DURATION_MS].
     * Also re-arms the timer and re-posts the notification when the user
     * swiped it away while already snoozing.
     */
    private suspend fun handleSnooze() {
        val current = state
        when (current) {
            is PlaybackState.Ringing -> {
                Log.i(TAG, "Snoozing alarm for ${AlarmScheduler.SNOOZE_DURATION_MS / 1000}s")
                autoStopJob?.cancel(); autoStopJob = null
                idleRefreshJob?.cancel(); idleRefreshJob = null
                updatePlaybackState(PlaybackState.Snoozing(current.alarmId))
                activeAction = null
                releaseMediaPlayer()
                releaseVibrator()
                releaseWakeLock()
                releaseAudioFocus()
                restoreVolume()
            }
            is PlaybackState.Snoozing -> Log.i(TAG, "Snooze refreshed")
            PlaybackState.Idle -> {
                Log.w(TAG, "Snooze request while idle — ignored")
                return
            }
        }

        val snoozing = state as PlaybackState.Snoozing
        armSnoozeExpiry(snoozing.alarmId)
        // Re-post after the swipe removed it — the stop button stays available.
        startForeground(
            AlarmNotificationController.NOTIFICATION_ID,
            notifications.buildSnoozeNotification()
        )
    }

    /** Snooze delay elapsed — resume ringing with a fresh auto-stop timer. */
    private suspend fun handleSnoozeExpired() {
        val snoozing = state as? PlaybackState.Snoozing ?: run {
            Log.w(TAG, "Snooze expiry without an active snooze — ignored")
            return
        }
        Log.i(TAG, "Snooze expired — resuming ringing")
        lastRingTimeMs = System.currentTimeMillis()
        updatePlaybackState(PlaybackState.Ringing(snoozing.alarmId))
        postActiveNotification(null)
        acquireWakeLock()
        try {
            executeAlarmRoutine()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Snooze re-ring failed — stopping", e)
            transitionToIdle(showStoppedNotification = false)
        }
        // handlePostAlarm already ran at the original trigger — don't repeat it.
    }

    /**
     * Stop active playback (or snooze) and transition to idle keep-alive.
     * Cancels an in-flight trigger routine so a stop that races a trigger
     * still wins, exactly as before the state-machine refactor.
     *
     * @param notifyStopped when true and the alarm was ringing, post the
     * one-shot "alarm stopped" notification. Manual stops pass false; auto
     * timeout (missed alarm) keeps the default true.
     */
    internal suspend fun handleStop(notifyStopped: Boolean = true) {
        Log.i(TAG, "Alarm stopped — transitioning to idle keep-alive")
        // Cancel an in-flight trigger routine (mid-setup or already playing)
        // so a stop always wins. Note that a coroutine waiting to start also
        // reports isActive=true, so this cancels queued triggers too — a
        // stop that arrives after the trigger intent still wins.
        if (triggerJob?.isActive == true) triggerJob?.cancel()
        transitionToIdle(
            showStoppedNotification = notifyStopped && state is PlaybackState.Ringing
        )
    }

    /**
     * Common teardown: release every playback resource, optionally post the
     * one-shot "alarm stopped" notification, then let the optional keep-alive
     * layer decide whether the service stays alive as the idle FGS.
     */
    private suspend fun transitionToIdle(
        showStoppedNotification: Boolean,
        cancelPendingSnooze: Boolean = true
    ) {
        autoStopJob?.cancel(); autoStopJob = null
        snoozeJob?.cancel(); snoozeJob = null
        idleRefreshJob?.cancel(); idleRefreshJob = null
        if (cancelPendingSnooze) scheduler.cancelSnoozeExpiry()

        val ringing = state as? PlaybackState.Ringing
        updatePlaybackState(PlaybackState.Idle)
        activeAction = null
        releaseMediaPlayer()
        releaseVibrator()
        releaseWakeLock()
        releaseAudioFocus()
        restoreVolume()

        // Best-effort bookkeeping. A DataStore/notification failure must never
        // leave the ringing FGS notification stranded on screen, so the
        // teardown below is additionally guarded by an outer catch.
        val alarms = runCatching { preferences.getAlarms().first() }.getOrDefault(emptyList())
        if (showStoppedNotification) {
            val ringingId = ringing?.alarmId
            val alarm = ringingId?.let { id -> alarms.find { it.id == id } }
            // Auto-timeout always counts as missed — including test alarms
            // (alarmId == null) which have no entity to look up.
            if (alarm != null || ringingId == null) {
                runCatching { notifications.postStoppedNotification(lastRingTimeMs) }
                    .onFailure { Log.w(TAG, "Failed to post missed-alarm notification", it) }
            }
        }

        // The optional keep-alive layer decides whether the idle service
        // should stay alive. When it's off (or no alarms are enabled) the
        // service stops; alarms still fire later through AlarmManager.
        try {
            val stayIdle = runCatching {
                keepAliveController.shouldStayIdle(alarms.any { it.enabled })
            }.getOrDefault(false)
            if (stayIdle) {
                startForeground(
                    AlarmNotificationController.NOTIFICATION_ID,
                    notifications.buildIdleNotification(alarms)
                )
                keepAliveController.onIdleServiceStarted()
                startIdleRefreshTicker()
            } else {
                keepAliveController.onIdleServiceStopped()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        } catch (e: Exception) {
            // Last-resort teardown: never leave the ringing notification up.
            Log.e(TAG, "Idle transition failed — force-stopping", e)
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf()
        }
    }

    /**
     * Arm the snooze-expiry timer. Prefers an exact alarm (survives process
     * death, reliable in Doze); falls back to an in-process delay when the
     * exact-alarm permission is unavailable.
     */
    private fun armSnoozeExpiry(alarmId: String?) {
        snoozeJob?.cancel(); snoozeJob = null
        val armed = scheduler.scheduleSnoozeExpiry(alarmId)
        if (!armed) {
            snoozeJob = serviceScope.launch {
                delay(AlarmScheduler.SNOOZE_DURATION_MS)
                playbackMutex.withLock { handleSnoozeExpired() }
            }
        }
    }

    // ── Alarm Routine ────────────────────────────────────────────────────
    // Playback execution lives in AlarmAudioPlayback.kt; the idle
    // notification ticker and post-alarm bookkeeping live in
    // AlarmAudioServiceLifecycle.kt.
}
