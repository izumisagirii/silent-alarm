package com.electrowiz.silentalarm.data

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
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
enum class NoEarphoneAction(val displayName: String) {
    VIBRATE_ONLY("Vibrate Only"),
    LOUDSPEAKER("Loudspeaker");

    companion object {
        fun fromOrdinal(ordinal: Int): NoEarphoneAction =
            entries.getOrElse(ordinal) { VIBRATE_ONLY }
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
 */
data class AlarmItem(
    val id: String = UUID.randomUUID().toString(),
    val hour: Int = 8,
    val minute: Int = 0,
    val enabled: Boolean = true,
    val label: String = "",
    val daysOfWeek: Set<Int> = emptySet() // Calendar.SUNDAY=1 … Calendar.SATURDAY=7
)

/**
 * Type-safe DataStore wrapper for all alarm preferences.
 *
 * Alarms are stored as a JSON array under a single string key, avoiding
 * the explosion of per-alarm keys that would come with a flat key-value model.
 * Global settings (volumes, no-earphone action) remain as individual keys.
 */
class AlarmPreferences(private val context: Context) {

    // ── Preference Keys ──────────────────────────────────────────────────
    private object Keys {
        val ALARMS_JSON = stringPreferencesKey("alarms_json")
        val EARPHONE_VOLUME = intPreferencesKey("earphone_volume")
        val SPEAKER_VOLUME = intPreferencesKey("speaker_volume")
        val NO_EARPHONE_ACTION = intPreferencesKey("no_earphone_action")
        val GLOBAL_RINGTONE_URI = stringPreferencesKey("global_ringtone_uri")
    }

    // ── Global Settings (shared across all alarms) ───────────────────────

    /** Earphone volume 0–100. Default: 80. */
    val earphoneVolume: Flow<Int> = context.dataStore.data.map { p ->
        p[Keys.EARPHONE_VOLUME] ?: 80
    }

    /** Speaker volume 0–100. Default: 60. */
    val speakerVolume: Flow<Int> = context.dataStore.data.map { p ->
        p[Keys.SPEAKER_VOLUME] ?: 60
    }

    /** What to do when no earphones are connected. Default: VIBRATE_ONLY. */
    val noEarphoneAction: Flow<NoEarphoneAction> = context.dataStore.data.map { p ->
        NoEarphoneAction.fromOrdinal(p[Keys.NO_EARPHONE_ACTION] ?: 0)
    }

    /** Global ringtone URI (applies to all alarms). Empty = system default. */
    val globalRingtoneUri: Flow<String> = context.dataStore.data.map { p ->
        p[Keys.GLOBAL_RINGTONE_URI] ?: ""
    }

    // ── Alarm List (JSON-backed) ─────────────────────────────────────────

    /**
     * Reactive stream of all alarms, sorted by (hour, minute).
     * Returns empty list if no alarms have been saved yet.
     */
    fun getAlarms(): Flow<List<AlarmItem>> = context.dataStore.data.map { prefs ->
        val json = prefs[Keys.ALARMS_JSON] ?: "[]"
        parseAlarms(json).sortedWith(compareBy({ it.hour }, { it.minute }))
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
        } catch (_: SecurityException) {
            // Some content URIs don't require persistable permission
        }
        context.dataStore.edit { prefs ->
            prefs[Keys.GLOBAL_RINGTONE_URI] = uri.toString()
        }
    }

    // ── JSON Helpers ─────────────────────────────────────────────────────

    private fun parseAlarms(json: String): List<AlarmItem> {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                AlarmItem(
                    id = obj.optString("id", UUID.randomUUID().toString()),
                    hour = obj.optInt("hour", 8),
                    minute = obj.optInt("minute", 0),
                    enabled = obj.optBoolean("enabled", true),
                    label = obj.optString("label", ""),
                    daysOfWeek = jsonArrayToIntSet(obj.optJSONArray("daysOfWeek"))
                )
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
            })
        }
        return arr.toString()
    }

    private fun jsonArrayToIntSet(arr: JSONArray?): Set<Int> {
        if (arr == null) return emptySet()
        return (0 until arr.length()).mapNotNull { arr.optInt(it, -1) }.filter { it >= 0 }.toSet()
    }
}
