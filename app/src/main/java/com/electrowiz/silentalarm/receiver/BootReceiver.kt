package com.electrowiz.silentalarm.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.electrowiz.silentalarm.data.AlarmPreferences
import com.electrowiz.silentalarm.data.AlarmScheduler
import com.electrowiz.silentalarm.daemon.ShellManager
import com.electrowiz.silentalarm.keepalive.KeepAliveController
import com.electrowiz.silentalarm.util.AlarmDiagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Re-schedules all enabled alarms after reboot (AlarmManager clears alarms)
 * and after the system wall-clock time changes. Timezone changes deliberately
 * do not trigger rescheduling: each alarm keeps the absolute trigger time that
 * was captured in its own timezone when it was saved.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> process(context, isBoot = true, action = intent.action)
            Intent.ACTION_TIME_CHANGED -> process(context, isBoot = false, action = intent.action)
            else -> return
        }
    }

    private fun process(context: Context, isBoot: Boolean, action: String?) {
        Log.i(TAG, if (isBoot) "Boot completed — re-scheduling alarms" else "Time changed — re-scheduling alarms")
        val pending = goAsync()

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val preferences = AlarmPreferences(context)
                val scheduler = AlarmScheduler(context)

                preferences.migrateOrReset()

                if (isBoot) {
                    // A reboot cancels both AlarmManager entries and any active
                    // ringing session. Never resume the old ring after reboot.
                    preferences.clearResumeAlarmId()
                }

                val alarms = preferences.getAlarms().first()
                scheduler.reconcile(alarms)
                Log.i(TAG, "Re-scheduled ${alarms.count { it.enabled }} alarms")
                AlarmDiagnostics.log(
                    context,
                    "boot_reconcile",
                    mapOf(
                        "action" to action,
                        "is_boot" to isBoot,
                        "alarm_count" to alarms.size,
                        "enabled_alarm_count" to alarms.count { it.enabled },
                        "exact_alarm_allowed" to scheduler.canScheduleExactAlarms()
                    )
                )

                if (isBoot) {
                    val shellManager = ShellManager.get(context)
                    val keepAlive = KeepAliveController.get(context)

                    // Restore the optional keep-alive layer without starting the
                    // FGS directly (Android 15+ blocks mediaPlayback FGS starts
                    // from BOOT_COMPLETED; the recovery alarm starts it shortly).
                    keepAlive.sync(
                        hasEnabledAlarms = alarms.any { it.enabled },
                        startServiceNow = false
                    )

                    if (preferences.privilegedEnabled.first() &&
                        shellManager.current() != null
                    ) {
                        shellManager.applyAntiKillTweaks()
                        Log.i(TAG, "Privileged anti-kill tweaks applied")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, if (isBoot) "Boot processing failed" else "Time-change processing failed", e)
            } finally {
                pending.finish()
            }
        }
    }
}
