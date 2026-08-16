package com.electrowiz.silentalarm.data

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** DataStore singleton — one instance per process. */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "alarm_settings")

/**
 * Defines what the alarm does when no earphones are detected.
 * Shared across all alarms as a global preference.
 */
enum class NoEarphoneAction {
    VIBRATE_ONLY,
    LOUDSPEAKER;

    companion object {
        fun fromOrdinal(ordinal: Int): NoEarphoneAction =
            entries.getOrElse(ordinal) { VIBRATE_ONLY }
    }
}

/**
 * What happens when the earphone alarm timeout expires.
 */
enum class TimeoutAction {
    STOP,
    FALLBACK;

    companion object {
        fun fromOrdinal(ordinal: Int): TimeoutAction =
            entries.getOrElse(ordinal) { STOP }
    }
}

/**
 * A single alarm entity. Persisted as JSON in DataStore.
 *
 * @property id unique identifier (UUID string), stable across edits
 * @property hour alarm hour (0–23, 24h format)
 * @property minute alarm minute (0–59)
 * @property enabled whether this alarm is actively scheduled
 * @property label user-visible name (e.g. "Morning Meds")
 * @property daysOfWeek which days of the week this alarm fires;
 *             empty set means "one-shot" (fires once, next occurrence)
 * @property timeZoneId timezone captured when the alarm was last saved/edited;
 *             blank means legacy data and is treated as the system timezone.
 */
data class AlarmItem(
    val id: String = UUID.randomUUID().toString(),
    val hour: Int = 8,
    val minute: Int = 0,
    val enabled: Boolean = true,
    val label: String = "",
    val daysOfWeek: Set<Int> = emptySet(), // Calendar.SUNDAY=1 … Calendar.SATURDAY=7
    val timeZoneId: String = ""
)

/**
 * Immutable snapshot of every global alarm setting.
 *
 * The alarm service reads this once per ring session instead of issuing
 * several sequential DataStore reads, which keeps trigger latency low.
 */
data class AlarmSettings(
    val earphoneVolume: Int,
    val speakerVolume: Int,
    val noEarphoneAction: NoEarphoneAction,
    val globalRingtoneUri: String,
    val timeoutSeconds: Int,
    val timeoutAction: TimeoutAction
)

/**
 * Type-safe DataStore wrapper for all alarm preferences.
 *
 * Alarms are stored as a JSON array under a single string key, avoiding
 * the explosion of per-alarm keys that would come with a flat key-value model.
 * Global settings (volumes, no-earphone action) remain as individual keys.
 */
class AlarmPreferences(private val context: Context) {

    companion object {
        /**
         * Bump this only when the persisted shape changes incompatibly.
         * Alarm/timezone data is cleared when the stored schema is older.
         */
        private const val SCHEMA_VERSION = 1

        /** Default earphone playback volume, 0–100. */
        const val DEFAULT_EARPHONE_VOLUME = 80

        /** Default speaker playback volume, 0–100. */
        const val DEFAULT_SPEAKER_VOLUME = 60

        /** Default alarm timeout in seconds (5 min). */
        const val DEFAULT_TIMEOUT_SECONDS = 300
    }

    /** Single DataStore flow shared by every reader (each access is one emission). */
    private val data: Flow<Preferences> = context.dataStore.data

    // ── Preference Keys ──────────────────────────────────────────────────
    private object Keys {
        val SCHEMA_VERSION = intPreferencesKey("schema_version")
        val ALARMS_JSON = stringPreferencesKey("alarms_json")
        val EARPHONE_VOLUME = intPreferencesKey("earphone_volume")
        val SPEAKER_VOLUME = intPreferencesKey("speaker_volume")
        val NO_EARPHONE_ACTION = intPreferencesKey("no_earphone_action")
        val GLOBAL_RINGTONE_URI = stringPreferencesKey("global_ringtone_uri")
        val TIMEOUT_SECONDS = intPreferencesKey("timeout_seconds")
        val TIMEOUT_ACTION = intPreferencesKey("timeout_action")
        val KEEP_ALIVE_ENABLED = booleanPreferencesKey("keep_alive_enabled")
        val PRIVILEGED_ENABLED = booleanPreferencesKey("privileged_enabled")
        val RESUME_ALARM_ID = stringPreferencesKey("resume_alarm_id")
    }

    // ── Global Settings (shared across all alarms) ───────────────────────

    /** Earphone volume 0–100. Default: 80. */
    val earphoneVolume: Flow<Int> = data.map { p ->
        p[Keys.EARPHONE_VOLUME] ?: DEFAULT_EARPHONE_VOLUME
    }

    /** Speaker volume 0–100. Default: 60. */
    val speakerVolume: Flow<Int> = data.map { p ->
        p[Keys.SPEAKER_VOLUME] ?: DEFAULT_SPEAKER_VOLUME
    }

    /** What to do when no earphones are connected. Default: VIBRATE_ONLY. */
    val noEarphoneAction: Flow<NoEarphoneAction> = data.map { p ->
        NoEarphoneAction.fromOrdinal(p[Keys.NO_EARPHONE_ACTION] ?: 0)
    }

