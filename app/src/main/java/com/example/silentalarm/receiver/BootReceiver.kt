package com.example.silentalarm.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.silentalarm.daemon.ShizukuDaemonManager
import com.example.silentalarm.data.AlarmPreferences
import com.example.silentalarm.data.AlarmScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Re-schedules all enabled alarms on boot (AlarmManager clears alarms on reboot)
 * and re-applies Shizuku anti-kill tweaks.
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
                val shizuku = ShizukuDaemonManager(context)

                val alarms = preferences.getAlarms().first()
                scheduler.scheduleAll(alarms)
                Log.i(TAG, "Re-scheduled ${alarms.count { it.enabled }} alarms")

                if (alarms.any { it.enabled }) {
                    scheduler.startIdleService()
                }

                if (shizuku.isShizukuAvailable() && shizuku.isShizukuPermitted()) {
                    shizuku.stopWatchdogDaemon()
                    shizuku.applyAntiKillingTweaks()
                    shizuku.startWatchdogDaemon()
                    Log.i(TAG, "Success Daemon.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Boot processing failed", e)
            } finally {
                pending.finish()
            }
        }
    }
}
