package io.github.lrosa2.singletrail.nav

import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

/** Route identity for de-duplication at import (spec §8.4 step 4, D36). */
object RouteIdentity {

    /** SHA-256 of the rounded geometry, in hex, for the route as given and ridden backwards. */
    data class GeometryHashes(val forward: String, val reversed: String)

    /**
     * Hashes the coordinates rounded to 5 decimals (about 1 m), with consecutive repeats dropped.
     * Elevation and time are ignored.
     *
     * Coordinates are first rounded to the 7 decimals GpxWriter stores, then to 5. A route therefore
     * keeps its hash through our own export and re-import; rounding straight to 5 decimals would
     * disagree for values just below a half-step, such as 7.44233499996.
     */
    fun hashes(points: List<RoutePoint>): GeometryHashes {
        val lat = LongArray(points.size)
        val lon = LongArray(points.size)
        var n = 0
        for (p in points) {
            val la = key(p.lat)
            val lo = key(p.lon)
            if (n > 0 && lat[n - 1] == la && lon[n - 1] == lo) continue
            lat[n] = la
            lon[n] = lo
            n++
        }
        return GeometryHashes(
            forward = sha256(lat, lon, 0 until n),
            reversed = sha256(lat, lon, (0 until n).reversed()),
        )
    }

    /**
     * Whether two routes are the same trail recorded or planned differently: lengths within
     * [maxLengthDifference] (a fraction), and every resampled point of each within
     * [maxDeviationMeters] of the other route. Direction doesn't matter.
     */
    fun isSimilar(
        a: PreparedRoute,
        b: PreparedRoute,
        maxDeviationMeters: Double = 10.0,
        maxLengthDifference: Double = 0.02,
    ): Boolean {
        val longer = max(a.lengthMeters, b.lengthMeters)
        if (abs(a.lengthMeters - b.lengthMeters) > maxLengthDifference * longer) return false
        return a.staysNear(b, maxDeviationMeters) && b.staysNear(a, maxDeviationMeters)
    }

    private fun PreparedRoute.staysNear(other: PreparedRoute, maxDeviationMeters: Double) =
        samples.all { other.nearestSegment(it.lat, it.lon, maxDeviationMeters) != null }

    /**
     * Degrees rounded half-up to 7 decimals, then to 5, in units of 10^-5. Plain arithmetic, because
     * BigDecimal is 100× slower on Android; values within a hair of a half-step, where the binary
     * product could be off, still go through BigDecimal, so the result never changes.
     */
    internal fun key(degrees: Double): Long {
        val x = abs(degrees) * 1e7
        if (x < 4.0e15) { // below 2^52, so the product keeps its fraction; false for NaN too
            val whole = floor(x)
            val fraction = x - whole
            if (abs(fraction - 0.5) > 1e-6 + x * 4e-16) {
                val units7 = whole.toLong() + if (fraction > 0.5) 1 else 0
                val units5 = (units7 + 50) / 100 // half-up from 7 to 5 decimals, exactly, on integers
                return if (degrees < 0) -units5 else units5
            }
        }
        return BigDecimal.valueOf(degrees)
            .setScale(7, RoundingMode.HALF_UP)
            .setScale(5, RoundingMode.HALF_UP)
            .unscaledValue()
            .toLong()
    }

    private fun sha256(lat: LongArray, lon: LongArray, order: IntProgression): String {
        // All points in one buffer and one update: on Android each update() is a call into native code.
        val buffer = ByteBuffer.allocate(16 * order.count())
        for (i in order) buffer.putLong(lat[i]).putLong(lon[i])
        val digest = MessageDigest.getInstance("SHA-256").digest(buffer.array())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