    /** Global ringtone URI (applies to all alarms). Empty = system default. */
    val globalRingtoneUri: Flow<String> = data.map { p ->
        p[Keys.GLOBAL_RINGTONE_URI] ?: ""
    }

    /** Earphone alarm timeout in seconds. Range 30–1800, default 300 (5 min). */
    val timeoutSeconds: Flow<Int> = data.map { p ->
        p[Keys.TIMEOUT_SECONDS] ?: DEFAULT_TIMEOUT_SECONDS
    }

    /** Action to take after the earphone timeout expires. Default: STOP. */
    val timeoutAction: Flow<TimeoutAction> = data.map { p ->
        TimeoutAction.fromOrdinal(p[Keys.TIMEOUT_ACTION] ?: 0)
    }

    /**
     * Whether the idle notification keep-alive (foreground service + recovery
     * alarm + watchdog) is enabled. Default: off — alarms still fire from
     * AlarmManager even with the keep-alive layer disabled.
     */
    val keepAliveEnabled: Flow<Boolean> = data.map { p ->
        p[Keys.KEEP_ALIVE_ENABLED] ?: false
    }

    /**
     * Whether privileged anti-kill (deviceidle whitelist / standby bucket,
     * executed through Root shell or Shizuku) is enabled. Default: off.
     */
    val privilegedEnabled: Flow<Boolean> = data.map { p ->
        p[Keys.PRIVILEGED_ENABLED] ?: false
    }

    /** One-shot snapshot of all global settings (a single DataStore read). */
    suspend fun snapshot(): AlarmSettings = data.first().let { p ->
        AlarmSettings(
            earphoneVolume = p[Keys.EARPHONE_VOLUME] ?: DEFAULT_EARPHONE_VOLUME,
            speakerVolume = p[Keys.SPEAKER_VOLUME] ?: DEFAULT_SPEAKER_VOLUME,
            noEarphoneAction = NoEarphoneAction.fromOrdinal(p[Keys.NO_EARPHONE_ACTION] ?: 0),
            globalRingtoneUri = p[Keys.GLOBAL_RINGTONE_URI] ?: "",
            timeoutSeconds = p[Keys.TIMEOUT_SECONDS] ?: DEFAULT_TIMEOUT_SECONDS,
            timeoutAction = TimeoutAction.fromOrdinal(p[Keys.TIMEOUT_ACTION] ?: 0)
        )
    }

    // ── Alarm List (JSON-backed) ─────────────────────────────────────────

    /**
     * Reactive stream of all alarms, sorted by (hour, minute).
     * Returns empty list if no alarms have been saved yet.
     */
    fun getAlarms(): Flow<List<AlarmItem>> = data.map { prefs ->
        val json = prefs[Keys.ALARMS_JSON] ?: "[]"
        parseAlarms(json).sortedWith(compareBy({ it.hour }, { it.minute }))
    }

    /**
     * Remove legacy or corrupt alarm data before it reaches the scheduler.
     * Existing preferences are otherwise left intact.
     */
    suspend fun migrateOrReset() {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.SCHEMA_VERSION] ?: 0
            if (current >= SCHEMA_VERSION) return@edit

