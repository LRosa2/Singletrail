package io.github.lrosa2.singletrail.nav.engine

import io.github.lrosa2.singletrail.nav.engine.NavEvent.Finished
import io.github.lrosa2.singletrail.nav.engine.NavEvent.ReversePrompt
import io.github.lrosa2.singletrail.nav.engine.NavEvent.ReverseUndone
import io.github.lrosa2.singletrail.nav.engine.NavEvent.Reversed
import io.github.lrosa2.singletrail.nav.engine.NavEvent.TurnNow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReverseTest {

    private val line = navRoute(0 to 0, 0 to 1000)

    @Test
    fun `riding the route backwards prompts after about 100 m`() {
        val ride = Ride(Navigator(line))
        ride.ride(0 to 500, 0 to 380)
        val at = ride.fixesWhere { it == ReversePrompt }.single()
        assertTrue(500 - at.y in 95.0..115.0, "prompted after ${500 - at.y} m")
        assertEquals(RidePhase.REVERSE_PENDING, ride.state.phase)
        assertEquals(at.elapsedNanos + 10 * NANOS_PER_SECOND, ride.state.reverseDeadlineNanos)
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
        ride.ride(0 to 500, 0 to 390)
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
        ride.ride(0 to 500, 0 to 390)
        assertEquals(RidePhase.REVERSE_PENDING, ride.state.phase)
        val u = ride.nav.undoReverse(ride.nanos)
        assertEquals(listOf<NavEvent>(ReverseUndone), u.events)
        assertFalse(u.state.reversed)
        assertEquals(RidePhase.ON_ROUTE, u.state.phase)
        // Riding on backwards does not prompt again for 500 m.
        ride.ride(0 to 390, 0 to 0)
        assertEquals(1, ride.count<ReversePrompt>())
    }

    @Test
    fun `starting a loop the wrong way round prompts too`() {
        // Clockwise square; the rider sets off anticlockwise from the start.
        val square = navRoute(0 to 0, 0 to 250, 250 to 250, 250 to 0, 0 to 0)
        val ride = Ride(Navigator(square))
        ride.ride(0 to 0, 250 to 0, 250 to 50)
        val at = ride.fixesWhere { it == ReversePrompt }.single()
        assertTrue(at.x in 95.0..140.0, "prompted at x = ${at.x}")
        ride.nav.acceptReverse(ride.nanos)
        assertTrue(ride.state.reversed)
        assertEquals(300.0, ride.state.progressM, 5.0)
        assertEquals(700.0, ride.state.distanceLeftM, 5.0)
    }

    @Test
    fun `slow riding backwards does not prompt`() {
        val ride = Ride(Navigator(line))
        ride.ride(0 to 500, 0 to 300, speedMps = 1.5)
        assertEquals(0, ride.count<ReversePrompt>())
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
