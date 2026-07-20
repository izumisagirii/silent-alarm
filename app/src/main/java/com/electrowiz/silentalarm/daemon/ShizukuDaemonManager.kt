package com.electrowiz.silentalarm.daemon

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * ## Shizuku Native Daemon Manager
 *
 * Provides the GKD-style anti-killing mechanisms via Shizuku's [UserService].
 * When Shizuku is running and authorized, this class applies three layers of protection:
 *
 * ### Layer 1: System-Level Whitelisting
 * Executes `cmd deviceidle whitelist +<package>` and `am set-standby-bucket <package> active`
 * via Shizuku's privileged process, exempting our app from battery optimization kills.
 *
 * ### Layer 2: Shell-UID Watchdog Daemon
 * Spawns a lightweight shell script that runs **inside Shizuku's process** under shell UID.
 * This daemon polls our app's process status every 5 seconds and forcefully revives
 * the AlarmAudioService if the app process is killed.
 *
 * ### Layer 3: Deep Doze Disable
 * Executes `dumpsys deviceidle disable` to aggressively disable Doze behavior.
 *
 * ## Architecture
 * Uses Shizuku's [UserService] pattern via [IShellService] AIDL. The [ShellService]
 * implementation is instantiated by Shizuku inside its own process (shell/root UID),
 * so any `Runtime.exec()` calls from it carry elevated privileges.
 *
 */
class ShizukuDaemonManager(private val context: Context) {

    companion object {
        private const val TAG = "ShizukuDaemonMgr"

        /** Check interval for the watchdog daemon in seconds. */
        private const val WATCHDOG_INTERVAL_SEC = 5

        // Shell command templates (format args: packageName)
        private const val CMD_DEVICEIDLE_WHITELIST = "cmd deviceidle whitelist +%s"
        private const val CMD_STANDBY_BUCKET = "am set-standby-bucket %s active"
        private const val CMD_DEVICEIDLE_DISABLE = "dumpsys deviceidle disable"
        private const val CMD_START_SERVICE = "am start-foreground-service %s/.service.AlarmAudioService"
    }

    // ── Shizuku Status Checks ────────────────────────────────────────────

    /** Check if Shizuku is installed and its binder is alive. */
    fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            Log.w(TAG, "Shizuku ping failed: ${e.message}")
            false
        }
    }

    /** Check if our app has been granted Shizuku permission by the user. */
    fun isShizukuPermitted(): Boolean {
        return try {
            Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    /** Request Shizuku permission — triggers the Shizuku permission dialog. */
    fun requestPermissionIfNeeded() {
        if (!isShizukuPermitted()) {
            try {
                Shizuku.requestPermission(0)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to request Shizuku permission", e)
            }
        }
    }

    // ── Anti-Killing Tweaks ──────────────────────────────────────────────

    /**
     * Apply all system-level anti-killing tweaks via Shizuku UserService.
     *
     * These commands require shell UID or higher — the UserService provides this
     * by running [ShellService] inside Shizuku's privileged process.
     */
    suspend fun applyAntiKillingTweaks() {
        val pkg = context.packageName
        Log.i(TAG, "Applying anti-killing tweaks for $pkg")

        // Fire-and-forget: each command takes effect immediately at system level
        executePrivileged(CMD_DEVICEIDLE_WHITELIST.format(pkg))
        executePrivileged(CMD_STANDBY_BUCKET.format(pkg))
        executePrivileged(CMD_DEVICEIDLE_DISABLE)

        Log.i(TAG, "Anti-killing tweaks applied")
    }

    // ── Watchdog Daemon ──────────────────────────────────────────────────

    /**
     * Start the shell-UID watchdog daemon via Shizuku UserService.
     *
     * The daemon runs as a background shell process owned by Shizuku's process
     * (shell UID), completely outside our app's process tree. If our app is
     * killed, the daemon detects it via `pidof` and revives `AlarmAudioService`.
     *
     * `nohup` + `&` ensures the daemon outlives the UserService call.
     */
    suspend fun startWatchdogDaemon() {
        val pkg = context.packageName
        val startCmd = CMD_START_SERVICE.format(pkg)

        val watchdogScript = buildString {
            append("while true; do ")
            append("if ! pidof $pkg > /dev/null 2>&1; then ")
            append("$startCmd; ")
            append("fi; ")
            append("sleep $WATCHDOG_INTERVAL_SEC; ")
            append("done")
        }

        val fullCommand = "nohup sh -c '$watchdogScript' > /dev/null 2>&1 &"

        Log.i(TAG, "Starting watchdog daemon for $pkg")
        executePrivileged(fullCommand)
    }

    /** Kill any existing watchdog daemon processes for our package. */
    suspend fun stopWatchdogDaemon() {
        val pkg = context.packageName
        Log.i(TAG, "Stopping watchdog daemon(s) for $pkg")
        executePrivileged("pkill -f 'pidof $pkg'")
    }

    // ── UserService Binding ──────────────────────────────────────────────

    /**
     * Execute a shell command with elevated privileges via Shizuku UserService.
     *
     * Binds to [ShellService] running in Shizuku's process, calls [IShellService.execute],
     * and unbinds immediately. Each invocation uses a fresh binding for simplicity
     * and to avoid keeping a long-lived connection.
     *
     * @param command shell command to execute with elevated privileges
     * @return command output, or null on failure
     */
    private suspend fun executePrivileged(command: String): String? =
        withContext(Dispatchers.Main) {
            Log.d(TAG, "executePrivileged: $command")

            val args = Shizuku.UserServiceArgs(
                ComponentName(context.packageName, ShellService::class.java.name)
            ).daemon(false).version(1).tag("shell_service")
              .processNameSuffix("shell")

            suspendCancellableCoroutine { cont ->
                try {
                    Shizuku.bindUserService(args, object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                            if (!cont.isActive) return
                            if (service == null) {
                                cont.resumeWithException(Exception("Null binder from Shizuku"))
                                return
                            }
                            try {
                                val data = Parcel.obtain()
                                val reply = Parcel.obtain()
                                try {
                                    data.writeInterfaceToken(ShellService.DESCRIPTOR)
                                    data.writeString(command)
                                    service.transact(1, data, reply, 0)
                                    reply.readException()
                                    cont.resume(reply.readString())
                                } finally {
                                    reply.recycle()
                                    data.recycle()
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "UserService transact error: ${e.message}", e)
                                cont.resumeWithException(e)
                            } finally {
                                try { Shizuku.unbindUserService(args, this, false) } catch (_: Exception) {}
                            }
                        }

                        override fun onServiceDisconnected(name: ComponentName?) {
                            if (cont.isActive)
                                cont.resumeWithException(Exception("Shizuku service disconnected"))
                        }
                    })
                } catch (e: Exception) {
                    Log.e(TAG, "Shizuku bindUserService failed: ${e.message}", e)
                    if (cont.isActive) cont.resumeWithException(e)
                }
            }
        }
}
