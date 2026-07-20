package com.example.silentalarm.daemon

import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Shizuku UserService that executes privileged shell commands.
 *
 * ## How It Works
 * Instantiated by Shizuku inside its own process (shell/root UID).
 * Any [Runtime.exec] calls from here carry Shizuku's elevated privileges.
 *
 * ## Communication Protocol (Binder-based, no AIDL)
 * - Transaction code `1` (EXECUTE): reads a String (command), executes it,
 *   writes the result String back.
 * - Uses Binder token for interface enforcement.
 *
 * ## Shizuku Requirements
 * - Must implement [IBinder] — achieved by extending [Binder].
 * - Must have a **default no-arg constructor** — Shizuku uses `Class.newInstance()`.
 * - May have a constructor taking [Context] (Shizuku v13+).
 */
class ShellService : Binder {

    companion object {
        private const val TAG = "ShellService"

        /** Binder interface descriptor — used for enforceInterface checks. */
        const val DESCRIPTOR = "com.example.silentalarm.daemon.IShellService"

        /** Transaction code: execute a shell command. */
        private const val TRANSACTION_EXECUTE = 1
    }

    constructor() : super()

    @Suppress("unused")
    constructor(context: Context) : super()

    /**
     * Handle incoming Binder transactions from the client.
     *
     * Supported codes:
     * - [TRANSACTION_EXECUTE]: read command String, execute it, write result String back.
     */
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        when (code) {
            TRANSACTION_EXECUTE -> {
                data.enforceInterface(DESCRIPTOR)
                val command: String = data.readString() ?: ""
                val result = execute(command)
                reply?.writeNoException()
                reply?.writeString(result)
                return true
            }
        }
        return super.onTransact(code, data, reply, flags)
    }

    /**
     * Execute a shell command with Shizuku's elevated privileges.
     *
     * Because this runs inside Shizuku's process, the shell inherits
     * root (UID 0) or shell (UID 2000) permissions, allowing commands
     * like `cmd deviceidle` and `am set-standby-bucket` to work.
     */
    private fun execute(command: String): String {
        Log.d(TAG, "Executing (Shizuku UserService): $command")

        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))

            val stdout = BufferedReader(InputStreamReader(process.inputStream)).readText()
            val stderr = BufferedReader(InputStreamReader(process.errorStream)).readText()

            val exitCode = process.waitFor()

            if (stderr.isNotBlank()) {
                Log.w(TAG, "stderr($exitCode): $stderr")
            }
            if (stdout.isNotBlank()) {
                Log.d(TAG, "stdout($exitCode): $stdout")
            }

            if (exitCode != 0) {
                Log.w(TAG, "Command exited $exitCode: $command")
            }

            stdout.ifBlank { stderr }
        } catch (e: Exception) {
            Log.e(TAG, "Command failed: ${e.message}", e)
            ""
        }
    }
}
