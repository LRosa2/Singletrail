package io.github.lrosa2.singletrail.nav

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BearingTest {

    private fun bearing(from: RoutePoint, to: RoutePoint) = Geo.bearingDeg(from.lat, from.lon, to.lat, to.lon)

    @Test
    fun `bearings run clockwise from north`() {
        assertEquals(0.0, bearing(at(0, 0), at(0, 100)), 0.01)
        assertEquals(90.0, bearing(at(0, 0), at(100, 0)), 0.01)
        assertEquals(180.0, bearing(at(0, 0), at(0, -100)), 0.01)
        assertEquals(270.0, bearing(at(0, 0), at(-100, 0)), 0.01)
        assertEquals(45.0, bearing(at(0, 0), at(100, 100)), 0.1)
    }

    @Test
    fun `turn angles are signed and take the short way round`() {
        assertEquals(20.0, Geo.turnDeg(350.0, 10.0), 1e-9)
        assertEquals(-20.0, Geo.turnDeg(10.0, 350.0), 1e-9)
        assertEquals(90.0, Geo.turnDeg(0.0, 90.0), 1e-9)
        assertEquals(-90.0, Geo.turnDeg(90.0, 0.0), 1e-9)
        assertEquals(180.0, Geo.turnDeg(0.0, 180.0), 1e-9)
        assertEquals(0.0, Geo.turnDeg(123.0, 123.0), 1e-9)
    }
}
