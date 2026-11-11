package io.github.lrosa2.singletrail.gpx

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.floor

/**
 * Half-up rounding of a double's shortest decimal form — exactly what
 * `BigDecimal.valueOf(value).setScale(scale, HALF_UP)` gives — with plain arithmetic. BigDecimal is
 * 100× slower on Android, which made writing a 50,000-point route take seconds.
 *
 * The binary product `value × 10^scale` can be off by a few units in the last place, which only
 * matters right next to a half-step; those rare values, and huge ones, still go through BigDecimal.
 * The same helper lives in `:core:nav` for route hashes; both are tested against BigDecimal.
 */
internal object Decimals {
    private val POWERS = LongArray(16).also { p ->
        p[0] = 1
        for (i in 1 until p.size) p[i] = p[i - 1] * 10
    }

    /** Below 2^52, so the product still has its fraction. */
    private const val LIMIT = 4.0e15

    /** The value rounded half-up to [scale] decimals, as plain text without trailing zeros ("46.5", "100", "0"). */
    fun plain(value: Double, scale: Int): String = buildString(24) { appendPlain(this, value, scale) }

    /** [plain], appended to [out] without making a string first. */
    fun appendPlain(out: StringBuilder, value: Double, scale: Int) {
        val units = halfUpOrNull(value, scale)
        if (units == null) {
            out.append(BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString())
        } else {
            format(out, units, scale)
        }
    }

    /** `value` in units of 10^-scale, rounded half-up (away from zero); null when only BigDecimal can tell. */
    private fun halfUpOrNull(value: Double, scale: Int): Long? {
        val x = abs(value) * POWERS[scale]
        if (!(x < LIMIT)) return null // also NaN and infinity: BigDecimal reports those as before
        val whole = floor(x)
        val fraction = x - whole
        // Generous bound on the product's error: a few ulps of x, plus margin.
        if (abs(fraction - 0.5) <= 1e-6 + x * 4e-16) return null
        val rounded = whole.toLong() + if (fraction > 0.5) 1 else 0
        return if (value < 0) -rounded else rounded
    }

    private fun format(out: StringBuilder, units: Long, scale: Int) {
        if (units == 0L) {
            out.append('0')
            return
        }
        val a = abs(units)
        val whole = a / POWERS[scale]
        var fraction = a % POWERS[scale]
        var digits = scale
        while (digits > 0 && fraction % 10 == 0L) {
            fraction /= 10
            digits--
        }
        if (units < 0) out.append('-')
        out.append(whole)
        if (digits > 0) {
            out.append('.')
            // Leading zeros of the fraction, then its digits.
            var lead = POWERS[digits - 1]
            while (lead > 1 && fraction < lead) {
                out.append('0')
                lead /= 10
            }
            out.append(fraction)
        }
    }
}
