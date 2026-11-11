package io.github.lrosa2.singletrail.gpx

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.random.Random

class DecimalsTest {
    /** What the writer used to do; slow on Android, so [Decimals] must give exactly the same text. */
    private fun reference(value: Double, scale: Int): String =
        BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    private fun check(value: Double, scale: Int) =
        assertEquals(reference(value, scale), Decimals.plain(value, scale), "value $value, scale $scale")

    @Test
    fun `edge cases format like BigDecimal`() {
        val values = listOf(
            0.0, -0.0, 1.0, -1.0, 100.0, 0.5, -0.25, 7.44233495, -7.44233495, 7.442334949999, 7.4423349500001,
            46.12345675, -46.12345675, 0.00000005, -0.00000005, 0.00000004, -0.00000001, 179.99999995, -179.99999995,
            1e-12, 1234.05, 1234.15, -1234.15, 0.05, 0.15, 0.25, 0.35, 1e12 + 0.5, 1e17, -1e17, 9_007_199_254_740_993.0,
        )
        for (v in values) {
            check(v, 7)
            check(v, 1)
        }
    }

    @Test
    fun `random coordinates and elevations format like BigDecimal`() {
        val random = Random(42)
        repeat(100_000) {
            // Coordinates with 5 to 12 decimals, as GPX files carry them.
            val digits = random.nextInt(5, 13)
            val text = "%.${digits}f".format(java.util.Locale.ROOT, random.nextDouble(-180.0, 180.0))
            check(text.toDouble(), 7)
        }
        repeat(100_000) {
            // Exactly on a half-step of the 8th decimal: the case that rounding by arithmetic gets wrong.
            val base = random.nextLong(-1_800_000_000L, 1_800_000_000L)
            check("${base}5E-8".toDouble(), 7)
        }
        repeat(100_000) {
            check(random.nextDouble(-500.0, 9_000.0), 1)
        }
    }
}
