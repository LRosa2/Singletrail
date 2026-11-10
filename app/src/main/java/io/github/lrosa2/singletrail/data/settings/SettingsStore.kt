package io.github.lrosa2.singletrail.data.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.lrosa2.singletrail.nav.engine.NavConfig
import io.github.lrosa2.singletrail.ride.BatteryPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

/** Ride settings (spec §8.1). The rest of the settings screen arrives with later milestones. */
data class RideSettings(
    /** Off-route threshold T, 15–80 m (spec §5.6). */
    val offRouteMeters: Int = 30,
    /** The beeps' own volume, 0–1, independent of the media volume (spec §4.3). */
    val beepVolume: Float = 0.8f,
    /** For magnetic mounts: never use the compass (spec §5.9). */
    val compassOff: Boolean = false,
    /** Opt-in per-ride debug trace with raw positions (spec §11). */
    val debugTrace: Boolean = false,
    /** Dim the screen between turns (spec §4.4). */
    val dimBetweenTurns: Boolean = true,
    /** Window brightness while dimmed, 0–1. */
    val dimLevel: Float = 0.15f,
    /** Battery percentage at which low-power mode is offered; the warning comes 10 points earlier (spec §4.4). */
    val lowPowerAt: Int = BatteryPolicy.DEFAULT_LOW_POWER_AT,
) {
    fun navConfig(): NavConfig = NavConfig(offRouteMeters = offRouteMeters.toDouble(), compassOff = compassOff)

    companion object {
        val OFF_ROUTE_RANGE = 15..80
        /** Window brightness while dimmed; never fully black (spec §4.4). */
        val DIM_RANGE = 0.02f..0.6f
        val LOW_POWER_RANGE = 10..50
    }
}

private val Context.settingsData by preferencesDataStore(name = "settings")

class SettingsStore(context: Context) {
    private val data = context.applicationContext.settingsData

    val settings: Flow<RideSettings> = data.data.map { it.toSettings() }

    suspend fun current(): RideSettings = settings.first()

    suspend fun update(transform: (RideSettings) -> RideSettings) {
        data.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs[OFF_ROUTE] = next.offRouteMeters.coerceIn(RideSettings.OFF_ROUTE_RANGE)
            prefs[BEEP_VOLUME] = next.beepVolume.coerceIn(0f, 1f)
            prefs[COMPASS_OFF] = next.compassOff
            prefs[DEBUG_TRACE] = next.debugTrace
            prefs[DIM_BETWEEN_TURNS] = next.dimBetweenTurns
            prefs[DIM_LEVEL] = next.dimLevel.coerceIn(RideSettings.DIM_RANGE)
            prefs[LOW_POWER_AT] = next.lowPowerAt.coerceIn(RideSettings.LOW_POWER_RANGE)
        }
    }

    /** The ride screen's last view (spec §4.1): 0 arrow, 1 map. */
    val rideView: Flow<Int> = data.data.map { it[RIDE_VIEW] ?: 0 }

    suspend fun setRideView(page: Int) {
        data.edit { it[RIDE_VIEW] = page }
    }

    private fun Preferences.toSettings(): RideSettings {
        val defaults = RideSettings()
        return RideSettings(
            offRouteMeters = (this[OFF_ROUTE] ?: defaults.offRouteMeters).coerceIn(RideSettings.OFF_ROUTE_RANGE),
            beepVolume = (this[BEEP_VOLUME] ?: defaults.beepVolume).coerceIn(0f, 1f),
            compassOff = this[COMPASS_OFF] ?: defaults.compassOff,
            debugTrace = this[DEBUG_TRACE] ?: defaults.debugTrace,
            dimBetweenTurns = this[DIM_BETWEEN_TURNS] ?: defaults.dimBetweenTurns,
            dimLevel = (this[DIM_LEVEL] ?: defaults.dimLevel).coerceIn(RideSettings.DIM_RANGE),
            lowPowerAt = (this[LOW_POWER_AT] ?: defaults.lowPowerAt).coerceIn(RideSettings.LOW_POWER_RANGE),
        )
    }

    /** The library's sort order (by name) and minimum stars, remembered across restarts. */
    suspend fun libraryView(): Pair<String?, Int?> {
        val prefs = data.data.first()
        return prefs[LIBRARY_SORT] to prefs[LIBRARY_MIN_STARS]?.takeIf { it in 1..5 }
    }

    suspend fun saveLibraryView(sort: String, minStars: Int?) {
        data.edit {
            it[LIBRARY_SORT] = sort
            it[LIBRARY_MIN_STARS] = minStars ?: 0
        }
    }

    /** The watched folder's tree URI (spec §8.4), or null when none is set. */
    val watchedFolder: Flow<String?> = data.data.map { it[WATCHED_FOLDER] }

    suspend fun currentWatchedFolder(): String? = data.data.first()[WATCHED_FOLDER]

    suspend fun setWatchedFolder(uri: String?) {
        data.edit { if (uri == null) it.remove(WATCHED_FOLDER) else it[WATCHED_FOLDER] = uri }
    }

    /** When the last full backup was saved, or null if never (for the export reminder, spec §8.1). */
    val lastBackupAt: Flow<Long?> = data.data.map { it[LAST_BACKUP] }

    suspend fun markBackedUp(at: Long) {
        data.edit { it[LAST_BACKUP] = at }
    }

    /** A random id for this installation, written into full backups so a restore can tell "mine". */
    suspend fun backupOwner(): String {
        data.data.first()[BACKUP_OWNER]?.let { return it }
        var id = ""
        data.edit { prefs -> id = prefs[BACKUP_OWNER] ?: UUID.randomUUID().toString().also { prefs[BACKUP_OWNER] = it } }
        return id
    }

    private companion object {
        val DIM_BETWEEN_TURNS = booleanPreferencesKey("dim_between_turns")
        val DIM_LEVEL = floatPreferencesKey("dim_level")
        val LOW_POWER_AT = intPreferencesKey("low_power_at")
        val RIDE_VIEW = intPreferencesKey("ride_view")
        val WATCHED_FOLDER = stringPreferencesKey("watched_folder")
        val LIBRARY_SORT = stringPreferencesKey("library_sort")
        val LIBRARY_MIN_STARS = intPreferencesKey("library_min_stars")
        val LAST_BACKUP = longPreferencesKey("last_backup_at")
        val BACKUP_OWNER = stringPreferencesKey("backup_owner")
        val OFF_ROUTE = intPreferencesKey("off_route_m")
        val BEEP_VOLUME = floatPreferencesKey("beep_volume")
        val COMPASS_OFF = booleanPreferencesKey("compass_off")
        val DEBUG_TRACE = booleanPreferencesKey("debug_trace")
    }
}
