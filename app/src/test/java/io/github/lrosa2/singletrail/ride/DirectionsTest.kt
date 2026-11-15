package io.github.lrosa2.singletrail.ride

import io.github.lrosa2.singletrail.nav.engine.GpsStatus
import io.github.lrosa2.singletrail.nav.engine.LatLon
import io.github.lrosa2.singletrail.nav.engine.NavState
import io.github.lrosa2.singletrail.nav.engine.NavTarget
import io.github.lrosa2.singletrail.nav.engine.RidePhase
import io.github.lrosa2.singletrail.nav.engine.TargetKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.Locale

class DirectionsTest {

    private fun state(phase: RidePhase, target: NavTarget?) = NavState(
        phase = phase, gps = GpsStatus.OK, satellites = 7, lastFixNanos = 0L, reversed = false,
        progressM = 380.0, lengthM = 2000.0, distanceLeftM = 1620.0, climbLeftM = null,
        distanceToRouteM = 400.0, next = null, then = null, target = target, heading = null,
        arrowDeg = null, reverseDeadlineNanos = null, skipped = emptyList(),
        rideDistanceM = 0.0, movingSeconds = 0.0,
    )

    private fun target(kind: TargetKind) = NavTarget(kind, 120.0, 47.4245, 9.3767, 400.0, 90.0)

    @Test
    fun `finding the route sends its nearest point`() {
        assertEquals(LatLon(47.4245, 9.3767), Directions.pointOf(state(RidePhase.ACQUIRING, target(TargetKind.NEAREST_POINT))))
    }

    @Test
    fun `off route sends the point the arrow leads back to`() {
        assertEquals(LatLon(47.4245, 9.3767), Directions.pointOf(state(RidePhase.OFF_ROUTE, target(TargetKind.AHEAD))))
        assertEquals(LatLon(47.4245, 9.3767), Directions.pointOf(state(RidePhase.OFF_ROUTE, target(TargetKind.EXIT_POINT))))
    }

    @Test
    fun `on the route there is nothing to send`() {
        assertNull(Directions.pointOf(state(RidePhase.ON_ROUTE, target(TargetKind.LOOK_AHEAD))))
        assertNull(Directions.pointOf(state(RidePhase.REVERSE_PENDING, target(TargetKind.LOOK_AHEAD))))
        assertNull(Directions.pointOf(state(RidePhase.FINISHED, null)))
    }

    @Test
    fun `before the first fix there is nothing to send`() {
        assertNull(Directions.pointOf(state(RidePhase.ACQUIRING, null)))
        assertNull(Directions.pointOf(state(RidePhase.WAITING_GPS, null)))
    }

    @Test
    fun `the link puts a pin on the point labelled with the route`() {
        assertEquals(
            "geo:47.424500,9.376700?q=47.424500,9.376700(Kaien-Route%20%C2%B7%20Heiden%20loop)",
            Directions.link(LatLon(47.4245, 9.3767), "Kaien-Route · Heiden loop"),
        )
    }

    @Test
    fun `the link uses a decimal point in every language`() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("geo:-0.500000,-71.250000?q=-0.500000,-71.250000(A)", Directions.link(LatLon(-0.5, -71.25), "A"))
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test
    fun `brackets in the name cannot end the label early`() {
        assertEquals(
            "geo:47.000000,9.000000?q=47.000000,9.000000(Loop%20%28short%29%20%26%20more)",
            Directions.link(LatLon(47.0, 9.0), "Loop (short) & more"),
        )
    }
}
