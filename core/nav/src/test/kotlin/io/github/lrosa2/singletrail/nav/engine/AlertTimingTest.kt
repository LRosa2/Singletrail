package io.github.lrosa2.singletrail.nav.engine

import io.github.lrosa2.singletrail.nav.corner
import io.github.lrosa2.singletrail.nav.engine.NavEvent.TurnApproaching
import io.github.lrosa2.singletrail.nav.engine.NavEvent.TurnNow
import io.github.lrosa2.singletrail.nav.zigzag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.api.Test
import kotlin.math.max

class AlertTimingTest {

    /** 600 m north, then a right turn. */
    private val turn = navRoute(corner(90.0, leg = 600.0))

    /** Distance from the rider's progress to the maneuver when the first event of type [E] fired. */
    private inline fun <reified E : NavEvent> Ride.distanceAt(): Double {
        val (_, u) = log.first { (_, u) -> u.events.any { it is E } }
        val m = when (val e = u.events.first { it is E }) {
            is TurnApproaching -> e.maneuver
            is TurnNow -> e.maneuver
            else -> error("not a turn event")
        }
        return m.s - u.state.progressM
    }

    @ParameterizedTest(name = "{0} km/h")
    @CsvSource("5", "20", "45")
    fun `alerts fire at speed-adaptive distances`(kmh: Double) {
        val v = kmh / 3.6
        val ride = Ride(Navigator(turn))
        ride.ride(0 to 0, 0 to 600, 100 to 600, speedMps = v)
        assertEquals(1, ride.count<TurnApproaching>())
        assertEquals(1, ride.count<TurnNow>())
        val approach = max(80.0, 12 * v)
        val now = max(15.0, 3 * v)
        // One fix a second: the alert fires on the first fix inside the distance.
        val a = ride.distanceAt<TurnApproaching>()
        val n = ride.distanceAt<TurnNow>()
        assertTrue(a <= approach && a > approach - v - 1, "approaching at $a m, expected just inside $approach m")
        assertTrue(n <= now && n > now - v - 1, "now at $n m, expected just inside $now m")
    }

    @Test
    fun `fix age moves the alert earlier by the distance ridden since the fix`() {
        val v = 20 / 3.6
        val ride = Ride(Navigator(turn))
        // Every fix is processed 2 s after it was taken.
        ride.ride(0 to 0, 0 to 600, 100 to 600, speedMps = v, ageS = 2.0)
        val a = ride.distanceAt<TurnApproaching>()
        val early = 80.0 + 2 * v
        assertTrue(a <= early && a > early - v - 1, "approaching at $a m, expected just inside $early m")
    }

    @Test
    fun `ticks between fixes extrapolate the position`() {
        val v = 45 / 3.6
        val nav = Navigator(turn)
        val ride = Ride(nav, intervalS = 4.0)
        ride.ride(0 to 25, 0 to 425, speedMps = v)
        // Fixes every 4 s (50 m), the last 175 m before the turn; ticks every 0.5 s catch the 150 m mark.
        var fired: Double? = null
        var t = ride.nanos - 4 * NANOS_PER_SECOND
        val lastFix = t
        while (fired == null) {
            t += NANOS_PER_SECOND / 2
            val u = nav.onTick(t)
            val e = u.events.filterIsInstance<TurnApproaching>().firstOrNull()
            if (e != null) fired = e.maneuver.s - (u.state.progressM + v * (t - lastFix) / NANOS_PER_SECOND.toDouble())
            if (t - lastFix > 4 * NANOS_PER_SECOND) break
        }
        val d = fired ?: error("no alert from ticks")
        assertTrue(d <= 150.0 && d > 150.0 - v / 2 - 1, "approaching at $d m")
    }

    @Test
    fun `each alert fires once despite GPS jitter`() {
        val ride = Ride(Navigator(turn))
        ride.ride(0 to 0, 0 to 600, 100 to 600, noiseM = 6.0)
        assertEquals(1, ride.count<TurnApproaching>())
        assertEquals(1, ride.count<TurnNow>())
    }

    @Test
    fun `slight bends show but do not beep`() {
        val nav = Navigator(navRoute(corner(50.0, leg = 300.0)))
        val ride = Ride(nav)
        ride.ride(corner(50.0, leg = 300.0))
        assertEquals(0, ride.count<TurnApproaching>() + ride.count<TurnNow>())
        assertEquals(1, nav.maneuvers.size)
    }

    @Test
    fun `slight bends beep when enabled`() {
        val ride = Ride(Navigator(navRoute(corner(50.0, leg = 300.0)), NavConfig(beepForSlight = true)))
        ride.ride(corner(50.0, leg = 300.0))
        assertEquals(1, ride.count<TurnApproaching>())
        assertEquals(1, ride.count<TurnNow>())
    }

    @Test
    fun `switchbacks get one approaching alert and no now alert`() {
        val ride = Ride(Navigator(navRoute(zigzag(hairpins = 5))))
        ride.ride(zigzag(hairpins = 5))
        assertEquals(1, ride.count<TurnApproaching>())
        assertEquals(0, ride.count<TurnNow>())
    }

    @Test
    fun `no alerts while off route`() {
        val ride = Ride(Navigator(turn))
        // Leaves the route at 400 m and rides parallel 100 m east, past the turn.
        ride.ride(0 to 0, 0 to 400, 100 to 400, 100 to 700)
        assertEquals(0, ride.count<TurnApproaching>() + ride.count<TurnNow>())
    }

    @Test
    fun `no alerts while GPS is lost`() {
        // Long extrapolation, so ticks alone would reach the approach zone 14 s after the last fix.
        val nav = Navigator(turn, NavConfig(maxExtrapolationSeconds = 30.0))
        val ride = Ride(nav)
        ride.ride(0 to 0, 0 to 450)
        ride.silence(11.0)
        assertEquals(GpsStatus.LOST, ride.state.gps)
        repeat(20) { ride.tick(ride.nanos + it * NANOS_PER_SECOND) }
        assertEquals(0, ride.count<TurnApproaching>())
    }

    @Test
    fun `the next maneuver and the one after it are shown`() {
        // Right at 300 m, left 50 m later.
        val ride = Ride(Navigator(navRoute(0 to 0, 0 to 300, 50 to 300, 50 to 600)))
        ride.ride(0 to 0, 0 to 200)
        val next = ride.state.next!!
        assertEquals(0, next.index)
        assertEquals(100.0, next.distanceM, 6.0)
        val then = ride.state.then!!
        assertEquals(1, then.index)
        assertEquals(150.0, then.distanceM, 6.0)
    }
}
