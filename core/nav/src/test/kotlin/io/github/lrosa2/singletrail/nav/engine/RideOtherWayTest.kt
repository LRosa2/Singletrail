package io.github.lrosa2.singletrail.nav.engine

import io.github.lrosa2.singletrail.nav.engine.NavEvent.BackOnRoute
import io.github.lrosa2.singletrail.nav.engine.NavEvent.OffRoute
import io.github.lrosa2.singletrail.nav.engine.NavEvent.ReversePrompt
import io.github.lrosa2.singletrail.nav.engine.NavEvent.Reversed
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The ride screen's "Ride the other way" (D50): the rider turns the route round by hand. */
class RideOtherWayTest {

    private val line = navRoute(0 to 0, 0 to 1000)

    @Test
    fun `before joining it turns the route round`() {
        val ride = Ride(Navigator(line))
        ride.stand(60, 300, seconds = 3.0)
        assertEquals(RidePhase.ACQUIRING, ride.state.phase)
        val u = ride.nav.rideOtherWay(ride.nanos)
        assertEquals(listOf<NavEvent>(Reversed), u.events)
        assertTrue(u.state.reversed)
        assertEquals(RidePhase.ACQUIRING, u.state.phase)
        // Joins at 300, which is 700 m into the turned route, and rides it south without a prompt.
        ride.ride(60 to 300, 0 to 300, 0 to 100)
        assertEquals(0, ride.count<ReversePrompt>())
        assertEquals(900.0, ride.state.progressM, 5.0)
    }

    @Test
    fun `on the route it switches at once`() {
        val ride = Ride(Navigator(line))
        ride.ride(0 to 400, 0 to 500)
        val u = ride.nav.rideOtherWay(ride.nanos)
        assertEquals(listOf<NavEvent>(Reversed), u.events)
        assertTrue(u.state.reversed)
        assertEquals(RidePhase.ON_ROUTE, u.state.phase)
        assertEquals(500.0, u.state.progressM, 3.0)
        ride.ride(0 to 500, 0 to 300)
        assertEquals(0, ride.count<ReversePrompt>())
        assertEquals(700.0, ride.state.progressM, 3.0)
    }

    @Test
    fun `off route beside the route it is back on the route at once`() {
        // A detour beside the route, then back onto it at 700 heading for the start.
        val ride = Ride(Navigator(line))
        ride.ride(0 to 0, 0 to 300, 100 to 300, 100 to 700, 0 to 700, 0 to 680)
        assertEquals(RidePhase.OFF_ROUTE, ride.state.phase)
        val u = ride.nav.rideOtherWay(ride.nanos)
        assertEquals(listOf<NavEvent>(Reversed), u.events)
        assertEquals(RidePhase.ON_ROUTE, u.state.phase)
        assertEquals(320.0, u.state.progressM, 5.0)
        assertEquals(TargetKind.LOOK_AHEAD, u.state.target?.kind)
        ride.ride(0 to 680, 0 to 500)
        assertEquals(1, ride.count<OffRoute>())
        assertEquals(0, ride.count<ReversePrompt>())
        assertEquals(500.0, ride.state.progressM, 5.0)
    }

    @Test
    fun `off route away from the route it rejoins riding the new way`() {
        val ride = Ride(Navigator(line))
        ride.ride(0 to 0, 0 to 300, 100 to 300, 100 to 500)
        assertEquals(RidePhase.OFF_ROUTE, ride.state.phase)
        val u = ride.nav.rideOtherWay(ride.nanos)
        assertEquals(listOf<NavEvent>(Reversed), u.events)
        assertEquals(RidePhase.OFF_ROUTE, u.state.phase)
        assertTrue(u.state.reversed)
        ride.ride(100 to 500, 0 to 500, 0 to 300)
        assertEquals(1, ride.count<BackOnRoute>())
        assertEquals(RidePhase.ON_ROUTE, ride.state.phase)
        assertEquals(700.0, ride.state.progressM, 5.0)
    }

    @Test
    fun `a loop turns round where it was joined`() {
        // Clockwise square joined at its start; turned round, the rider sets off anticlockwise.
        val square = navRoute(0 to 0, 0 to 250, 250 to 250, 250 to 0, 0 to 0)
        val ride = Ride(Navigator(square))
        ride.ride(0 to 0, 10 to 0)
        ride.nav.rideOtherWay(ride.nanos)
        ride.ride(10 to 0, 250 to 0, 250 to 50)
        assertEquals(0, ride.count<ReversePrompt>())
        assertTrue(ride.state.reversed)
        assertEquals(300.0, ride.state.progressM, 5.0)
    }

    @Test
    fun `turning round just past a loop's join point rides the whole lap the other way`() {
        // Emulator find: 10 m into the lap, turning round put the rider 10 m from the finish, and it finished.
        val square = navRoute(0 to 0, 0 to 250, 250 to 250, 250 to 0, 0 to 0)
        val ride = Ride(Navigator(square))
        ride.ride(0 to 0, 0 to 10)
        val u = ride.nav.rideOtherWay(ride.nanos)
        assertEquals(listOf<NavEvent>(Reversed), u.events)
        assertEquals(RidePhase.ON_ROUTE, u.state.phase)
        assertEquals(0.0, u.state.progressM, 3.0)
        assertEquals(1000.0, u.state.distanceLeftM, 3.0)
        ride.ride(0 to 10, 0 to 0, 250 to 0)
        assertEquals(0, ride.count<ReversePrompt>())
        assertEquals(RidePhase.ON_ROUTE, ride.state.phase)
        assertEquals(260.0, ride.state.progressM, 5.0)
    }

    @Test
    fun `turning round twice near a loop's join point keeps riding`() {
        val square = navRoute(0 to 0, 0 to 250, 250 to 250, 250 to 0, 0 to 0)
        val ride = Ride(Navigator(square))
        ride.ride(0 to 0, 0 to 10)
        ride.nav.rideOtherWay(ride.nanos)
        ride.stand(0, 12, seconds = 2.0)
        val u = ride.nav.rideOtherWay(ride.nanos)
        assertEquals(RidePhase.ON_ROUTE, u.state.phase)
        assertTrue(!u.state.reversed)
        assertEquals(1000.0, u.state.distanceLeftM, 15.0)
    }

    @Test
    fun `a finished ride stays finished`() {
        val ride = Ride(Navigator(line))
        ride.ride(0 to 0, 0 to 1000)
        assertEquals(RidePhase.FINISHED, ride.state.phase)
        val u = ride.nav.rideOtherWay(ride.nanos)
        assertEquals(emptyList<NavEvent>(), u.events)
        assertEquals(RidePhase.FINISHED, u.state.phase)
    }
}
