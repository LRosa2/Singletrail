package io.github.lrosa2.singletrail.nav

import io.github.lrosa2.singletrail.nav.RouteIdentity.hashes
import io.github.lrosa2.singletrail.nav.RouteIdentity.isSimilar
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.RoundingMode
import kotlin.math.abs

class RouteIdentityTest {

    // --- geometry hash --------------------------------------------------------------------------

    /** The key as first written with BigDecimal (slow on Android); stored hashes depend on it never changing. */
    private fun referenceKey(degrees: Double): Long =
        java.math.BigDecimal.valueOf(degrees)
            .setScale(7, RoundingMode.HALF_UP)
            .setScale(5, RoundingMode.HALF_UP)
            .unscaledValue()
            .toLong()

    @Test
    fun `stored hashes never change`() {
        // Libraries and backups keep these hashes; pinned so a faster implementation can't change them.
        val points = listOf(RoutePoint(46.7, 7.45, null), RoutePoint(46.70004495, 7.4500001, 1100.0), RoutePoint(46.71, -7.5, null))
        val h = hashes(points)
        assertEquals("6f9ba4c2a084da9a75aee0e047041b039a79b2eb7dd6c31cfbce18dfb447d96c", h.forward)
        assertEquals("fe4a0e5c91cf23177794801cfba11c74bc9a6d9052f6a655a2468aba06c81a64", h.reversed)
    }

    @Test
    fun `the coordinate key rounds exactly like BigDecimal`() {
        val edges = listOf(
            0.0, -0.0, 7.44233495, -7.44233495, 7.442334949999, 7.4423349500001, 7.44233499996, 46.12345, 46.123455,
            -46.123455, 46.1234549999, 179.99999995, -179.99999995, 0.000005, -0.000005, 0.0000049999, 1e-12,
        )
        for (v in edges) assertEquals(referenceKey(v), RouteIdentity.key(v), "value $v")
        val random = kotlin.random.Random(7)
        repeat(100_000) {
            val text = "%.${random.nextInt(5, 13)}f".format(java.util.Locale.ROOT, random.nextDouble(-180.0, 180.0))
            val v = text.toDouble()
            assertEquals(referenceKey(v), RouteIdentity.key(v), "value $v")
        }
        repeat(100_000) {
            // Half-steps of the 8th decimal (first rounding) and of the 6th (second rounding).
            val v = "${random.nextLong(-1_800_000_000L, 1_800_000_000L)}5E-8".toDouble()
            assertEquals(referenceKey(v), RouteIdentity.key(v), "value $v")
            val w = "${random.nextLong(-18_000_000L, 18_000_000L)}5E-6".toDouble()
            assertEquals(referenceKey(w), RouteIdentity.key(w), "value $w")
        }
    }

    private val trail = polyline(
        listOf(0.0 to 0.0, 800.0 to 0.0, 800.0 to 600.0, 1500.0 to 900.0),
        ele = { 600 + it / 20 },
    )

    /** Three points well inside their 5th-decimal rounding buckets. */
    private val short = listOf(
        RoutePoint(46.512343, 7.441212),
        RoutePoint(46.512843, 7.442212),
        RoutePoint(46.513343, 7.443212),
    )

    @Test
    fun `hash is a lowercase SHA-256 hex string`() {
        assertTrue(Regex("[0-9a-f]{64}").matches(hashes(trail).forward))
    }

    @Test
    fun `elevation does not change the hash`() {
        assertEquals(hashes(trail), hashes(trail.map { it.copy(elevation = null) }))
    }

    @Test
    fun `differences below the fifth decimal do not change the hash`() {
        assertEquals(hashes(short), hashes(short.map { RoutePoint(it.lat + 1e-7, it.lon - 1e-7) }))
    }

    @Test
    fun `moving one point by 11 m changes the hash`() {
        val moved = short.toMutableList().also { it[1] = it[1].copy(lat = it[1].lat + 1e-4) }
        assertNotEquals(hashes(short).forward, hashes(moved).forward)
    }

    @Test
    fun `repeated points do not change the hash`() {
        val withStops = short.flatMap { listOf(it, it.copy(lat = it.lat + 1e-7)) }
        assertEquals(hashes(short), hashes(withStops))
    }

    @Test
    fun `reversed hash is the hash of the reversed route`() {
        val h = hashes(trail)
        assertEquals(h.reversed, hashes(trail.reversed()).forward)
        assertEquals(h.forward, hashes(trail.reversed()).reversed)
        assertNotEquals(h.forward, h.reversed)
    }

    @Test
    fun `hash survives our own 7-decimal GPX export`() {
        // 7.44233499996 rounds straight to 7.44233, but GpxWriter stores it as 7.4423350, which
        // rounds to 7.44234 on re-import. Both must hash as the same point.
        val original = listOf(RoutePoint(46.75120, 7.44233499996), RoutePoint(46.75220, 7.44333))
        val reimported = original.map {
            RoutePoint(
                it.lat.toBigDecimal().setScale(7, RoundingMode.HALF_UP).toDouble(),
                it.lon.toBigDecimal().setScale(7, RoundingMode.HALF_UP).toDouble(),
            )
        }
        assertEquals(hashes(original), hashes(reimported))
    }

    // --- similarity -----------------------------------------------------------------------------

    /** About 5.3 km: 2 km east, 1.5 km north, then 1.8 km north-east. */
    private val corners = listOf(0.0 to 0.0, 2000.0 to 0.0, 2000.0 to 1500.0, 3500.0 to 2500.0)
    private val base = PreparedRoute.prepare(polyline(corners))

    @Test
    fun `the same trail recorded again is similar`() {
        // Shifted about 4 m sideways and with points every 7 m instead of every 10 m.
        val again = PreparedRoute.prepare(polyline(corners.map { (x, y) -> x + 3.0 to y + 3.0 }, step = 7.0))
        assertTrue(isSimilar(base, again))
    }

    @Test
    fun `the reversed trail is similar`() {
        assertTrue(isSimilar(base, base.reversed()))
    }

    @Test
    fun `a 300 m variant 30 m to the side is not similar`() {
        val variant = PreparedRoute.prepare(
            polyline(
                listOf(
                    0.0 to 0.0, 800.0 to 0.0, 800.0 to 30.0, 1100.0 to 30.0, 1100.0 to 0.0,
                    2000.0 to 0.0, 2000.0 to 1500.0, 3500.0 to 2500.0,
                ),
            ),
        )
        assertTrue(abs(variant.lengthMeters - base.lengthMeters) < 0.02 * base.lengthMeters)
        assertFalse(isSimilar(base, variant))
    }

    @Test
    fun `a trail more than 2 percent longer is not similar even inside the 10 m corridor`() {
        // The first leg zigzags 9 m either side of the base line every 25 m.
        val zigzag = (0..80).map { k ->
            k * 25.0 to when {
                k == 0 || k == 80 -> 0.0
                k % 2 == 1 -> 9.0
                else -> -9.0
            }
        }
        val wiggly = PreparedRoute.prepare(polyline(zigzag + corners.drop(2)))
        assertFalse(isSimilar(base, wiggly))
    }

    @Test
    fun `out and back is not similar to one way`() {
        val outAndBack = PreparedRoute.prepare(polyline(corners + corners.reversed().drop(1)))
        assertFalse(isSimilar(base, outAndBack))
    }
}
