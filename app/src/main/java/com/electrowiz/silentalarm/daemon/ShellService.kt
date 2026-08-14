package com.electrowiz.silentalarm.daemon

import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

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
        const val DESCRIPTOR = "com.electrowiz.silentalarm.daemon.IShellService"

        /** Transaction code: execute a shell command. */
        const val TRANSACTION_EXECUTE = 1

        /**
         * Transaction sent by Shizuku when [rikka.shizuku.Shizuku.unbindUserService]
         * is called with `remove = true`.
         */
        private const val TRANSACTION_DESTROY = 16777115

        private const val COMMAND_TIMEOUT_MS = 30_000L
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
            TRANSACTION_DESTROY -> {
                reply?.writeNoException()
                Log.i(TAG, "Shizuku requested UserService destruction")
                System.exit(0)
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

        val process = try {
            Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
        } catch (e: IOException) {
            Log.e(TAG, "Failed to start shell", e)
            return ""
        }

        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val stdoutThread = process.inputStream.drain("ShellService-stdout", stdout)
        val stderrThread = process.errorStream.drain("ShellService-stderr", stderr)

        return try {
            val finished = process.waitFor(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                process.waitFor(1, TimeUnit.SECONDS)
                Log.w(TAG, "Command timed out after ${COMMAND_TIMEOUT_MS}ms: $command")
                return ""
            }

            stdoutThread.join(1_000)
            stderrThread.join(1_000)
            val exitCode = process.waitFor()

            val stderrText = stderr.toString()
            val stdoutText = stdout.toString()

            if (stderrText.isNotBlank()) {
                Log.w(TAG, "stderr($exitCode): $stderrText")
            }
            if (stdoutText.isNotBlank()) {
                Log.d(TAG, "stdout($exitCode): $stdoutText")
            }

            if (exitCode != 0) {
                Log.w(TAG, "Command exited $exitCode: $command")
            }

            stdoutText.ifBlank { stderrText }
        } catch (e: Exception) {
            Log.e(TAG, "Command failed: ${e.message}", e)
            ""
        } finally {
            if (process.isAlive) process.destroy()
        }
    }

    private fun InputStream.drain(name: String, sink: StringBuilder): Thread =
        thread(isDaemon = true, name = name) {
            bufferedReader().use { reader ->
                reader.forEachLine { sink.appendLine(it) }
            }
        }
}
