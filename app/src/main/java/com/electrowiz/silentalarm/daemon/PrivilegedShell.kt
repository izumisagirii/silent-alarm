package com.electrowiz.silentalarm.daemon

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Outcome of one privileged shell invocation.
 *
 * [exitCode] is the process exit code, or -1 when the command could be started
 * but failed before producing a real exit status. [output] is the combined
 * stdout/stderr text (may be blank).
 */
data class ShellResult(
    val exitCode: Int,
    val output: String
) {
    val isSuccess: Boolean get() = exitCode == 0
}

/**
 * Executes a single privileged shell command.
 *
 * The concrete backend is chosen by [ShellManager]:
 * - [SuShell] — plain `su` root shell, no framework dependency (default);
 * - [ShizukuShell] — Shizuku UserService, used when root is unavailable.
 *
 * This layer is intentionally narrow: one-shot commands only (whitelist,
 * watchdog start/stop). It never holds process-keep-alive state itself.
 */
interface PrivilegedShell {
    suspend fun isAvailable(): Boolean
    suspend fun isPermitted(): Boolean

    /**
     * Executes [command] and returns its real exit code/output, or null when
     * the command could not be dispatched at all (no shell, timeout, binder
     * transport failure).
     */
    suspend fun execute(command: String): ShellResult?

    /** Non-blocking UI hook for backends that need user consent. */
    fun requestPermissionIfNeeded() {}
}

/**
 * Root shell backend: runs `su -c` directly in the app process.
 *
 * Availability is probed once per process and cached; probing spawns a short
 * `su` process, so callers should touch it from a background dispatcher.
 */
class SuShell internal constructor() : PrivilegedShell {
    companion object {
        private const val TAG = "SuShell"
        private const val COMMAND_TIMEOUT_MS = 10_000L
        private const val PROBE_TIMEOUT_MS = 3_000L
    }

    @Volatile
    private var probed = false

    @Volatile
    private var available = false

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        if (!probed) {
            available = probe()
            probed = true
        }
        available
    }

    override suspend fun isPermitted(): Boolean = isAvailable()

    override suspend fun execute(command: String): ShellResult? = withContext(Dispatchers.IO) {
        val process = try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", command))
        } catch (e: IOException) {
            Log.w(TAG, "su unavailable: ${e.message}")
            return@withContext null
        }

        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val stdoutThread = process.inputStream.drain("SuShell-stdout", stdout)
        val stderrThread = process.errorStream.drain("SuShell-stderr", stderr)

        try {
            val finished = process.waitFor(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                process.waitFor(1, TimeUnit.SECONDS)
                Log.w(TAG, "su command timed out: $command")
                return@withContext null
            }
            stdoutThread.join(1_000)
            stderrThread.join(1_000)
            val exitCode = process.exitValue()
            if (exitCode != 0) {
                Log.w(TAG, "su command exited $exitCode: $command")
            }
            ShellResult(
                exitCode = exitCode,
                output = listOf(stdout.toString(), stderr.toString())
                    .filter { it.isNotBlank() }
                    .joinToString("\n")
            )
        } catch (e: Exception) {
            Log.w(TAG, "su command failed: ${e.message}", e)
            null
        } finally {
            if (process.isAlive) process.destroy()
        }
    }

    private fun probe(): Boolean {
        val process = try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
        } catch (e: IOException) {
            return false
        }
        return try {
            val finished = process.waitFor(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                return false
            }
            process.exitValue() == 0
        } catch (e: Exception) {
            false
        } finally {
            if (process.isAlive) process.destroy()
        }
    }

    private fun java.io.InputStream.drain(name: String, sink: StringBuilder): Thread =
        kotlin.concurrent.thread(isDaemon = true, name = name) {
            bufferedReader().use { reader ->
                reader.forEachLine { sink.appendLine(it) }
            }
        }
}

/**
 * Shizuku backend: binds the app's [ShellService] UserService and executes one
 * shell command per transaction. Used when no root shell is available.
 */
class ShizukuShell internal constructor(context: Context) : PrivilegedShell {
    companion object {
        private const val TAG = "ShizukuShell"
        private const val EXECUTE_TIMEOUT_MS = 15_000L
        private const val SHELL_SERVICE_TAG = "shell_service"
    }

