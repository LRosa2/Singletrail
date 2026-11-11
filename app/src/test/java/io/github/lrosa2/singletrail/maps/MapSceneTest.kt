package io.github.lrosa2.singletrail.maps

import io.github.lrosa2.singletrail.Plane
import io.github.lrosa2.singletrail.line
import io.github.lrosa2.singletrail.nav.Maneuver
import io.github.lrosa2.singletrail.nav.ManeuverKind
import io.github.lrosa2.singletrail.nav.PreparedRoute
import io.github.lrosa2.singletrail.nav.RoutePoint
import io.github.lrosa2.singletrail.nav.engine.GpsStatus
import io.github.lrosa2.singletrail.nav.engine.Heading
import io.github.lrosa2.singletrail.nav.engine.HeadingSource
import io.github.lrosa2.singletrail.nav.engine.LatLon
import io.github.lrosa2.singletrail.nav.engine.NavState
import io.github.lrosa2.singletrail.nav.engine.NavTarget
import io.github.lrosa2.singletrail.nav.engine.RidePhase
import io.github.lrosa2.singletrail.nav.engine.TargetKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MapSceneTest {

    /** 1 km straight north, a point every 10 m. */
    private val route = PreparedRoute.prepare(line(0 to 0, 0 to 1000).map { RoutePoint(it.lat, it.lon, null) })
    private val cuts = RouteCuts(route)

    @Test
    fun `a stretch starts and ends exactly at the given distances, with the route's own points in between`() {
        val stretch = cuts.stretch(15.0, 42.0)!!
        assertEquals(15.0, stretch.start.s, 1e-6)
        assertEquals(42.0, stretch.end.s, 1e-6)
        // The points at 20, 30 and 40 m.
        assertEquals(2, stretch.from)
        assertEquals(5, stretch.to)
    }

    @Test
    fun `a stretch that starts or ends on a point does not draw it twice`() {
        val stretch = cuts.stretch(route.distanceAt(2), route.distanceAt(4))!!
        assertEquals(3, stretch.from)
        assertEquals(4, stretch.to)
    }

    @Test
    fun `stretches are clamped to the route, and an empty one is null`() {
        assertNull(cuts.stretch(500.0, 500.0))
        assertNull(cuts.stretch(1200.0, 1500.0))
        val all = cuts.stretch(-5.0, 2000.0)!!
        assertEquals(0.0, all.start.s, 1e-9)
        assertEquals(route.lengthMeters, all.end.s, 1e-9)
        assertEquals(1, all.from)
        assertEquals(route.points.size - 1, all.to)
    }

    private val here = LatLon(Plane.lat(100.0), Plane.lon(0.0))

    private fun nav(
        phase: RidePhase = RidePhase.ON_ROUTE,
        gps: GpsStatus = GpsStatus.OK,
        heading: Heading? = Heading(30.0, HeadingSource.GPS),
        position: LatLon? = here,
        target: NavTarget? = null,
        progress: Double = 100.0,
    ) = NavState(
        phase = phase, gps = gps, satellites = 9, lastFixNanos = 0, reversed = false,
        progressM = progress, lengthM = 1000.0, distanceLeftM = 1000.0 - progress, climbLeftM = null, distanceToRouteM = 2.0,
        next = null, then = null, target = target, heading = heading,
        arrowDeg = 0.0, reverseDeadlineNanos = null, skipped = emptyList(), rideDistanceM = 0.0,
        movingSeconds = 0.0, speedMps = 5.0, position = position,
    )

    private fun target(kind: TargetKind) = NavTarget(kind, 150.0, Plane.lat(150.0), Plane.lon(0.0), 50.0, 0.0)

    @Test
    fun `heading-up follows the rider and turns the map with the heading, the rider arrow points straight up`() {
        val scene = MapScene.of(nav(), emptyList(), northUpChosen = false)
        val camera = scene.camera!!
        assertEquals(here.lat, camera.lat, 1e-12)
        assertEquals(here.lon, camera.lon, 1e-12)
        assertEquals(30.0, camera.bearingDeg)
        assertTrue(camera.headingUp)
        assertEquals(0.0, scene.riderArrowDeg)
        assertFalse(scene.riderInactive)
    }

    @Test
    fun `north-up when chosen keeps north up and the rider arrow shows the heading`() {
        val scene = MapScene.of(nav(), emptyList(), northUpChosen = true)
        assertEquals(0.0, scene.camera!!.bearingDeg)
        assertFalse(scene.camera!!.headingUp)
        assertEquals(30.0, scene.riderArrowDeg)
    }

    @Test
    fun `a frozen heading turns the map north-up and greys the rider (spec 5_9)`() {
        val scene = MapScene.of(nav(heading = Heading(30.0, HeadingSource.FROZEN)), emptyList(), northUpChosen = false)
        assertFalse(scene.camera!!.headingUp)
        assertEquals(0.0, scene.camera!!.bearingDeg)
        assertTrue(scene.riderInactive)
    }

    @Test
    fun `without a heading the map is north-up and the rider is a dot`() {
        val scene = MapScene.of(nav(heading = null), emptyList(), northUpChosen = false)
        assertFalse(scene.camera!!.headingUp)
        assertNull(scene.riderArrowDeg)
    }

    @Test
    fun `with GPS lost the rider is grey at the last fix`() {
        val scene = MapScene.of(nav(gps = GpsStatus.LOST), emptyList(), northUpChosen = false)
        assertTrue(scene.riderInactive)
        assertEquals(here, scene.rider)
    }

    @Test
    fun `before the first fix there is no camera, so the map shows the whole route`() {
        val scene = MapScene.of(nav(phase = RidePhase.WAITING_GPS, position = null, heading = null), emptyList(), northUpChosen = false)
        assertNull(scene.camera)
        assertNull(scene.rider)
    }

    @Test
    fun `a straight line to the target off the route and while acquiring, not to the look-ahead point`() {
        listOf(TargetKind.EXIT_POINT, TargetKind.AHEAD, TargetKind.NEAREST_POINT).forEach { kind ->
            val scene = MapScene.of(nav(target = target(kind)), emptyList(), northUpChosen = false)
            assertEquals(LatLon(Plane.lat(150.0), Plane.lon(0.0)), scene.target, kind.name)
        }
        assertNull(MapScene.of(nav(target = target(TargetKind.LOOK_AHEAD)), emptyList(), northUpChosen = false).target)
    }

    @Test
    fun `with a frozen heading the line goes to the look-ahead point too (compass off, spec 5_9)`() {
        val frozen = nav(heading = Heading(30.0, HeadingSource.FROZEN), target = target(TargetKind.LOOK_AHEAD))
        assertNotNull(MapScene.of(frozen, emptyList(), northUpChosen = false).target)
    }

    @Test
    fun `turn markers for what is still ahead, each turn of a switchback group on its own`() {
        fun at(s: Double, kind: ManeuverKind = ManeuverKind.TURN, parts: List<Maneuver> = emptyList()) =
            Maneuver(s, Plane.lat(s), Plane.lon(0.0), kind, 90.0, parts)
        val switchbacks = at(300.0, ManeuverKind.SWITCHBACKS, listOf(at(300.0, ManeuverKind.SHARP), at(340.0, ManeuverKind.SHARP), at(380.0, ManeuverKind.SHARP)))
        val maneuvers = listOf(at(50.0), switchbacks, at(600.0))

        val before = MapScene.of(nav(progress = 100.0), maneuvers, northUpChosen = false).turns
        assertEquals(listOf(300.0, 340.0, 380.0, 600.0).map { LatLon(Plane.lat(it), Plane.lon(0.0)) }, before)

        val inside = MapScene.of(nav(progress = 350.0), maneuvers, northUpChosen = false).turns
        assertEquals(listOf(380.0, 600.0).map { LatLon(Plane.lat(it), Plane.lon(0.0)) }, inside)
    }
}
