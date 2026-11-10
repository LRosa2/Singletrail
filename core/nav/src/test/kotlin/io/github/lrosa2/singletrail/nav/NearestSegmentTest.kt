package io.github.lrosa2.singletrail.nav

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class NearestSegmentTest {

    /** 100 m east, then 100 m north. */
    private val elbow = PreparedRoute.prepare(listOf(at(0, 0), at(100, 0), at(100, 100)))

    private fun PreparedRoute.nearest(x: Number, y: Number, radius: Double) =
        nearestSegment(Plane.lat(y.toDouble()), Plane.lon(x.toDouble()), radius)

    @Test
    fun `reports distance and progress along the route`() {
        val hit = elbow.nearest(40, 12, 50.0)!!
        assertEquals(0, hit.segment)
        assertEquals(12.0, hit.distanceMeters, 0.05)
        assertEquals(40.0, hit.s, 0.05)
    }

    @Test
    fun `picks the closer leg`() {
        val hit = elbow.nearest(90, 20, 50.0)!!
        assertEquals(1, hit.segment)
        assertEquals(10.0, hit.distanceMeters, 0.05)
        assertEquals(120.0, hit.s, 0.05)
    }

    @Test
    fun `beyond the finish projects onto the last point`() {
        val hit = elbow.nearest(100, 130, 50.0)!!
        assertEquals(30.0, hit.distanceMeters, 0.05)
        assertEquals(elbow.lengthMeters, hit.s, 1e-9)
    }

    @Test
    fun `returns null when nothing is within the radius`() {
        // 60 m from the second leg, 80 m from the first.
        assertNull(elbow.nearest(40, 80, 50.0))
        assertEquals(60.0, elbow.nearest(40, 80, 100.0)!!.distanceMeters, 0.05)
    }

    @Test
    fun `finds the middle of a long straight segment`() {
        val r = PreparedRoute.prepare(listOf(at(0, 0), at(5000, 0)))
        val hit = r.nearest(2500, 20, 50.0)!!
        assertEquals(20.0, hit.distanceMeters, 0.05)
        assertEquals(2500.0, hit.s, 0.5)
    }

    @Test
    fun `grid search agrees with a brute-force scan`() {
        val rnd = Random(42)
        // A 400-point random walk with steps of 5-40 m that crosses itself.
        var x = 0.0
        var y = 0.0
        var heading = 0.0
        val walk = ArrayList<RoutePoint>()
        repeat(400) {
            walk += at(x, y)
            heading += rnd.nextDouble(-1.0, 1.0)
            val step = rnd.nextDouble(5.0, 40.0)
            x += step * cos(heading)
            y += step * sin(heading)
        }
        val r = PreparedRoute.prepare(walk)
        val pts = r.points
        repeat(500) {
            val qLat = Plane.lat(rnd.nextDouble(-1500.0, 1500.0))
            val qLon = Plane.lon(rnd.nextDouble(-1500.0, 1500.0))
            val expected = (0 until pts.size - 1)
                .minOf { i -> LocalPlane.projectOntoSegment(qLat, qLon, pts[i], pts[i + 1]).distanceMeters }
                .takeIf { it <= 250.0 }
            val actual = r.nearestSegment(qLat, qLon, 250.0)?.distanceMeters
            if (expected == null) assertNull(actual) else assertEquals(expected, actual!!, 1e-9)
        }
    }

    @Test
    fun `a route across the antimeridian stays continuous`() {
        val r = PreparedRoute.prepare(listOf(RoutePoint(60.0, 179.999), RoutePoint(60.0, -179.999)))
        assertEquals(111.2, r.lengthMeters, 0.1)
        assertEquals(180.0, abs(r.positionAt(r.lengthMeters / 2).lon), 1e-6)
        val hit = r.nearestSegment(60.0001, 180.0, 50.0)!!
        assertEquals(11.1, hit.distanceMeters, 0.1)
        assertEquals(r.lengthMeters / 2, hit.s, 0.1)
    }
}