    private val appContext: Context = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Binder transactions are synchronous and the remote [ShellService] can
     * block for the command timeout. Run them away from the main dispatcher
     * so a slow `cmd deviceidle`/`am` call cannot freeze the UI.
     */
    private val transactScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private val shellServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(appContext.packageName, ShellService::class.java.name)
    ).daemon(false).version(2).tag(SHELL_SERVICE_TAG).processNameSuffix("shell")

    /** Serializes bind/transact/unbind sequences. */
    private val commandMutex = Mutex()

    override suspend fun isAvailable(): Boolean =
        try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            Log.w(TAG, "Shizuku ping failed: ${e.message}")
            false
        }

    override suspend fun isPermitted(): Boolean =
        try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }

    override fun requestPermissionIfNeeded() {
        val permitted = try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
        if (permitted) return
        try {
            Shizuku.requestPermission(0)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request Shizuku permission", e)
        }
    }

    override suspend fun execute(command: String): ShellResult? =
        commandMutex.withLock {
            withContext(Dispatchers.Main) {
                val result = withTimeoutOrNull(EXECUTE_TIMEOUT_MS) {
                    bindAndTransact(command)
                }
                if (result == null) {
                    throw IOException(
                        "Shizuku UserService transaction timed out after ${EXECUTE_TIMEOUT_MS}ms"
                    )
                }
                result
            }
        }

    private suspend fun bindAndTransact(command: String): ShellResult? =
        suspendCancellableCoroutine { cont ->
            val settled = AtomicBoolean(false)
            var connection: ServiceConnection? = null

            fun cleanup(remove: Boolean) {
                val conn = connection ?: return
                connection = null
                runCatching {
                    Shizuku.unbindUserService(shellServiceArgs, conn, remove)
                }
            }

            fun complete(value: ShellResult?, error: Throwable?) {
                if (!settled.compareAndSet(false, true)) return
                mainHandler.post {
                    cleanup(remove = error != null)
                    if (cont.isActive) {
                        if (error != null) cont.resumeWithException(error) else cont.resume(value)
                    }
                }
            }

            fun abort() {
                if (!settled.compareAndSet(false, true)) return
                mainHandler.post { cleanup(remove = true) }
            }

            connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    if (settled.get()) return
                    if (service == null) {
                        complete(null, IOException("Null binder from Shizuku"))
                        return
                    }

                    // The actual synchronous transact runs on a private IO
                    // dispatcher; completion is then marshalled back to main.
                    transactScope.launch {
                        var value: ShellResult? = null
                        var error: Throwable? = null
                        try {
                            value = transact(service, command)
                        } catch (e: Exception) {
                            Log.e(TAG, "UserService transact error: ${e.message}", e)
                            error = e
                        }
                        complete(value, error)
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    complete(null, IOException("Shizuku service disconnected"))
                }
            }

            cont.invokeOnCancellation { abort() }

            try {
                Shizuku.bindUserService(shellServiceArgs, connection!!)
            } catch (e: Exception) {
                Log.e(TAG, "Shizuku bindUserService failed: ${e.message}", e)
                complete(null, e)
            }
        }

    private fun transact(binder: IBinder, command: String): ShellResult {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ShellService.DESCRIPTOR)
            data.writeString(command)
            binder.transact(ShellService.TRANSACTION_EXECUTE, data, reply, 0)
            reply.readException()
            return ShellResult(
                exitCode = reply.readInt(),
                output = reply.readString().orEmpty()
            )
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}

/**
 * Chooses and caches the active privileged backend, and applies the one-shot
 * anti-kill whitelist commands. Process-wide singleton.
 */
class ShellManager private constructor(context: Context) {

    enum class ShellStatus { NONE, SU_READY, SHIZUKU_READY, SHIZUKU_NEEDS_PERMISSION }

    companion object {
        private const val TAG = "ShellManager"
        private const val CMD_DEVICEIDLE_WHITELIST = "cmd deviceidle whitelist +%s"
        private const val CMD_STANDBY_BUCKET = "am set-standby-bucket %s active"

        @Volatile
        private var instance: ShellManager? = null

        fun get(context: Context): ShellManager =
            instance ?: synchronized(this) {
                instance ?: ShellManager(context.applicationContext).also { instance = it }
            }
    }

    private val appContext: Context = context.applicationContext
    private val suShell = SuShell()
    private val shizukuShell = ShizukuShell(appContext)
    private val statusMutex = Mutex()

    @Volatile
    private var cachedStatus: ShellStatus? = null

    fun statusNow(): ShellStatus = cachedStatus ?: ShellStatus.NONE

    /**
     * Re-detect the backend. Prefers the root shell (no framework dependency),
     * falling back to Shizuku when root is absent.
     */
    suspend fun refresh(): ShellStatus = statusMutex.withLock {
        val status = if (suShell.isAvailable()) {
            ShellStatus.SU_READY
        } else if (shizukuShell.isAvailable()) {
            if (shizukuShell.isPermitted()) {
                ShellStatus.SHIZUKU_READY
            } else {
                ShellStatus.SHIZUKU_NEEDS_PERMISSION
            }
        } else {
            ShellStatus.NONE
        }
        cachedStatus = status
        status
    }

    /** The backend usable right now, or null when none is available/permitted. */
    suspend fun current(): PrivilegedShell? {
        val status = cachedStatus ?: refresh()
        return when (status) {
            ShellStatus.SU_READY -> suShell
            ShellStatus.SHIZUKU_READY -> shizukuShell
            else -> null
        }
    }

    suspend fun applyAntiKillTweaks(): Boolean {
        val shell = current() ?: return false
        val pkg = appContext.packageName
        Log.i(TAG, "Applying anti-kill tweaks for $pkg")

        val commands = listOf(
            CMD_DEVICEIDLE_WHITELIST.format(pkg) to "deviceidle whitelist",
            CMD_STANDBY_BUCKET.format(pkg) to "standby bucket"
        )

        var allSucceeded = true
        for ((command, label) in commands) {
            val result = try {
                shell.execute(command)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "$label command failed with exception", e)
                allSucceeded = false
                continue
            }
            if (result == null) {
                Log.e(TAG, "$label command could not be executed")
                allSucceeded = false
            } else if (!result.isSuccess) {
                Log.e(
                    TAG,
                    "$label command failed with exit ${result.exitCode}: ${result.output}"
                )
                allSucceeded = false
            } else {
                Log.i(TAG, "$label applied successfully")
            }
        }
        return allSucceeded
    }

    fun requestPermissionIfNeeded() {
        if (statusNow() == ShellStatus.SHIZUKU_NEEDS_PERMISSION) {
            shizukuShell.requestPermissionIfNeeded()
        }
    }
}
