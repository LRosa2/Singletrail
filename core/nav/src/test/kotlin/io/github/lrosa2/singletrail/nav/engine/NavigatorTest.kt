package io.github.lrosa2.singletrail.nav.engine

import io.github.lrosa2.singletrail.nav.at
import io.github.lrosa2.singletrail.nav.engine.NavEvent.BackOnRoute
import io.github.lrosa2.singletrail.nav.engine.NavEvent.Finished
import io.github.lrosa2.singletrail.nav.engine.NavEvent.Joined
import io.github.lrosa2.singletrail.nav.engine.NavEvent.OffRoute
import io.github.lrosa2.singletrail.nav.engine.NavEvent.ReversePrompt
import io.github.lrosa2.singletrail.nav.engine.NavEvent.SignalBack
import io.github.lrosa2.singletrail.nav.engine.NavEvent.SignalLost
import io.github.lrosa2.singletrail.nav.engine.NavEvent.TurnApproaching
import io.github.lrosa2.singletrail.nav.engine.NavEvent.TurnNow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class NavigatorTest {

    /** 1 km due north. */
    private val line = navRoute(0 to 0, 0 to 1000)

    @Test
    fun `waits for GPS until the first fix`() {
        val state = Navigator(line).state
        assertEquals(RidePhase.WAITING_GPS, state.phase)
        assertEquals(GpsStatus.WAITING, state.gps)
        assertNull(state.target)
    }

    @Test
    fun `far from the route it points at the nearest route point`() {
        val ride = Ride(Navigator(line))
        ride.stand(200, 500, seconds = 1.0)
        val state = ride.state
        assertEquals(RidePhase.ACQUIRING, state.phase)
        assertNotNull(state.target)
        val target = state.target!!
        assertEquals(TargetKind.NEAREST_POINT, target.kind)
        assertEquals(500.0, target.s, 1.0)
        assertEquals(200.0, target.distanceM, 1.0)
        assertEquals(270.0, target.bearingDeg, 1.0)
    }

    @Test
    fun `joins at the nearest point and follows the rider`() {
        val ride = Ride(Navigator(line))
        ride.ride(0 to 400, 0 to 500)
        assertEquals(listOf(Joined), ride.events)
        assertEquals(RidePhase.ON_ROUTE, ride.state.phase)
        assertEquals(500.0, ride.state.progressM, 2.0)
        assertEquals(500.0, ride.state.distanceLeftM, 2.0)
        assertEquals(TargetKind.LOOK_AHEAD, ride.state.target?.kind)
        assertEquals(530.0, ride.state.target!!.s, 2.0)
    }

    @Test
    fun `turns before the join point never alert`() {
        // Turn right after 300 m; the rider joins 50 m past it.
        val ride = Ride(Navigator(navRoute(0 to 0, 0 to 300, 300 to 300)))
        ride.ride(50 to 300, 300 to 300)
        assertEquals(0, ride.count<TurnApproaching>() + ride.count<TurnNow>())
        assertEquals(1, ride.count<Finished>())
    }

    @Test
    fun `an out-and-back never jumps to the other leg`() {
        // Out 500 m, back on a parallel line 6 m away; GPS noise of 8 m.
        val route = navRoute(0 to 0, 0 to 500, 6 to 500, 6 to 0)
        val ride = Ride(Navigator(route))
        ride.ride(0 to 0, 0 to 500, 6 to 500, 6 to 0, noiseM = 8.0, accuracyM = 10f)
        assertEquals(0, ride.count<OffRoute>())
        assertEquals(0, ride.count<ReversePrompt>())
        assertProgressFollows(ride, speedMps = 5.0, toleranceM = 40.0)
        assertEquals(1, ride.count<Finished>())
    }

    @Test
    fun `a figure-8 crossing never jumps`() {
        val route = navRoute(0 to 0, 200 to 200, 200 to 0, 0 to 200, 0 to 0)
        val ride = Ride(Navigator(route))
        ride.ride(0 to 0, 200 to 200, 200 to 0, 0 to 200, 0 to 0, noiseM = 5.0)
        assertEquals(0, ride.count<OffRoute>())
        assertEquals(0, ride.count<ReversePrompt>())
        assertProgressFollows(ride, speedMps = 5.0, toleranceM = 30.0)
        assertEquals(1, ride.count<Finished>())
    }

    /** Every fix's progress is within [toleranceM] of the distance ridden since the first fix. */
    private fun assertProgressFollows(ride: Ride, speedMps: Double, toleranceM: Double) {
        ride.log.filter { it.first != null }.forEachIndexed { k, (_, u) ->
            if (u.state.phase == RidePhase.FINISHED) return
            val ridden = k * speedMps
            assertTrue(abs(u.state.progressM - ridden) <= toleranceM, "fix $k: progress ${u.state.progressM} vs ridden $ridden")
        }
    }

    @Test
    fun `goes off route after three fixes and five seconds beyond the threshold`() {
        val ride = Ride(Navigator(line))
        // Fixes every 5 m from x = 3, so none sits exactly on the 30 m threshold.
        ride.ride(0 to 0, 0 to 302, 100 to 302)
        val at = ride.fixesWhere { it == OffRoute }.single()
        // First fix beyond 30 m is at x = 33; five seconds later the rider is at x = 58.
        assertEquals(58.0, at.x, 0.5)
        assertEquals(RidePhase.OFF_ROUTE, ride.state.phase)
    }

    @Test
    fun `one wild fix is not off route`() {
        val ride = Ride(Navigator(line))
        ride.ride(0 to 0, 0 to 200)
        ride.fix(at(80, 205).let { Fix(ride.nanos, it.lat, it.lon, 5f, 5f, 0f, 10f) })
        ride.ride(0 to 210, 0 to 300)
        assertEquals(0, ride.count<OffRoute>())
        assertEquals(300.0, ride.state.progressM, 2.0)
    }

    @Test
    fun `inaccurate fixes never put the rider off route`() {
        val ride = Ride(Navigator(line))
        ride.ride(0 to 0, 0 to 200)
        ride.ride(0 to 200, 100 to 200, accuracyM = 40f)
        assertEquals(0, ride.count<OffRoute>())
        assertEquals(RidePhase.ON_ROUTE, ride.state.phase)
        assertEquals(GpsStatus.POOR, ride.state.gps)
    }

    @Test
    fun `back on route after two fixes well within the threshold`() {
        val ride = Ride(Navigator(line))
        ride.ride(0 to 0, 0 to 300, 100 to 300, 0 to 300, 0 to 500)
        assertEquals(1, ride.count<OffRoute>())
        assertEquals(1, ride.count<BackOnRoute>())
        assertEquals(RidePhase.ON_ROUTE, ride.state.phase)
        assertEquals(500.0, ride.state.progressM, 2.0)
        assertEquals(emptyList<RouteRange>(), ride.state.skipped)
    }

    @Test
    fun `no GPS after ten seconds without a fix, back on the next fix`() {
        val ride = Ride(Navigator(line))
        ride.ride(0 to 0, 0 to 100)
        ride.silence(9.0)
        assertEquals(0, ride.count<SignalLost>())
        ride.silence(1.0)
        assertEquals(1, ride.count<SignalLost>())
        assertEquals(GpsStatus.LOST, ride.state.gps)
        assertEquals(RidePhase.ON_ROUTE, ride.state.phase)
        ride.ride(0 to 150, 0 to 160)
        assertEquals(1, ride.count<SignalBack>())
        assertEquals(GpsStatus.OK, ride.state.gps)
    }

    @Test
    fun `finishes near the end after most of the route`() {
        val ride = Ride(Navigator(line))
        ride.ride(0 to 0, 0 to 1000)
        val at = ride.fixesWhere { it == Finished }.single()
        assertTrue(at.y >= 970.0, "finished at ${at.y}")
        assertEquals(RidePhase.FINISHED, ride.state.phase)
    }

    @Test
    fun `a loop joined midway finishes back at the join point`() {
        val square = navRoute(0 to 0, 0 to 250, 250 to 250, 250 to 0, 0 to 0)
        val ride = Ride(Navigator(square))
        ride.ride(0 to 150, 0 to 250, 250 to 250, 250 to 0, 0 to 0, 0 to 250)
        val at = ride.fixesWhere { it == Finished }.single()
        assertEquals(0.0, at.x, 1.0)
        assertTrue(at.y in 120.0..160.0, "finished at y = ${at.y}")
        // All four corners were announced, including the one at the loop's original start.
        assertEquals(4, ride.count<TurnNow>())
    }

    @Test
    fun `a loop does not finish where it starts`() {
        val square = navRoute(0 to 0, 0 to 250, 250 to 250, 250 to 0, 0 to 0)
        val ride = Ride(Navigator(square))
        ride.ride(0 to 0, 0 to 100)
        assertEquals(0, ride.count<Finished>())
        assertEquals(100.0, ride.state.progressM, 2.0)
        assertEquals(900.0, ride.state.distanceLeftM, 2.0)
    }

    @Test
    fun `the state carries the latest position`() {
        val nav = Navigator(line)
        assertNull(nav.state.position)
        val p = at(12, 340)
        val state = nav.onFix(Fix(0, p.lat, p.lon, 40f)).state
        // Even an inaccurate fix moves the dot: the map shows where the phone thinks it is.
        assertEquals(LatLon(p.lat, p.lon), state.position)
    }

    @Test
    fun `the state reports the smoothed speed`() {
        val ride = Ride(Navigator(line))
        ride.ride(0 to 0, 0 to 100, speedMps = 4.0)
        assertEquals(4.0, ride.state.speedMps, 1e-6)
    }

    @Test
    fun `ride distance follows the fix speed, not GPS jitter`() {
        val ride = Ride(Navigator(line))
        ride.ride(0 to 0, 0 to 300, noiseM = 4.0)
        // Jitter of a few metres per fix would add a third or more to a fix-to-fix sum.
        assertEquals(300.0, ride.state.rideDistanceM, 6.0)
    }

    @Test
    fun `a position jump is not ridden distance`() {
        val ride = Ride(Navigator(line))
        // A stale fix 9,000 km away, then the rider on the route a second later; neither fix has a speed.
        val far = at(-9_000_000, 0)
        ride.fix(Fix(0, far.lat, far.lon, 5f))
        val p = at(0, 0)
        ride.fix(Fix(NANOS_PER_SECOND, p.lat, p.lon, 5f))
        val q = at(0, 5)
        ride.fix(Fix(2 * NANOS_PER_SECOND, q.lat, q.lon, 5f))
        assertEquals(5.0, ride.state.rideDistanceM, 0.5)
        assertEquals(1.0, ride.state.movingSeconds, 0.01)
    }

    @Test
    fun `ride distance and moving time count only while moving`() {
        val ride = Ride(Navigator(line))
        ride.ride(0 to 0, 0 to 100)
        ride.stand(0, 100, seconds = 30.0)
        ride.ride(0 to 100, 0 to 200)
        assertEquals(200.0, ride.state.rideDistanceM, 6.0)
        assertEquals(40.0, ride.state.movingSeconds, 2.0)
    }
}
