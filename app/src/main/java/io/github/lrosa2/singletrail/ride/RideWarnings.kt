package io.github.lrosa2.singletrail.ride

/** Problems shown on the ride screen because they would make guidance fail silently (spec §4.3, §4.4, §6). */
enum class RideWarning {
    /** Location is switched off in the phone's quick settings: no GPS. */
    LOCATION_OFF,

    /** The alarm stream is muted, so no beeps. */
    ALARM_VOLUME_OFF,

    /** Do Not Disturb is set to block alarms. */
    DND_BLOCKS_ALARMS,

    /** A Bluetooth audio device is connected; beeps may go to it instead of the phone speaker. */
    BLUETOOTH_AUDIO,

    /** Battery Saver would stop or throttle GPS when the screen goes off. */
    BATTERY_SAVER_GPS,

    /** Notifications are off, so the ride notification is hidden. */
    NOTIFICATIONS_OFF,

    /** Storage ran low or a write failed: recording stopped, navigation goes on. */
    RECORDING_STOPPED,
}

enum class DndMode { OFF, PRIORITY, ALARMS_ONLY, TOTAL_SILENCE }

/** `PowerManager.getLocationPowerSaveMode()` values. */
enum class SaverLocationMode { NO_CHANGE, GPS_OFF_SCREEN_OFF, ALL_OFF_SCREEN_OFF, FOREGROUND_ONLY, THROTTLED_SCREEN_OFF }

data class AudioReadings(
    val alarmVolume: Int,
    val dnd: DndMode,
    /** Whether the priority-mode policy lets alarms through; null when the app can't read it. */
    val alarmsInPriority: Boolean?,
    val bluetoothOutput: Boolean,
)

object RideWarnings {
    fun audio(r: AudioReadings): Set<RideWarning> = buildSet {
        if (r.alarmVolume <= 0) add(RideWarning.ALARM_VOLUME_OFF)
        val blocked = when (r.dnd) {
            DndMode.OFF, DndMode.ALARMS_ONLY -> false
            DndMode.TOTAL_SILENCE -> true
            // Alarms pass in priority mode unless the user turned them off there.
            DndMode.PRIORITY -> r.alarmsInPriority == false
        }
        if (blocked) add(RideWarning.DND_BLOCKS_ALARMS)
        if (r.bluetoothOutput) add(RideWarning.BLUETOOTH_AUDIO)
    }

    /** A foreground service counts as foreground, so "foreground only" keeps GPS running. */
    fun batterySaver(on: Boolean, mode: SaverLocationMode): Set<RideWarning> {
        if (!on) return emptySet()
        return when (mode) {
            SaverLocationMode.NO_CHANGE, SaverLocationMode.FOREGROUND_ONLY -> emptySet()
            SaverLocationMode.GPS_OFF_SCREEN_OFF,
            SaverLocationMode.ALL_OFF_SCREEN_OFF,
            SaverLocationMode.THROTTLED_SCREEN_OFF,
            -> setOf(RideWarning.BATTERY_SAVER_GPS)
        }
    }
}
