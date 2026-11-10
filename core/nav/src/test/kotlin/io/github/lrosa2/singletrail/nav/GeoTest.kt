package io.github.lrosa2.singletrail.nav

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GeoTest {
    @Test
    fun `zero distance for identical points`() {
        assertEquals(0.0, Geo.distanceMeters(46.5, 7.0, 46.5, 7.0), 1e-9)
    }

    @Test
    fun `one degree of latitude is about 111 km`() {
        assertEquals(111_195.0, Geo.distanceMeters(46.0, 7.0, 47.0, 7.0), 50.0)
    }

    @Test
    fun `short trail-scale distance is accurate`() {
        // ~100 m east at 46.5 N: 100 / (111195 * cos(46.5)) degrees of longitude
        val dLon = 100.0 / (111_195.0 * Math.cos(Math.toRadians(46.5)))
        assertEquals(100.0, Geo.distanceMeters(46.5, 7.0, 46.5, 7.0 + dLon), 0.2)
    }
}
