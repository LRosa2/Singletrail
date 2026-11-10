package io.github.lrosa2.singletrail.nav

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import kotlin.random.Random

class ElevationTest {

    // --- moving average -------------------------------------------------------------------------

    @Test
    fun `moving average keeps a steady slope`() {
        val ramp = DoubleArray(20) { it * 2.0 }
        assertArrayEquals(ramp, Elevation.movingAverage(ramp, 3), 1e-9)
    }

    @Test
    fun `moving average spreads a spike`() {
        val v = DoubleArray(11).also { it[5] = 10.0 }
        val s = Elevation.movingAverage(v, 2)
        assertEquals(2.0, s[5], 1e-9)
        assertEquals(2.0, s[3], 1e-9)
        assertEquals(0.0, s[2], 1e-9)
    }

    @Test
    fun `moving average keeps the end values`() {
        val s = Elevation.movingAverage(doubleArrayOf(10.0, 0.0, 0.0, 0.0, 0.0, 20.0), 2)
        assertEquals(10.0, s[0])
        assertEquals(20.0, s[5])
    }

    // --- 5 m hysteresis -------------------------------------------------------------------------

    private fun climb(vararg v: Double) = Elevation.climb(v, 5.0)

    @Test
    fun `wiggles under 5 m are not climbing`() {
        val c = climb(0.0, 4.0, 0.0, 4.0, 0.0)
        assertEquals(0.0, c.ascent)
        assertEquals(0.0, c.descent)
    }

    @Test
    fun `exactly 5 m counts`() {
        assertEquals(5.0, climb(0.0, 5.0).ascent)
    }

    @Test
    fun `a drop of 5 m or more splits a climb`() {
        val c = climb(0.0, 10.0, 3.0, 20.0)
        assertEquals(27.0, c.ascent)
        assertEquals(7.0, c.descent)
    }

    @Test
    fun `a dip under 5 m does not split a climb`() {
        val c = climb(0.0, 10.0, 6.0, 20.0)
        assertEquals(20.0, c.ascent)
        assertEquals(0.0, c.descent)
    }

    @Test
    fun `a climb counts from its lowest point`() {
        assertEquals(23.0, climb(0.0, -3.0, 20.0).ascent)
    }

    @Test
    fun `cumulative climb grows along the way`() {
        val c = climb(0.0, 10.0, 3.0, 20.0)
        assertArrayEquals(doubleArrayOf(0.0, 10.0, 10.0, 27.0), c.cumulativeAscent)
        assertArrayEquals(doubleArrayOf(0.0, 0.0, 7.0, 7.0), c.cumulativeDescent)
    }

    // --- profile of a prepared route ------------------------------------------------------------

    /** Up 100 m over 1 km, 200 m flat on top, down 100 m over 1 km. */
    private val hill = PreparedRoute.prepare(
        polyline(listOf(0.0 to 0.0, 2200.0 to 0.0)) { s ->
            when {
                s <= 1000 -> s / 10
                s <= 1200 -> 100.0
                else -> 100 - (s - 1200) / 10
            }
        },
    )

    @Test
    fun `no elevation data gives no profile`() {
        assertNull(PreparedRoute.prepare(listOf(at(0, 0), at(100, 0))).elevation)
    }

    @Test
    fun `one elevation value is not a profile`() {
        assertNull(PreparedRoute.prepare(listOf(at(0, 0, 500), at(100, 0))).elevation)
    }

    @Test
    fun `climb and descent of a hill`() {
        val p = hill.elevation!!
        assertEquals(100.0, p.totalAscent, 0.5)
        assertEquals(100.0, p.totalDescent, 0.5)
        assertEquals(0.0, p.minElevation, 0.5)
        assertEquals(100.0, p.maxElevation, 0.5)
    }

    @Test
    fun `climb left counts only what is ahead`() {
        val p = hill.elevation!!
        assertEquals(100.0, p.ascentBetween(0.0, hill.lengthMeters), 0.5)
        assertEquals(50.0, p.ascentBetween(500.0, hill.lengthMeters), 0.5)
        assertEquals(0.0, p.ascentBetween(1100.0, hill.lengthMeters), 0.5)
    }

    @Test
    fun `missing elevations are filled in along the route`() {
        val r = PreparedRoute.prepare(listOf(at(0, 0, 100), at(50, 0), at(100, 0, 120)))
        assertEquals(110.0, r.elevation!!.elevationAt(50.0), 0.05)
    }

    @Test
    fun `missing elevation at the start takes the first known value`() {
        val r = PreparedRoute.prepare(listOf(at(0, 0), at(50, 0, 100), at(100, 0, 120)))
        assertEquals(100.0, r.elevation!!.elevationAt(0.0), 0.05)
    }

    @Test
    fun `GPS noise on flat ground adds no climb`() {
        val rnd = Random(7)
        val r = PreparedRoute.prepare(
            polyline(listOf(0.0 to 0.0, 2000.0 to 0.0), step = 5.0) { 500 + rnd.nextDouble(-3.0, 3.0) },
        )
        assertEquals(0.0, r.elevation!!.totalAscent)
    }

    @Test
    fun `reversing swaps climb and descent`() {
        // Up 120 m over 1.2 km, 100 m flat, then down 20 m over 200 m.
        val r = PreparedRoute.prepare(
            polyline(listOf(0.0 to 0.0, 1500.0 to 0.0)) { s ->
                when {
                    s <= 1200 -> s / 10
                    s <= 1300 -> 120.0
                    else -> 120 - (s - 1300) / 10
                }
            },
        )
        val forward = r.elevation!!
        val reverse = r.reversed().elevation!!
        assertEquals(120.0, forward.totalAscent, 0.5)
        assertEquals(20.0, forward.totalDescent, 0.5)
        assertEquals(forward.totalDescent, reverse.totalAscent, 0.5)
        assertEquals(forward.totalAscent, reverse.totalDescent, 0.5)
    }
}
