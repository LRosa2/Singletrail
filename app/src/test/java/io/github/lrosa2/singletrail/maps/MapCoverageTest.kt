package io.github.lrosa2.singletrail.maps

import io.github.lrosa2.singletrail.nav.RoutePosition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MapCoverageTest {
    // About 1 km due north from (46.0, 7.0), in 10 steps of 100 m.
    private val metersPerDegree = 111_195.0
    private val route = (0..10).map { i -> RoutePosition(i * 100.0, 46.0 + i * 100 / metersPerDegree, 7.0) }
    private val north = MapArea(minLat = 46.0045, minLon = 6.9, maxLat = 47.0, maxLon = 7.1)
    private val south = MapArea(minLat = 45.0, minLon = 6.9, maxLat = 46.0045, maxLon = 7.1)

    @Test
    fun `a route inside one map is fully covered`() {
        assertEquals(0.0, MapCoverage.uncoveredMeters(route, listOf(MapArea(45.0, 6.0, 47.0, 8.0))))
    }

    @Test
    fun `without maps the whole route is uncovered`() {
        assertEquals(1000.0, MapCoverage.uncoveredMeters(route, emptyList()), 1e-6)
    }

    @Test
    fun `a route crossing the edge of the only map is partly uncovered`() {
        // The northern map starts about 500 m along the route.
        assertEquals(500.0, MapCoverage.uncoveredMeters(route, listOf(north)), 30.0)
    }

    @Test
    fun `two neighbouring maps cover a route across their border`() {
        assertEquals(0.0, MapCoverage.uncoveredMeters(route, listOf(north, south)))
    }

    @Test
    fun `a map beside the route doesn't count`() {
        assertEquals(1000.0, MapCoverage.uncoveredMeters(route, listOf(MapArea(45.0, 7.5, 47.0, 8.0))), 1e-6)
    }

    @Test
    fun `a route of one point has nothing to cover`() {
        assertEquals(0.0, MapCoverage.uncoveredMeters(route.take(1), emptyList()))
    }
}
