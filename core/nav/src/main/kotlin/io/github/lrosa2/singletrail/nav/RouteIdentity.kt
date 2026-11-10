package io.github.lrosa2.singletrail.nav

import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.abs
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

    private fun key(degrees: Double): Long =
        BigDecimal.valueOf(degrees)
            .setScale(7, RoundingMode.HALF_UP)
            .setScale(5, RoundingMode.HALF_UP)
            .unscaledValue()
            .toLong()

    private fun sha256(lat: LongArray, lon: LongArray, order: IntProgression): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteBuffer.allocate(16)
        for (i in order) {
            buffer.clear()
            buffer.putLong(lat[i]).putLong(lon[i])
            digest.update(buffer.array())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
