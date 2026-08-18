package com.electrowiz.silentalarm.util

import android.app.AlarmManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Lightweight, local-only diagnostic event log for alarm delivery troubleshooting. */
object AlarmDiagnostics {
    private const val RETENTION_MS = 31L * 24 * 60 * 60 * 1000
    private const val MAX_EVENT_LINES = 2_000
    private const val LOG_DIRECTORY = "diagnostics"
    private const val LOG_FILE_NAME = "alarm-events.jsonl"

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SilentAlarm-Diagnostics").apply { isDaemon = true }
    }
    private val eventSequence = AtomicLong(0)
    private val fileLock = Any()
    private var lastPruneAt = 0L

    fun log(context: Context, event: String, fields: Map<String, Any?> = emptyMap()) {
        val appContext = context.applicationContext
        val timestamp = System.currentTimeMillis()
        val sequence = eventSequence.incrementAndGet()
        executor.execute {
            runCatching {
                synchronized(fileLock) {
                    val logFile = logFile(appContext)
                    logFile.parentFile?.mkdirs()
                    logFile.appendText(buildEvent(appContext, timestamp, sequence, event, fields) + "\n")
                    if (timestamp - lastPruneAt >= 24 * 60 * 60 * 1000L) {
                        pruneLocked(logFile, timestamp)
                        lastPruneAt = timestamp
                    }
                }
            }
        }
    }

    /** Returns complete export text; no temporary export file is created. */
    suspend fun export(context: Context): String = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        executor.submit<String> {
            synchronized(fileLock) {
                val now = System.currentTimeMillis()
                val logFile = logFile(appContext)
                logFile.parentFile?.mkdirs()
                pruneLocked(logFile, now)
                lastPruneAt = now

                val events = if (logFile.exists()) logFile.readText() else ""
                buildExportHeader(appContext, now, events.lineSequence().count { it.isNotBlank() }) +
                    "\n" + events
            }
        }.get()
    }

    fun shortAlarmId(alarmId: String?): String = when {
        alarmId.isNullOrBlank() -> "test"
        alarmId.length <= 8 -> alarmId
        else -> alarmId.take(8)
    }

    private fun logFile(context: Context): File =
        File(File(context.filesDir, LOG_DIRECTORY), LOG_FILE_NAME)

    private fun buildEvent(
        context: Context,
        timestamp: Long,
        sequence: Long,
        event: String,
        fields: Map<String, Any?>
    ): String {
        val root = JSONObject()
            .put("event_sequence", sequence)
            .put("timestamp_ms", timestamp)
            .put("timestamp", isoTimestamp(timestamp))
            .put("uptime_ms", SystemClock.elapsedRealtime())
            .put("process_pid", Process.myPid())
            .put("event", event)
            .put("permissions", permissionSnapshot(context))
        fields.forEach { (key, value) -> root.put(key, value ?: JSONObject.NULL) }
        return root.toString()
    }

    private fun buildExportHeader(context: Context, timestamp: Long, eventCount: Int): String =
        JSONObject()
            .put("format", "silent-alarm-diagnostics")
            .put("format_version", 2)
            .put("exported_at_ms", timestamp)
            .put("exported_at", isoTimestamp(timestamp))
            .put("retention_days", 31)
            .put("event_count", eventCount)
            .put("device", deviceSnapshot(context))
            .put("permissions_at_export", permissionSnapshot(context))
            .toString()

    private fun deviceSnapshot(context: Context): JSONObject {
        val packageInfo = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        return JSONObject()
            .put("manufacturer", Build.MANUFACTURER)
            .put("brand", Build.BRAND)
            .put("model", Build.MODEL)
            .put("device", Build.DEVICE)
            .put("product", Build.PRODUCT)
            .put("android_version", Build.VERSION.RELEASE ?: "unknown")
            .put("api_level", Build.VERSION.SDK_INT)
            .put(
                "security_patch",
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Build.VERSION.SECURITY_PATCH else "unknown"
            )
            .put("app_version", packageInfo?.versionName ?: "unknown")
            .put("app_version_code", packageInfo?.longVersionCode ?: -1L)
            .put("locale", Locale.getDefault().toLanguageTag())
            .put("timezone", java.util.TimeZone.getDefault().id)
    }

    private fun permissionSnapshot(context: Context): JSONObject {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            runCatching { alarmManager.canScheduleExactAlarms() }.getOrDefault(false)
        return JSONObject()
            .put("exact_alarm", exactAllowed)
            .put("battery_optimization_ignored", runCatching {
                powerManager.isIgnoringBatteryOptimizations(context.packageName)
            }.getOrDefault(false))
            .put("notifications_enabled", NotificationManagerCompat.from(context).areNotificationsEnabled())
            .put("power_save_mode", powerManager.isPowerSaveMode)
            .put("device_idle_mode", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                powerManager.isDeviceIdleMode
            } else {
                false
            })
    }

    private fun pruneLocked(logFile: File, now: Long) {
        if (!logFile.exists()) return
        val cutoff = now - RETENTION_MS
        val kept = logFile.readLines().filter { line ->
            runCatching {
                JSONObject(line).optLong("timestamp_ms", Long.MAX_VALUE) >= cutoff
            }.getOrDefault(true)
        }.takeLast(MAX_EVENT_LINES)
        logFile.writeText(if (kept.isEmpty()) "" else kept.joinToString("\n") + "\n")
    }

    private fun isoTimestamp(timestamp: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date(timestamp))
}
