package io.github.lrosa2.singletrail.ride

import io.github.lrosa2.singletrail.ride.RideWarning.ALARM_VOLUME_OFF
import io.github.lrosa2.singletrail.ride.RideWarning.BATTERY_SAVER_GPS
import io.github.lrosa2.singletrail.ride.RideWarning.BLUETOOTH_AUDIO
import io.github.lrosa2.singletrail.ride.RideWarning.DND_BLOCKS_ALARMS
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RideWarningsTest {

    private fun audio(
        alarmVolume: Int = 5,
        dnd: DndMode = DndMode.OFF,
        alarmsInPriority: Boolean? = true,
        bluetooth: Boolean = false,
    ) = RideWarnings.audio(AudioReadings(alarmVolume, dnd, alarmsInPriority, bluetooth))

    @Test
    fun `a normal setup has no audio warnings`() {
        assertEquals(emptySet<RideWarning>(), audio())
    }

    @Test
    fun `alarm volume at zero is a warning`() {
        assertEquals(setOf(ALARM_VOLUME_OFF), audio(alarmVolume = 0))
    }

    @Test
    fun `total silence blocks alarms, alarms-only does not`() {
        assertEquals(setOf(DND_BLOCKS_ALARMS), audio(dnd = DndMode.TOTAL_SILENCE))
        assertEquals(emptySet<RideWarning>(), audio(dnd = DndMode.ALARMS_ONLY))
    }

    @Test
    fun `priority mode blocks alarms only when its policy says so`() {
        assertEquals(emptySet<RideWarning>(), audio(dnd = DndMode.PRIORITY, alarmsInPriority = true))
        assertEquals(setOf(DND_BLOCKS_ALARMS), audio(dnd = DndMode.PRIORITY, alarmsInPriority = false))
        // Unknown policy: alarms are allowed in priority mode by default, so no warning.
        assertEquals(emptySet<RideWarning>(), audio(dnd = DndMode.PRIORITY, alarmsInPriority = null))
    }

    @Test
    fun `a Bluetooth audio device is a warning`() {
        assertEquals(setOf(BLUETOOTH_AUDIO), audio(bluetooth = true))
    }

    @Test
    fun `battery saver warns only when it would stop or throttle GPS with the screen off`() {
        assertEquals(emptySet<RideWarning>(), RideWarnings.batterySaver(on = false, mode = SaverLocationMode.GPS_OFF_SCREEN_OFF))
        assertEquals(emptySet<RideWarning>(), RideWarnings.batterySaver(on = true, mode = SaverLocationMode.NO_CHANGE))
        assertEquals(emptySet<RideWarning>(), RideWarnings.batterySaver(on = true, mode = SaverLocationMode.FOREGROUND_ONLY))
        for (mode in listOf(SaverLocationMode.GPS_OFF_SCREEN_OFF, SaverLocationMode.ALL_OFF_SCREEN_OFF, SaverLocationMode.THROTTLED_SCREEN_OFF)) {
            assertEquals(setOf(BATTERY_SAVER_GPS), RideWarnings.batterySaver(on = true, mode = mode), mode.name)
        }
    }
}
