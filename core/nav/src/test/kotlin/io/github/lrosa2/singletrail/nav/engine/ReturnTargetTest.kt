package io.github.lrosa2.singletrail.nav.engine

import io.github.lrosa2.singletrail.nav.ManeuverKind
import io.github.lrosa2.singletrail.nav.engine.NavEvent.BackOnRoute
import io.github.lrosa2.singletrail.nav.engine.NavEvent.OffRoute
import io.github.lrosa2.singletrail.nav.engine.NavEvent.TurnNow
import io.github.lrosa2.singletrail.nav.zigzag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReturnTargetTest {

    private val line = navRoute(0 to 0, 0 to 1000)

    private fun Ride.offRouteTargets(): List<NavTarget> =
        log.map { it.second.state }.filter { it.phase == RidePhase.OFF_ROUTE }.map { it.target!! }

    @Test
    fun `a short detour points back to the exit point`() {
        val ride = Ride(Navigator(line))
        ride.ride(0 to 0, 0 to 300, 100 to 300)
        val target = ride.state.target!!
        assertEquals(TargetKind.EXIT_POINT, target.kind)
        assertEquals(300.0, target.s, 3.0)
        assertEquals(100.0, target.distanceM, 3.0)
        assertEquals(270.0, target.bearingDeg, 2.0)
    }

    @Test
    fun `riding parallel to the route keeps the exit point`() {
        // The route ahead is much closer than the exit point, but the rider is not getting closer to it.
        val ride = Ride(Navigator(line))
        ride.ride(0 to 0, 0 to 300, 60 to 300)
        ride.ride(60 to 300, 60 to 420, speedMps = 2.0)
        assertTrue(ride.offRouteTargets().all { it.kind == TargetKind.EXIT_POINT })
    }

    @Test
    fun `a much closer point ahead takes over after 20 s of getting closer`() {
        val ride = Ride(Navigator(line))
        ride.ride(0 to 0, 0 to 300, 100 to 400)
        assertEquals(TargetKind.EXIT_POINT, ride.state.target!!.kind)
        val before = ride.log.size
        // Heading back towards the route well ahead of the exit point, slowly.
        ride.ride(100 to 400, 0 to 540, speedMps = 2.0)
        val states = ride.log.drop(before).map { it.second.state }
        val firstAhead = states.indexOfFirst { it.target?.kind == TargetKind.AHEAD }
        assertTrue(firstAhead > 0, "switched to a point ahead")
        val switched = states[firstAhead]
        assertTrue(switched.target!!.s > 400, "ahead target at ${switched.target!!.s}")
        // The point ahead was at most half as far as the exit point for at least 20 s before the switch.
        val closeSince = states.indexOfFirst { s ->
            s.target?.kind == TargetKind.EXIT_POINT && s.distanceToRouteM!! <= 0.5 * s.target!!.distanceM
        }
        assertTrue(closeSince >= 0 && firstAhead - closeSince >= 20, "close at $closeSince, switched at $firstAhead")
        assertEquals(1, ride.count<BackOnRoute>())
    }

    @Test
    fun `after a long detour the target is the nearest point ahead`() {
        val ride = Ride(Navigator(line))
        // Leave at 300 m and ride 400 m off route, ending level with the 500 m mark.
        ride.ride(0 to 0, 0 to 300, 150 to 300, 150 to 550, 100 to 500)
        val target = ride.state.target!!
        assertEquals(TargetKind.AHEAD, target.kind)
        assertEquals(500.0, target.s, 3.0)
        assertEquals(100.0, target.distanceM, 3.0)
    }

    @Test
    fun `overshooting a switchback points back to it, never to the leg below`() {
        val route = navRoute(zigzag(hairpins = 5))
        val nav = Navigator(route)
        val first = nav.maneuvers.single().parts.first()
        val ride = Ride(nav)
        // Up the lead-in, straight on past the first hairpin, then across above the whole stack.
        ride.ride(0 to -60, 0 to 100, 60 to 100)
        assertEquals(1, ride.count<OffRoute>())
        val targets = ride.offRouteTargets()
        assertTrue(targets.isNotEmpty())
        assertTrue(targets.all { it.s <= first.s + 1 }, "targets ${targets.map { it.s }} beyond hairpin at ${first.s}")
        assertTrue(targets.all { it.kind == TargetKind.EXIT_POINT })
    }

    @Test
    fun `a long detour after a missed switchback still never targets the legs below`() {
        val route = navRoute(zigzag(hairpins = 5))
        val nav = Navigator(route)
        val first = nav.maneuvers.single().parts.first()
        val ride = Ride(nav)
        ride.ride(0 to -60, 0 to 100, 100 to 100, 100 to 140, -60 to 140, -60 to 100, 60 to 100)
        val targets = ride.offRouteTargets()
        assertTrue(targets.any { it.kind == TargetKind.AHEAD }, "long detour reached")
        assertTrue(targets.all { it.s <= first.s + 1 }, "targets beyond hairpin at ${first.s}: ${targets.map { it.s }.distinct()}")
    }

    @Test
    fun `cutting across switchbacks never moves progress past the first one`() {
        val route = navRoute(zigzag(hairpins = 5))
        val nav = Navigator(route)
        val first = nav.maneuvers.single().parts.first()
        assertEquals(ManeuverKind.U_TURN, first.kind)
        val ride = Ride(nav)
        // Up the lead-in to mid-height, then straight across the legs.
        ride.ride(0 to -60, 0 to 20, 80 to 20)
        val maxProgress = ride.log.maxOf { it.second.state.progressM }
        assertTrue(maxProgress <= first.s + 21, "progress $maxProgress beyond hairpin at ${first.s}")
        assertEquals(1, ride.count<OffRoute>())
        assertTrue(ride.offRouteTargets().all { it.s <= first.s + 21 })
    }

    @Test
    fun `riding switchbacks properly passes them all`() {
        val ride = Ride(Navigator(navRoute(zigzag(hairpins = 5))))
        ride.ride(zigzag(hairpins = 5), noiseM = 3.0)
        assertEquals(0, ride.count<OffRoute>())
        assertEquals(RidePhase.FINISHED, ride.state.phase)
    }

    @Test
    fun `rejoining further on skips the section and its turns`() {
        // A bump to the east between 300 m and 400 m north; the rider rides straight past it.
        val route = navRoute(0 to 0, 0 to 300, 80 to 300, 80 to 400, 0 to 400, 0 to 1000)
        val ride = Ride(Navigator(route))
        ride.ride(0 to 0, 0 to 700)
        assertEquals(1, ride.count<OffRoute>())
        assertEquals(1, ride.count<BackOnRoute>())
        val skipped = ride.state.skipped.single()
        assertEquals(300.0, skipped.from, 5.0)
        assertEquals(560.0, skipped.to, 10.0)
        // Only the first corner, reached before leaving the route, was announced.
        val announced = ride.events.filterIsInstance<TurnNow>().map { it.index }
        assertEquals(listOf(0), announced)
        // 1160 m route; the rider is 300 m past the rejoin point at 560 m.
        assertEquals(1160.0 - 860.0, ride.state.distanceLeftM, 3.0)
    }
}
