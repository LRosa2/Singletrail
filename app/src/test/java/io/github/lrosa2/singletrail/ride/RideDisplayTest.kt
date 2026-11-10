package io.github.lrosa2.singletrail.ride

import android.view.KeyEvent
import io.github.lrosa2.singletrail.nav.Maneuver
import io.github.lrosa2.singletrail.nav.ManeuverKind
import io.github.lrosa2.singletrail.nav.engine.GpsStatus
import io.github.lrosa2.singletrail.nav.engine.Heading
import io.github.lrosa2.singletrail.nav.engine.HeadingSource
import io.github.lrosa2.singletrail.nav.engine.NavState
import io.github.lrosa2.singletrail.nav.engine.RidePhase
import io.github.lrosa2.singletrail.nav.engine.UpcomingManeuver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RideDisplayTest {

    private val turn = Maneuver(500.0, 46.5, 7.0, ManeuverKind.TURN, 90.0)

    private fun state(
        phase: RidePhase = RidePhase.ON_ROUTE,
        gps: GpsStatus = GpsStatus.OK,
        nextIn: Double? = null,
        speed: Double = 5.0,
        heading: Heading? = Heading(0.0, HeadingSource.GPS),
        arrowDeg: Double? = 10.0,
    ) = NavState(
        phase = phase, gps = gps, satellites = 9, lastFixNanos = 0, reversed = false,
        progressM = 100.0, lengthM = 2000.0, distanceLeftM = 1900.0, climbLeftM = null, distanceToRouteM = 2.0,
        next = nextIn?.let { UpcomingManeuver(0, turn, it) }, then = null, target = null, heading = heading,
        arrowDeg = arrowDeg, reverseDeadlineNanos = null, skipped = emptyList(), rideDistanceM = 0.0,
        movingSeconds = 0.0, speedMps = speed,
    )

    private fun brightness(
        s: NavState,
        sinceTouch: Double = 60.0,
        dim: Boolean = true,
        lowPower: Boolean = false,
    ) = ScreenPolicy.brightness(s, sinceTouch, dimBetweenTurns = dim, lowPower = lowPower, dimLevel = 0.2f)

    @Test
    fun `the screen dims on a stretch with no turn coming`() {
        assertEquals(0.2f, brightness(state(nextIn = 600.0)))
        assertEquals(0.2f, brightness(state(nextIn = null)))
    }

    @Test
    fun `full brightness about 15 s before a turn`() {
        // 5 m/s: 15 s is 75 m.
        assertNull(brightness(state(nextIn = 70.0)))
        assertEquals(0.2f, brightness(state(nextIn = 100.0)))
        // Walking pace: still bright for the last 50 m.
        assertNull(brightness(state(nextIn = 40.0, speed = 1.0)))
    }

    @Test
    fun `full brightness whenever something needs attention`() {
        for (phase in listOf(RidePhase.WAITING_GPS, RidePhase.ACQUIRING, RidePhase.OFF_ROUTE, RidePhase.REVERSE_PENDING, RidePhase.FINISHED)) {
            assertNull(brightness(state(phase = phase)), phase.name)
        }
        assertNull(brightness(state(gps = GpsStatus.LOST)))
    }

    @Test
    fun `a touch brightens the screen for a while`() {
        assertNull(brightness(state(), sinceTouch = 3.0))
        assertEquals(0.2f, brightness(state(), sinceTouch = 30.0))
    }

    @Test
    fun `with dimming off the phone's own brightness is used`() {
        assertNull(brightness(state(nextIn = 900.0), dim = false))
    }

    @Test
    fun `low-power mode is dimmer, and dims even with dimming off`() {
        assertEquals(0.1f, brightness(state(nextIn = 900.0), dim = false, lowPower = true))
        assertEquals(ScreenPolicy.LOW_POWER_BRIGHT, brightness(state(nextIn = 50.0), lowPower = true))
    }

    @Test
    fun `battery advice at 30 and 20 percent, never while charging`() {
        assertEquals(BatteryAdvice.NONE, BatteryPolicy.advice(31, charging = false))
        assertEquals(BatteryAdvice.LOW, BatteryPolicy.advice(30, charging = false))
        assertEquals(BatteryAdvice.VERY_LOW, BatteryPolicy.advice(20, charging = false))
        assertEquals(BatteryAdvice.NONE, BatteryPolicy.advice(5, charging = true))
        assertEquals(BatteryAdvice.NONE, BatteryPolicy.advice(null, charging = false))
    }

    @Test
    fun `the low-power threshold moves the offer, and the warning comes 10 points before it`() {
        assertEquals(BatteryAdvice.VERY_LOW, BatteryPolicy.advice(35, charging = false, lowPowerAt = 35))
        assertEquals(BatteryAdvice.LOW, BatteryPolicy.advice(45, charging = false, lowPowerAt = 35))
        assertEquals(BatteryAdvice.NONE, BatteryPolicy.advice(46, charging = false, lowPowerAt = 35))
        assertEquals(BatteryAdvice.LOW, BatteryPolicy.advice(11, charging = false, lowPowerAt = 10))
    }

    @Test
    fun `volume keys answer the reverse prompt only while it is showing`() {
        assertEquals(PromptKey.ACCEPT, PromptKey.of(KeyEvent.KEYCODE_VOLUME_UP, RidePhase.REVERSE_PENDING))
        assertEquals(PromptKey.UNDO, PromptKey.of(KeyEvent.KEYCODE_VOLUME_DOWN, RidePhase.REVERSE_PENDING))
        assertNull(PromptKey.of(KeyEvent.KEYCODE_VOLUME_UP, RidePhase.ON_ROUTE))
        assertNull(PromptKey.of(KeyEvent.KEYCODE_BACK, RidePhase.REVERSE_PENDING))
    }

    @Test
    fun `the arrow is amber off route and grey without GPS or a live heading`() {
        assertEquals(ArrowLook(ArrowTone.NORMAL, compass = false), ArrowLook.of(state()))
        assertEquals(ArrowTone.WARNING, ArrowLook.of(state(phase = RidePhase.OFF_ROUTE))!!.tone)
        assertEquals(ArrowTone.INACTIVE, ArrowLook.of(state(gps = GpsStatus.LOST))!!.tone)
        assertEquals(ArrowTone.INACTIVE, ArrowLook.of(state(heading = Heading(0.0, HeadingSource.FROZEN)))!!.tone)
        assertTrue(ArrowLook.of(state(heading = Heading(0.0, HeadingSource.COMPASS)))!!.compass)
    }

    @Test
    fun `no arrow while waiting, at the finish, or without a heading`() {
        assertNull(ArrowLook.of(state(phase = RidePhase.WAITING_GPS)))
        assertNull(ArrowLook.of(state(phase = RidePhase.FINISHED)))
        assertNull(ArrowLook.of(state(heading = null, arrowDeg = null)))
        assertFalse(ArrowLook.of(state())!!.compass)
    }
}
