package io.github.lrosa2.singletrail.nav.engine

import io.github.lrosa2.singletrail.nav.engine.NavEvent.Finished
import io.github.lrosa2.singletrail.nav.engine.NavEvent.OffRoute
import io.github.lrosa2.singletrail.nav.engine.NavEvent.Reversed
import io.github.lrosa2.singletrail.nav.engine.NavEvent.TurnNow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CheckpointTest {

    /** North 400 m, right turn, east 400 m. */
    private val route = navRoute(0 to 0, 0 to 400, 400 to 400)

    @Test
    fun `a checkpoint survives JSON`() {
        val ride = Ride(Navigator(route))
        ride.ride(0 to 0, 0 to 400, 100 to 400)
        val cp = ride.nav.snapshot()
        assertEquals(cp, NavCheckpoint.fromJson(cp.toJson()))
    }

    @Test
    fun `a restored navigator carries on like the original`() {
        val original = Ride(Navigator(route))
        original.ride(0 to 0, 0 to 300)
        val restored = Ride(Navigator.restore(route, original.nav.snapshot()), nanos = original.nanos)
        assertEquals(GpsStatus.WAITING, restored.state.gps)
        assertEquals(RidePhase.ON_ROUTE, restored.state.phase)
        assertEquals(300.0, restored.state.progressM, 1.0)

        val mark = original.updates.size
        original.ride(0 to 305, 0 to 400, 400 to 400)
        restored.ride(0 to 305, 0 to 400, 400 to 400)
        val after = original.updates.drop(mark).flatMap { it.events }
        assertEquals(after, restored.events)
        assertEquals(listOf(TurnNow::class, Finished::class), after.filter { it is TurnNow || it == Finished }.map { it::class })
        assertEquals(original.state.progressM, restored.state.progressM, 1.0)
        assertEquals(original.state.rideDistanceM, restored.state.rideDistanceM, 6.0)
    }

    @Test
    fun `alerts already given are not repeated after a restore`() {
        val original = Ride(Navigator(route))
        original.ride(0 to 0, 0 to 395)
        assertEquals(1, original.count<TurnNow>())
        val restored = Ride(Navigator.restore(route, original.nav.snapshot()), nanos = original.nanos)
        restored.ride(0 to 396, 0 to 400, 400 to 400)
        assertEquals(0, restored.count<TurnNow>())
    }

    @Test
    fun `after a restart further along the route it rejoins there`() {
        val original = Ride(Navigator(route))
        original.ride(0 to 0, 0 to 100)
        // The app was killed; the rider kept going and is now 600 m in.
        val restored = Ride(Navigator.restore(route, original.nav.snapshot()), nanos = original.nanos + 200 * NANOS_PER_SECOND)
        restored.ride(200 to 400, 300 to 400)
        assertEquals(0, restored.count<OffRoute>())
        assertEquals(RidePhase.ON_ROUTE, restored.state.phase)
        assertEquals(700.0, restored.state.progressM, 2.0)
    }

    @Test
    fun `after a restart away from the route it is off route with a target ahead`() {
        val original = Ride(Navigator(route))
        original.ride(0 to 0, 0 to 100)
        val restored = Ride(Navigator.restore(route, original.nav.snapshot()), nanos = original.nanos + 60 * NANOS_PER_SECOND)
        restored.stand(-200, 250, seconds = 2.0)
        assertEquals(1, restored.count<OffRoute>())
        assertEquals(RidePhase.OFF_ROUTE, restored.state.phase)
        assertEquals(TargetKind.AHEAD, restored.state.target!!.kind)
        assertEquals(250.0, restored.state.target!!.s, 2.0)
    }

    @Test
    fun `a reversed ride restores reversed`() {
        val original = Ride(Navigator(route))
        original.ride(0 to 300, 0 to 100)
        assertEquals(1, original.count<Reversed>())
        val restored = Navigator.restore(route, original.nav.snapshot())
        assertTrue(restored.state.reversed)
        assertEquals(original.state.progressM, restored.state.progressM, 0.01)
        assertEquals(original.nav.maneuvers, restored.maneuvers)
    }

    @Test
    fun `a loop joined midway restores the same way round`() {
        val square = navRoute(0 to 0, 0 to 250, 250 to 250, 250 to 0, 0 to 0)
        val original = Ride(Navigator(square))
        original.ride(0 to 150, 0 to 250, 100 to 250)
        val restored = Ride(Navigator.restore(square, original.nav.snapshot()), nanos = original.nanos)
        restored.ride(100 to 250, 250 to 250, 250 to 0, 0 to 0, 0 to 200)
        assertEquals(1, restored.count<Finished>())
        assertEquals(original.nav.route.points, restored.nav.route.points)
    }
}
