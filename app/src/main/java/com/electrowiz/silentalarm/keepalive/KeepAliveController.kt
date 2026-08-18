package com.electrowiz.silentalarm.keepalive

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.electrowiz.silentalarm.data.AlarmPreferences
import com.electrowiz.silentalarm.data.AlarmScheduler
import com.electrowiz.silentalarm.daemon.PrivilegedShell
import com.electrowiz.silentalarm.daemon.ShellManager
import com.electrowiz.silentalarm.service.AlarmAudioService
import com.electrowiz.silentalarm.util.AlarmDiagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

/**
 * Single owner of the two optional keep-alive layers, reconciled together but
 * fully independent of each other:
 *
 * - **Notification keep-alive** ([AlarmPreferences.keepAliveEnabled]) — the
 *   idle foreground service plus an inexact recovery alarm that restores it
 *   after the process is killed.
 * - **Privileged watchdog** ([AlarmPreferences.privilegedEnabled]) — a shell
 *   loop under Shizuku/root UID that restarts the service if the process dies.
 *
 * Neither layer depends on the other, and the core alarm scheduler never
 * depends on this class. Watchdog is deliberately simple: one marker string
 * in the loop command line, one `pkill -f` to stop it — no PID files.
 */
class KeepAliveController private constructor(context: Context) {

    companion object {
        private const val TAG = "KeepAliveController"

        /** Action on [AlarmAudioService] that restarts the idle keep-alive. */
        const val ACTION_KEEP_ALIVE = "com.electrowiz.silentalarm.ACTION_KEEP_ALIVE"

        private const val RECOVERY_REQUEST_CODE = 8001

        /**
         * Keep-alive heartbeat interval. 2h balances how quickly the idle
         * notification comes back after a ROM kills the process (when no
         * watchdog privilege is available) against quiet device wakeups —
         * the alive-idle heartbeat is silent and only re-arms.
         */
        private const val RECOVERY_INTERVAL_MS = 2 * 60 * 60 * 1000L

        /**
         * Delay used after boot. Android 15+ forbids starting a mediaPlayback
         * foreground service from BOOT_COMPLETED directly, so an exact alarm
         * starts it a moment later.
         */
        private const val BOOT_RECOVERY_DELAY_MS = 15_000L

        private const val WATCHDOG_INTERVAL_SEC = 20

        @Volatile
        private var instance: KeepAliveController? = null

        fun get(context: Context): KeepAliveController =
            instance ?: synchronized(this) {
                instance ?: KeepAliveController(context.applicationContext)
                    .also { instance = it }
            }
    }

    private val appContext: Context = context.applicationContext
    private val preferences = AlarmPreferences(appContext)
    private val alarmManager =
        appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val shellManager = ShellManager.get(appContext)

    /**
     * All keep-alive reconciliation is serialized through this single-thread
     * dispatcher so rapid CRUD/Tile/undo operations cannot finish out of order
     * and leave recovery alarms or the idle service in a stale state.
     */
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val watchdogMutex = Mutex()

    @Volatile
    private var watchdogRunning = false

    // ── Public API ───────────────────────────────────────────────────────

    /** Fire-and-forget wrapper used by the UI, tile, boot and service. */
    fun syncAsync(hasEnabledAlarms: Boolean, startServiceNow: Boolean = true) {
        syncScope.launch {
            try {
                sync(hasEnabledAlarms, startServiceNow)
            } catch (e: Exception) {
                Log.w(TAG, "Keep-alive sync failed", e)
            }
        }
    }

    /**
     * Reconcile both keep-alive layers with the current alarm state. The two
     * layers are independent: notification keep-alive follows
     * [AlarmPreferences.keepAliveEnabled], the watchdog follows
     * [AlarmPreferences.privilegedEnabled].
     *
     * @param startServiceNow when false, the idle service is not started
     * directly (used from boot, where Android 15+ blocks direct mediaPlayback
     * FGS starts); the recovery alarm starts it shortly after instead.
     */
    suspend fun sync(hasEnabledAlarms: Boolean, startServiceNow: Boolean = true) {
        syncKeepAlive(hasEnabledAlarms, startServiceNow)
        syncWatchdog(hasEnabledAlarms)
    }

    private suspend fun syncKeepAlive(hasEnabledAlarms: Boolean, startServiceNow: Boolean) {
        val keepAlive = preferences.keepAliveEnabled.first()
        if (keepAlive && hasEnabledAlarms) {
            if (startServiceNow) startIdleService()
            scheduleRecovery(if (startServiceNow) RECOVERY_INTERVAL_MS else BOOT_RECOVERY_DELAY_MS)
        } else {
            cancelRecovery()
            stopIdleServiceIfIdle()
        }
    }

    /** Whether the idle service should keep running after playback ends. */
    suspend fun shouldStayIdle(hasEnabledAlarms: Boolean): Boolean =
        hasEnabledAlarms && preferences.keepAliveEnabled.first()

    /** Called by the service when it keeps running as the idle FGS. */
    fun onIdleServiceStarted() {
        scheduleRecovery(RECOVERY_INTERVAL_MS)
    }

    /** Called by the service when it stops itself (keep-alive off / no alarms). */
    fun onIdleServiceStopped() {
        cancelRecovery()
    }

    /** Re-arm the recovery alarm (used when a recovery intent arrives mid-ring). */
    fun scheduleRecovery() {
        scheduleRecovery(RECOVERY_INTERVAL_MS)
    }

