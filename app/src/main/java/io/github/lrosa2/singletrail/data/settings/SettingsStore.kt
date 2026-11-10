package io.github.lrosa2.singletrail.data.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.lrosa2.singletrail.nav.engine.NavConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

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
) {
    fun navConfig(): NavConfig = NavConfig(offRouteMeters = offRouteMeters.toDouble(), compassOff = compassOff)

    companion object {
        val OFF_ROUTE_RANGE = 15..80
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
        }
    }

    private fun Preferences.toSettings(): RideSettings {
        val defaults = RideSettings()
        return RideSettings(
            offRouteMeters = (this[OFF_ROUTE] ?: defaults.offRouteMeters).coerceIn(RideSettings.OFF_ROUTE_RANGE),
            beepVolume = (this[BEEP_VOLUME] ?: defaults.beepVolume).coerceIn(0f, 1f),
            compassOff = this[COMPASS_OFF] ?: defaults.compassOff,
            debugTrace = this[DEBUG_TRACE] ?: defaults.debugTrace,
        )
    }

    private companion object {
        val OFF_ROUTE = intPreferencesKey("off_route_m")
        val BEEP_VOLUME = floatPreferencesKey("beep_volume")
        val COMPASS_OFF = booleanPreferencesKey("compass_off")
        val DEBUG_TRACE = booleanPreferencesKey("debug_trace")
    }
}
