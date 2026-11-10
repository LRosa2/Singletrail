package io.github.lrosa2.singletrail.nav

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.math.abs

class PreparedRouteTest {

    private fun prepare(vararg points: RoutePoint) = PreparedRoute.prepare(points.toList())

    @Test
    fun `cumulative distance follows the points`() {
        val r = prepare(at(0, 0), at(100, 0), at(100, 50))
        assertEquals(0.0, r.distanceAt(0))
        assertEquals(100.0, r.distanceAt(1), 0.05)
        assertEquals(150.0, r.distanceAt(2), 0.05)
        assertEquals(150.0, r.lengthMeters, 0.05)
    }

    @Test
    fun `points within a metre of the last kept point are dropped`() {
        // 0.4 and 0.8 are within 1 m of the start; 1.2 is not.
        val r = prepare(at(0, 0), at(0.4, 0), at(0.8, 0), at(1.2, 0), at(10, 0))
        assertEquals(listOf(0.0, 1.2, 10.0), r.points.map { Math.round(Plane.x(it.lon) * 100) / 100.0 })
    }

    @Test
    fun `a stop in the middle collapses to one point`() {
        val r = prepare(at(0, 0), at(50, 0), at(50.3, 0.2), at(49.8, -0.1), at(50.2, 0.1), at(100, 0))
        assertEquals(3, r.points.size)
        assertEquals(100.0, r.lengthMeters, 0.05)
    }

    @Test
    fun `kept points keep their elevation`() {
        val r = prepare(at(0, 0, 500), at(0.5, 0, 999), at(20, 0, 510))
        assertEquals(listOf(500.0, 510.0), r.points.map { it.elevation })
    }

    @Test
    fun `a route needs two points at least a metre apart`() {
        assertThrows<IllegalArgumentException> { PreparedRoute.prepare(emptyList()) }
        assertThrows<IllegalArgumentException> { prepare(at(0, 0)) }
        assertThrows<IllegalArgumentException> { prepare(at(0, 0), at(0.5, 0), at(0.2, 0.3)) }
    }

    @Test
    fun `start and end less than 50 m apart make a loop`() {
        assertTrue(prepare(at(0, 0), at(200, 0), at(200, 200), at(0, 200), at(0, 40)).isLoop)
    }

    @Test
    fun `start and end 60 m apart are not a loop`() {
        assertFalse(prepare(at(0, 0), at(200, 0), at(200, 200), at(0, 200), at(0, 60)).isLoop)
    }

    @Test
    fun `samples are every 5 m and end at the finish`() {
        val r = prepare(at(0, 0), at(23, 0))
        val s = r.samples.map { it.s }
        assertEquals(listOf(0.0, 5.0, 10.0, 15.0, 20.0, r.lengthMeters), s)
    }

    @Test
    fun `no sample squeezed in right before the finish`() {
        // A regular sample at 20 m would sit only 0.3 m before the finish.
        val r = prepare(at(0, 0), at(20.3, 0))
        assertEquals(listOf(0.0, 5.0, 10.0, 15.0, r.lengthMeters), r.samples.map { it.s })
    }

    @Test
    fun `samples follow corners`() {
        val r = prepare(at(0, 0), at(10, 0), at(10, 10))
        val p = r.samples.single { abs(it.s - 15.0) < 1e-6 }
        assertEquals(Plane.lat(5.0), p.lat, 1e-7)
        assertEquals(Plane.lon(10.0), p.lon, 1e-7)
    }

    @Test
    fun `positionAt interpolates along the route and clamps to its ends`() {
        val r = prepare(at(0, 0), at(100, 0))
        val mid = r.positionAt(25.0)
        assertEquals(25.0, mid.s)
        assertEquals(Plane.lat(0.0), mid.lat, 1e-7)
        assertEquals(Plane.lon(25.0), mid.lon, 1e-7)
        assertEquals(0.0, r.positionAt(-5.0).s)
        val end = r.positionAt(1e6)
        assertEquals(r.lengthMeters, end.s)
        assertEquals(Plane.lon(100.0), end.lon, 1e-9)
    }

    @Test
    fun `reversed route runs from the finish to the start`() {
        val r = prepare(at(0, 0), at(100, 0), at(100, 50))
        val rev = r.reversed()
        assertEquals(r.lengthMeters, rev.lengthMeters, 1e-9)
        assertEquals(r.points.last(), rev.points.first())
        assertEquals(r.points.first(), rev.points.last())
    }

    @Test
    fun `sample spacing must be positive`() {
        assertThrows<IllegalArgumentException> { RoutePrepConfig(sampleSpacingMeters = 0.0) }
    }
}
