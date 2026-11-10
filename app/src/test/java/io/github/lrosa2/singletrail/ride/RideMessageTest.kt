package io.github.lrosa2.singletrail.ride

import io.github.lrosa2.singletrail.nav.Maneuver
import io.github.lrosa2.singletrail.nav.ManeuverKind
import io.github.lrosa2.singletrail.nav.engine.GpsStatus
import io.github.lrosa2.singletrail.nav.engine.NavState
import io.github.lrosa2.singletrail.nav.engine.NavTarget
import io.github.lrosa2.singletrail.nav.engine.RidePhase
import io.github.lrosa2.singletrail.nav.engine.TargetKind
import io.github.lrosa2.singletrail.nav.engine.UpcomingManeuver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RideMessageTest {

    private val s = 1_000_000_000L
    private val left = Maneuver(500.0, 46.5, 7.0, ManeuverKind.TURN, -90.0)

    private fun state(
        phase: RidePhase = RidePhase.ON_ROUTE,
        gps: GpsStatus = GpsStatus.OK,
        next: UpcomingManeuver? = null,
        target: NavTarget? = null,
        lastFix: Long? = 0L,
        satellites: Int? = 7,
        reverseDeadline: Long? = null,
        distanceToRoute: Double? = 2.0,
    ) = NavState(
        phase = phase, gps = gps, satellites = satellites, lastFixNanos = lastFix, reversed = false,
        progressM = 380.0, lengthM = 2000.0, distanceLeftM = 1620.0, climbLeftM = null,
        distanceToRouteM = distanceToRoute, next = next, then = null, target = target, heading = null,
        arrowDeg = null, reverseDeadlineNanos = reverseDeadline, skipped = emptyList(),
        rideDistanceM = 0.0, movingSeconds = 0.0,
    )

    private fun target(kind: TargetKind, d: Double) = NavTarget(kind, 0.0, 46.5, 7.0, d, 90.0)

    @Test
    fun `on route it names the next maneuver and its distance`() {
        val m = RideMessage.of(state(next = UpcomingManeuver(0, left, 120.0)), 0, satellitesInView = null)
        assertEquals(RideMessage.Next(left, 120.0), m)
    }

    @Test
    fun `on route with nothing ahead it says follow the route`() {
        assertEquals(RideMessage.FollowRoute(1620.0), RideMessage.of(state(), 0, satellitesInView = null))
    }

    @Test
    fun `waiting for GPS shows the satellite count`() {
        val m = RideMessage.of(state(phase = RidePhase.WAITING_GPS, gps = GpsStatus.WAITING, lastFix = null, satellites = null), 0, satellitesInView = 9)
        assertEquals(RideMessage.WaitingForGps(9), m)
    }

    @Test
    fun `acquiring shows how far away the route is`() {
        val m = RideMessage.of(state(phase = RidePhase.ACQUIRING, target = target(TargetKind.NEAREST_POINT, 230.0)), 0, null)
        assertEquals(RideMessage.FindingRoute(230.0), m)
    }

    @Test
    fun `off route shows the distance from the route`() {
        val m = RideMessage.of(state(phase = RidePhase.OFF_ROUTE, distanceToRoute = 38.0, target = target(TargetKind.EXIT_POINT, 40.0)), 0, null)
        assertEquals(RideMessage.OffRoute(38.0), m)
    }

    @Test
    fun `lost GPS wins over everything but the finish`() {
        val m = RideMessage.of(state(phase = RidePhase.OFF_ROUTE, gps = GpsStatus.LOST, lastFix = 0L), 40 * s, null)
        assertEquals(RideMessage.GpsLost(40), m)
        val done = RideMessage.of(state(phase = RidePhase.FINISHED, gps = GpsStatus.LOST), 40 * s, null)
        assertEquals(RideMessage.Finished, done)
    }

    @Test
    fun `the reverse prompt counts down`() {
        val m = RideMessage.of(state(phase = RidePhase.REVERSE_PENDING, reverseDeadline = 10 * s), 3 * s + 1, null)
        assertEquals(RideMessage.ReversePrompt(7), m)
    }
}
