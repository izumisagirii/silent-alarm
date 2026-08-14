package com.electrowiz.silentalarm.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.electrowiz.silentalarm.data.AlarmPreferences
import com.electrowiz.silentalarm.data.AlarmScheduler
import com.electrowiz.silentalarm.daemon.ShellManager
import com.electrowiz.silentalarm.keepalive.KeepAliveController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Re-schedules all enabled alarms on boot (AlarmManager clears alarms on reboot)
 * and re-applies Shizuku anti-kill tweaks. Timezone/time changes deliberately
 * do not trigger rescheduling: each alarm keeps the absolute trigger time that
 * was captured when it was saved.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        Log.i(TAG, "Boot completed — re-scheduling alarms")
        val pending = goAsync()

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val preferences = AlarmPreferences(context)
                val scheduler = AlarmScheduler(context)
                val shellManager = ShellManager.get(context)
                val keepAlive = KeepAliveController.get(context)

                preferences.migrateOrReset()
                val alarms = preferences.getAlarms().first()
                scheduler.reconcile(alarms)
                Log.i(TAG, "Re-scheduled ${alarms.count { it.enabled }} alarms")

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
            } catch (e: Exception) {
                Log.e(TAG, "Boot processing failed", e)
            } finally {
                pending.finish()
            }
        }
    }
}