            // Older builds did not have a schema version and their alarm/timezone
            // payloads may be incompatible with the current editor. Reset those
            // keys on first migration; global volumes/ringtone are preserved.
            prefs.remove(Keys.ALARMS_JSON)
            prefs.remove(Keys.RESUME_ALARM_ID)
            prefs[Keys.SCHEMA_VERSION] = SCHEMA_VERSION
        }
    }

    /**
     * Persist a new alarm. Generates a UUID if the item's id is blank.
     * The updated list is written back to DataStore atomically.
     */
    suspend fun addAlarm(item: AlarmItem) {
        context.dataStore.edit { prefs ->
            val alarms = parseAlarms(prefs[Keys.ALARMS_JSON] ?: "[]").toMutableList()
            val toAdd = if (item.id.isBlank()) item.copy(id = UUID.randomUUID().toString()) else item
            alarms.add(toAdd)
            prefs[Keys.ALARMS_JSON] = serializeAlarms(alarms)
        }
    }

    /**
     * Replace an existing alarm (matched by [AlarmItem.id]) with [updated].
     * If no alarm with that id exists this is a no-op.
     */
    suspend fun updateAlarm(updated: AlarmItem) {
        context.dataStore.edit { prefs ->
            val alarms = parseAlarms(prefs[Keys.ALARMS_JSON] ?: "[]").toMutableList()
            val idx = alarms.indexOfFirst { it.id == updated.id }
            if (idx >= 0) {
                alarms[idx] = updated
                prefs[Keys.ALARMS_JSON] = serializeAlarms(alarms)
            }
        }
    }

    /**
     * Delete the alarm identified by [alarmId]. No-op if not found.
     */
    suspend fun deleteAlarm(alarmId: String) {
        context.dataStore.edit { prefs ->
            val alarms = parseAlarms(prefs[Keys.ALARMS_JSON] ?: "[]")
            prefs[Keys.ALARMS_JSON] = serializeAlarms(alarms.filter { it.id != alarmId })
        }
    }

    /**
     * Enable or disable a single alarm without touching other fields.
     */
    suspend fun toggleAlarm(alarmId: String, enabled: Boolean) {
        context.dataStore.edit { prefs ->
            val alarms = parseAlarms(prefs[Keys.ALARMS_JSON] ?: "[]").map {
                if (it.id == alarmId) it.copy(enabled = enabled) else it
            }
            prefs[Keys.ALARMS_JSON] = serializeAlarms(alarms)
        }
    }

    /**
     * Enable or disable all alarms in a single atomic write.
     * Used by the QS tile master switch.
     */
    suspend fun setAllEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            val alarms = parseAlarms(prefs[Keys.ALARMS_JSON] ?: "[]").map {
                it.copy(enabled = enabled)
            }
            prefs[Keys.ALARMS_JSON] = serializeAlarms(alarms)
        }
    }

    // ── Global Setting Writers ───────────────────────────────────────────

    suspend fun setEarphoneVolume(volume: Int) {
        context.dataStore.edit { prefs ->
            prefs[Keys.EARPHONE_VOLUME] = volume.coerceIn(0, 100)
        }
    }

    suspend fun setSpeakerVolume(volume: Int) {
        context.dataStore.edit { prefs ->
            prefs[Keys.SPEAKER_VOLUME] = volume.coerceIn(0, 100)
        }
    }

    suspend fun setNoEarphoneAction(action: NoEarphoneAction) {
        context.dataStore.edit { prefs ->
            prefs[Keys.NO_EARPHONE_ACTION] = action.ordinal
        }
    }

    /**
     * Persist a global ringtone URI with read permission so MediaPlayer
     * can read it after reboot. Applies to all alarms.
     */
    suspend fun setGlobalRingtoneUri(uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {
            // Some content URIs can't grant a persistable permission; the
            // in-session read still works, it just won't survive a reboot.
        }
        context.dataStore.edit { prefs ->
            prefs[Keys.GLOBAL_RINGTONE_URI] = uri.toString()
        }
    }

    suspend fun setTimeoutSeconds(seconds: Int) {
        context.dataStore.edit { prefs ->
            prefs[Keys.TIMEOUT_SECONDS] = seconds.coerceIn(30, 1800)
        }
    }

    suspend fun setTimeoutAction(action: TimeoutAction) {
        context.dataStore.edit { prefs ->
            prefs[Keys.TIMEOUT_ACTION] = action.ordinal
        }
    }

    suspend fun setKeepAliveEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.KEEP_ALIVE_ENABLED] = enabled
        }
    }

    suspend fun setPrivilegedEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.PRIVILEGED_ENABLED] = enabled
        }
    }

    // ── Interrupted-ring recovery ────────────────────────────────────────

    /** Alarm id that should resume ringing after the service process restarts. */
    suspend fun pendingResumeAlarmId(): String? =
        data.first()[Keys.RESUME_ALARM_ID]

    /**
     * Persist/clear the alarm session that must survive process death while
     * ringing. Snooze uses AlarmManager for the same purpose, so the key is
     * cleared as soon as the session enters snooze or idle.
     */
    suspend fun setResumeAlarmId(alarmId: String?) {
        context.dataStore.edit { prefs ->
            if (alarmId.isNullOrBlank()) {
                prefs.remove(Keys.RESUME_ALARM_ID)
            } else {
                prefs[Keys.RESUME_ALARM_ID] = alarmId
            }
        }
    }

    suspend fun clearResumeAlarmId() = setResumeAlarmId(null)

    // ── JSON Helpers ─────────────────────────────────────────────────────

    private fun parseAlarms(json: String): List<AlarmItem> {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { index ->
                runCatching {
                    val obj = arr.optJSONObject(index) ?: return@runCatching null
                    val id = obj.optString("id", "").takeIf { it.isNotBlank() }
                        ?: return@runCatching null
                    AlarmItem(
                        id = id,
                        hour = obj.optInt("hour", 8).coerceIn(0, 23),
                        minute = obj.optInt("minute", 0).coerceIn(0, 59),
                        enabled = obj.optBoolean("enabled", true),
                        label = obj.optString("label", ""),
                        daysOfWeek = jsonArrayToIntSet(obj.optJSONArray("daysOfWeek"))
                            .filterTo(mutableSetOf()) { it in 1..7 },
                        timeZoneId = obj.optString("timeZoneId", "")
                    )
                }.getOrNull()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun serializeAlarms(alarms: List<AlarmItem>): String {
        val arr = JSONArray()
        alarms.forEach { item ->
            arr.put(JSONObject().apply {
                put("id", item.id)
                put("hour", item.hour)
                put("minute", item.minute)
                put("enabled", item.enabled)
                put("label", item.label)
                put("daysOfWeek", JSONArray(item.daysOfWeek))
                put("timeZoneId", item.timeZoneId)
            })
        }
        return arr.toString()
    }

    private fun jsonArrayToIntSet(arr: JSONArray?): Set<Int> {
        if (arr == null) return emptySet()
        return (0 until arr.length()).mapNotNull { arr.optInt(it, -1) }.filter { it >= 0 }.toSet()
    }
}
