package io.github.lrosa2.singletrail.nav.engine

import io.github.lrosa2.singletrail.nav.at
import io.github.lrosa2.singletrail.nav.engine.NavEvent.BackOnRoute
import io.github.lrosa2.singletrail.nav.engine.NavEvent.Finished
import io.github.lrosa2.singletrail.nav.engine.NavEvent.OffRoute
import io.github.lrosa2.singletrail.nav.engine.NavEvent.ReversePrompt
import io.github.lrosa2.singletrail.nav.engine.NavEvent.ReverseUndone
import io.github.lrosa2.singletrail.nav.engine.NavEvent.Reversed
import io.github.lrosa2.singletrail.nav.engine.NavEvent.TurnNow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random

class ReverseTest {

    private val line = navRoute(0 to 0, 0 to 1000)

    @Test
    fun `riding back after riding forward prompts after about 100 m`() {
        val ride = Ride(Navigator(line))
        ride.ride(0 to 400, 0 to 550, 0 to 420)
        val at = ride.fixesWhere { it == ReversePrompt }.single()
        assertTrue(550 - at.y in 95.0..115.0, "prompted after ${550 - at.y} m")
        assertEquals(RidePhase.REVERSE_PENDING, ride.state.phase)
        assertEquals(at.elapsedNanos + 10 * NANOS_PER_SECOND, ride.state.reverseDeadlineNanos)
    }

    @Test
    fun `riding back right after joining prompts after about 50 m`() {
        val ride = Ride(Navigator(line))
        ride.ride(0 to 500, 0 to 420)
        val at = ride.fixesWhere { it == ReversePrompt }.single()
        assertTrue(500 - at.y in 45.0..65.0, "prompted after ${500 - at.y} m")
    }

    @Test
    fun `climbing back the other way from where the route was joined switches direction`() {
        // Field report: arrived at the route, set off the other way uphill, and it never switched.
        val ride = Ride(Navigator(line))
        ride.ride(80 to 500, 0 to 500, 0 to 0, speedMps = 1.9)
        assertEquals(0, ride.count<OffRoute>())
        assertEquals(1, ride.count<Reversed>())
        assertTrue(ride.state.reversed)
        assertEquals(RidePhase.FINISHED, ride.state.phase)
    }

    @Test
    fun `the prompt accepts itself after ten seconds`() {
        val ride = Ride(Navigator(line))
        ride.ride(0 to 500, 0 to 300)
        val prompt = ride.fixesWhere { it == ReversePrompt }.single()
        val switched = ride.fixesWhere { it == Reversed }.single()
        assertEquals(10 * NANOS_PER_SECOND, switched.elapsedNanos - prompt.elapsedNanos)
        assertTrue(ride.state.reversed)
        assertEquals(RidePhase.ON_ROUTE, ride.state.phase)
        // Now 700 m into the reversed route, 300 m from its finish at the old start.
        assertEquals(700.0, ride.state.progressM, 3.0)
        assertEquals(300.0, ride.state.distanceLeftM, 3.0)
        assertEquals(0.0, ride.nav.route.positionAt(ride.nav.route.lengthMeters).let { io.github.lrosa2.singletrail.nav.Plane.y(it.lat) }, 0.5)
    }

    @Test
    fun `accepting at once switches without waiting`() {
        val ride = Ride(Navigator(line))
        ride.ride(0 to 500, 0 to 430)
        assertEquals(RidePhase.REVERSE_PENDING, ride.state.phase)
        val u = ride.nav.acceptReverse(ride.nanos)
        assertEquals(listOf<NavEvent>(Reversed), u.events)
        assertTrue(u.state.reversed)
    }

    @Test
    fun `turns on the reversed route are announced`() {
        // Forward: north then right. Reversed: west then left at the same corner.
        val ride = Ride(Navigator(navRoute(0 to 0, 0 to 300, 300 to 300)))
        ride.ride(250 to 300, 0 to 300, 0 to 0)
        assertEquals(1, ride.count<Reversed>())
        val now = ride.events.filterIsInstance<TurnNow>().single()
        assertTrue(now.maneuver.angleDeg < 0, "left turn on the reversed route")
        assertEquals(1, ride.count<Finished>())
    }

    @Test
    fun `undo keeps the direction and suppresses the prompt for 500 m`() {
        val ride = Ride(Navigator(line))
        ride.ride(0 to 500, 0 to 430)
        assertEquals(RidePhase.REVERSE_PENDING, ride.state.phase)
        val u = ride.nav.undoReverse(ride.nanos)
        assertEquals(listOf<NavEvent>(ReverseUndone), u.events)
        assertFalse(u.state.reversed)
        assertEquals(RidePhase.ON_ROUTE, u.state.phase)
        // Riding on backwards does not prompt again for 500 m, off the route or on it.
        ride.ride(0 to 430, 0 to 0)
        assertEquals(1, ride.count<ReversePrompt>())
    }

