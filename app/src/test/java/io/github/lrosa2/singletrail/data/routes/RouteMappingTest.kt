package io.github.lrosa2.singletrail.data.routes

import io.github.lrosa2.singletrail.Plane
import io.github.lrosa2.singletrail.line
import io.github.lrosa2.singletrail.newRoute
import io.github.lrosa2.singletrail.track
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RouteMappingTest {

    /** 1 km east then 500 m north, climbing from 500 m to 600 m. */
    private val hill = newRoute(track("Hill", line(0 to 0, 1000 to 0, 1000 to 500, ele = { 500 + it / 15 }), "Steep"))

    @Test
    fun `a new route row takes its stats from the prepared route`() {
        val row = hill.toEntity(id = "id1", gpxPath = "routes/id1.gpx", now = 1234L)

        assertEquals("id1", row.id)
        assertEquals("Hill", row.name)
        assertEquals("Steep", row.description)
        assertEquals("routes/id1.gpx", row.gpxPath)
        assertEquals(hill.track.points.size, row.pointCount)
        assertEquals(1500.0, row.distanceM, 1.0)
        assertEquals(100.0, row.ascentM!!, 0.5)
        assertEquals(0.0, row.descentM!!, 0.5)
        assertEquals(500.0, row.minEleM!!, 0.5)
        assertEquals(600.0, row.maxEleM!!, 0.5)
        assertTrue(row.hasElevation)
        assertFalse(row.isLoop)
        assertEquals(hill.hashes.forward, row.geometryHash)
        assertEquals(hill.hashes.reversed, row.geometryHashReversed)
        assertEquals(Plane.lat(0.0), row.startLat, 1e-9)
        assertEquals(Plane.lat(500.0), row.endLat, 1e-9)
        assertEquals(Plane.lon(1000.0), row.endLon, 1e-9)
        assertEquals(Plane.lat(0.0), row.minLat, 1e-9)
        assertEquals(Plane.lat(500.0), row.maxLat, 1e-9)
        assertEquals(Plane.lon(0.0), row.minLon, 1e-9)
        assertEquals(Plane.lon(1000.0), row.maxLon, 1e-9)
        assertNull(row.rating)
        assertEquals(1234L, row.importedAt)
        assertEquals(1234L, row.updatedAt)
        assertNull(row.lastRiddenAt)
        assertEquals(0, row.turnsAlgoVersion)
    }

    @Test
    fun `a track without elevation has no climb`() {
        val flat = newRoute(track("Flat", line(0 to 0, 1000 to 0)))
        val row = flat.toEntity("id2", "routes/id2.gpx", now = 0L)

        assertFalse(row.hasElevation)
        assertNull(row.ascentM)
        assertNull(row.descentM)
        assertNull(row.minEleM)
        assertNull(row.maxEleM)
    }

    @Test
    fun `replacing the geometry keeps name, description, stars and history`() {
        val existing = hill.toEntity("id1", "routes/id1.gpx", now = 1000L)
            .copy(name = "My hill", description = "Mine", rating = 4, lastRiddenAt = 1500L, turnsAlgoVersion = 3)
        val longer = newRoute(track("Hill v2", line(0 to 0, 1000 to 0, 1000 to 800), "New text"))

        val row = longer.replacing(existing, now = 2000L)

        assertEquals("id1", row.id)
        assertEquals("My hill", row.name)
        assertEquals("Mine", row.description)
        assertEquals(4, row.rating)
        assertEquals(1000L, row.importedAt)
        assertEquals(1500L, row.lastRiddenAt)
        assertEquals("routes/id1.gpx", row.gpxPath)
        assertEquals(1800.0, row.distanceM, 1.0)
        assertEquals(longer.hashes.forward, row.geometryHash)
        assertEquals(2000L, row.updatedAt)
        // Maneuvers must be recomputed for the new geometry.
        assertEquals(0, row.turnsAlgoVersion)
    }
}