    // ── Recovery alarm ───────────────────────────────────────────────────

    private fun scheduleRecovery(delayMs: Long) {
        if (!canScheduleExactAlarms()) {
            Log.w(TAG, "Exact alarm permission missing — recovery alarm skipped")
            AlarmDiagnostics.log(
                appContext,
                "recovery_schedule_skipped",
                mapOf("reason" to "exact_alarm_permission_missing")
            )
            return
        }
        val pi = buildRecoveryPendingIntent()
        val triggerAt = System.currentTimeMillis() + delayMs.coerceAtLeast(0L)
        try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            Log.d(TAG, "Recovery alarm armed in ${delayMs / 1000}s")
            AlarmDiagnostics.log(
                appContext,
                "recovery_scheduled",
                mapOf("trigger_at_ms" to triggerAt, "delay_ms" to delayMs)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to arm recovery alarm", e)
            AlarmDiagnostics.log(
                appContext,
                "recovery_schedule_failed",
                mapOf("reason" to e.javaClass.simpleName)
            )
        }
    }

    private fun canScheduleExactAlarms(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    private fun cancelRecovery() {
        val pi = buildRecoveryPendingIntent()
        alarmManager.cancel(pi)
        pi.cancel()
        Log.d(TAG, "Recovery alarm cancelled")
    }

    private fun buildRecoveryPendingIntent(): PendingIntent {
        val intent = Intent(appContext, AlarmAudioService::class.java).apply {
            action = ACTION_KEEP_ALIVE
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getForegroundService(
            appContext,
            RECOVERY_REQUEST_CODE,
            intent,
            flags
        )
    }

    // ── Idle foreground service ──────────────────────────────────────────

    private fun startIdleService() {
        val intent = Intent(appContext, AlarmAudioService::class.java).apply {
            action = ACTION_KEEP_ALIVE
        }
        try {
            appContext.startForegroundService(intent)
            Log.d(TAG, "Idle service started for keep-alive")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start idle service", e)
        }
    }

    /**
     * Ask the idle service to stop itself — but never interrupt a ringing or
     * snoozing session, and never spawn a new service just to stop it.
     */
    private fun stopIdleServiceIfIdle() {
        if (!AlarmAudioService.serviceAlive.value || AlarmAudioService.playbackActive.value) {
            return
        }
        val intent = Intent(appContext, AlarmAudioService::class.java).apply {
            action = AlarmScheduler.ACTION_STOP_ALARM
        }
        runCatching { appContext.startService(intent) }
            .onFailure { Log.w(TAG, "Stop intent not delivered", it) }
    }

    // ── Watchdog (privileged layer) ──────────────────────────────────────

    private suspend fun syncWatchdog(hasEnabledAlarms: Boolean) {
        val privileged = preferences.privilegedEnabled.first()
        val shell = shellManager.current()
        watchdogMutex.withLock {
            val shouldRun = privileged && hasEnabledAlarms && shell != null
            if (shouldRun && !watchdogRunning) {
                watchdogRunning = startWatchdogLocked(shell)
            } else if (!shouldRun && watchdogRunning) {
                // Only clear the flag when pkill actually succeeded (or there
                // was no way to run it); otherwise a future sync must retry.
                watchdogRunning = !stopWatchdogLocked(shell)
            }
        }
    }

    /**
     * Kill any previous watchdog instance first, then start a fresh one. Old
     * instances can outlive an app-process death and must not accumulate.
     */
    private suspend fun startWatchdogLocked(shell: PrivilegedShell): Boolean {
        val pkg = appContext.packageName
        val marker = "SILENTALARM_WD_$pkg"
        val safePattern = "[" + marker.first() + "]" + marker.substring(1)
        val startCmd = "am start-foreground-service $pkg/.service.AlarmAudioService"
        val script = buildString {
            append("nohup sh -c '")
            append("MARKER=$marker; ")
            append("while true; do ")
            append("if ! pidof $pkg >/dev/null 2>&1; then $startCmd; fi; ")
            append("sleep $WATCHDOG_INTERVAL_SEC; ")
            append("done' >/dev/null 2>&1 &")
        }

        // Best-effort cleanup; `; true` keeps the shell exit code 0 even when
        // no previous watchdog existed.
        try {
            shell.execute("pkill -f '$safePattern' 2>/dev/null; true")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clean old watchdog instance", e)
        }

        val result = try {
            shell.execute(script)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Watchdog start failed with exception", e)
            null
        }
        return if (result != null && result.isSuccess) {
            Log.i(TAG, "Watchdog started for $pkg")
            true
        } else {
            Log.w(TAG, "Watchdog start failed: ${result?.output ?: "no shell result"}")
            false
        }
    }

    private suspend fun stopWatchdogLocked(shell: PrivilegedShell?): Boolean {
        val pkg = appContext.packageName
        if (shell == null) {
            Log.w(TAG, "No privileged shell available to stop watchdog")
            return false
        }
        val marker = "SILENTALARM_WD_$pkg"
        val safePattern = "[" + marker.first() + "]" + marker.substring(1)
        val result = try {
            shell.execute("pkill -f '$safePattern' 2>/dev/null; true")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Watchdog stop failed with exception", e)
            null
        }
        return if (result != null && result.isSuccess) {
            Log.i(TAG, "Watchdog stopped for $pkg")
            true
        } else {
            Log.w(TAG, "Watchdog stop failed: ${result?.output ?: "no shell result"}")
            false
        }
    }
}