    @Test
    fun `starting a loop the wrong way round prompts too`() {
        // Clockwise square; the rider sets off anticlockwise from the start.
        val square = navRoute(0 to 0, 0 to 250, 250 to 250, 250 to 0, 0 to 0)
        val ride = Ride(Navigator(square))
        ride.ride(0 to 0, 250 to 0, 250 to 50)
        val at = ride.fixesWhere { it == ReversePrompt }.single()
        assertTrue(at.x in 45.0..70.0, "prompted at x = ${at.x}")
        ride.nav.acceptReverse(ride.nanos)
        assertTrue(ride.state.reversed)
        assertEquals(300.0, ride.state.progressM, 5.0)
        assertEquals(700.0, ride.state.distanceLeftM, 5.0)
    }

    @Test
    fun `slow riding backwards prompts too`() {
        val ride = Ride(Navigator(line))
        ride.ride(0 to 400, 0 to 550, 0 to 300, speedMps = 1.5)
        assertEquals(1, ride.count<ReversePrompt>())
        assertEquals(1, ride.count<Reversed>())
    }

    @Test
    fun `slow riding back with GPS noise prompts`() {
        val ride = Ride(Navigator(line))
        ride.ride(0 to 400, 0 to 550, 0 to 300, speedMps = 1.5, noiseM = 5.0, accuracyM = 8f)
        assertEquals(1, ride.count<ReversePrompt>())
        assertEquals(0, ride.count<OffRoute>())
    }

    @Test
    fun `a loop joined mid-side and climbed the other way switches direction`() {
        val square = navRoute(0 to 0, 0 to 250, 250 to 250, 250 to 0, 0 to 0)
        val ride = Ride(Navigator(square))
        ride.ride(-60 to 125, 0 to 125, 0 to 0, 250 to 0, speedMps = 1.9, noiseM = 4.0, accuracyM = 8f)
        assertEquals(0, ride.count<OffRoute>())
        assertEquals(1, ride.count<Reversed>())
        assertTrue(ride.state.reversed)
    }

    @Test
    fun `an unsure GPS heading does not restart the count`() {
        val ride = Ride(Navigator(line))
        ride.ride(0 to 400, 0 to 550)
        ride.ride(0 to 550, 0 to 420, bearingAccDeg = 45f)
        assertEquals(1, ride.count<ReversePrompt>())
    }

    @Test
    fun `riding forward in between restarts the count`() {
        val ride = Ride(Navigator(line))
        ride.ride(0 to 400, 0 to 650, 0 to 580, 0 to 610, 0 to 540)
        assertEquals(0, ride.count<ReversePrompt>())
    }

    @Test
    fun `standing still with GPS jitter never prompts`() {
        val ride = Ride(Navigator(line))
        ride.ride(0 to 500, 0 to 520)
        val random = Random(7)
        repeat(600) {
            val p = at(random.nextGaussian() * 8, 520 + random.nextGaussian() * 8)
            ride.fix(Fix(ride.nanos, p.lat, p.lon, 12f, 0.2f, random.nextFloat() * 360, 90f, null, 12))
        }
        assertEquals(0, ride.count<ReversePrompt>())
        assertEquals(0, ride.count<OffRoute>())
    }

    @Test
    fun `slowly riding back an out-and-back never prompts`() {
        // Out 500 m, back on a parallel line 6 m away; GPS noise of 8 m.
        val route = navRoute(0 to 0, 0 to 500, 6 to 500, 6 to 0)
        val ride = Ride(Navigator(route))
        ride.ride(0 to 0, 0 to 500, 6 to 500, 6 to 0, speedMps = 1.5, noiseM = 8.0, accuracyM = 10f)
        assertEquals(0, ride.count<ReversePrompt>())
        assertEquals(1, ride.count<Finished>())
    }

    @Test
    fun `riding the route the other way while off route prompts too`() {
        // On route to 300, a detour beside it, then back onto the route at 700 heading for the start.
        val ride = Ride(Navigator(line))
        ride.ride(0 to 0, 0 to 300, 100 to 300, 100 to 700, 0 to 700, 0 to 450)
        assertEquals(1, ride.count<OffRoute>())
        val at = ride.fixesWhere { it == ReversePrompt }.single()
        assertTrue(700 - at.y in 95.0..115.0, "prompted after ${700 - at.y} m on the route")
        assertEquals(1, ride.count<Reversed>())
        assertTrue(ride.state.reversed)
        assertEquals(RidePhase.ON_ROUTE, ride.state.phase)
        // 550 m into the reversed route, 450 m from its finish at the old start.
        assertEquals(550.0, ride.state.progressM, 5.0)
    }

    @Test
    fun `the route can be started reversed`() {
        val ride = Ride(Navigator(line, mode = RideMode(reversed = true)))
        ride.ride(0 to 1000, 0 to 900)
        assertTrue(ride.state.reversed)
        assertEquals(100.0, ride.state.progressM, 2.0)
        assertEquals(0, ride.count<ReversePrompt>())
    }
}
