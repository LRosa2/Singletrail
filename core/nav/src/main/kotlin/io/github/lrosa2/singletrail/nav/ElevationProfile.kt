package io.github.lrosa2.singletrail.nav

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Elevation along a route (spec §5.2): sampled on the route's resampled polyline, smoothed with a
 * moving window, and with climb and descent counted through a hysteresis so noise doesn't add up.
 */
class ElevationProfile private constructor(
    private val s: DoubleArray,
    private val smoothed: DoubleArray,
    private val climb: Climb,
) {
    val totalAscent: Double get() = climb.ascent
    val totalDescent: Double get() = climb.descent
    val minElevation: Double = smoothed.min()
    val maxElevation: Double = smoothed.max()

    /** Smoothed elevation [distance] metres along the route. */
    fun elevationAt(distance: Double): Double = interpolate(smoothed, distance)

    /** Climb between two distances along the route; "climb left" is `ascentBetween(s, length)`. */
    fun ascentBetween(from: Double, to: Double): Double =
        max(0.0, interpolate(climb.cumulativeAscent, to) - interpolate(climb.cumulativeAscent, from))

    private fun interpolate(values: DoubleArray, at: Double): Double {
        val x = at.coerceIn(s.first(), s.last())
        val found = s.binarySearch(x)
        if (found >= 0) return values[found]
        val i = -found - 2
        val f = (x - s[i]) / (s[i + 1] - s[i])
        return values[i] + f * (values[i + 1] - values[i])
    }

    internal companion object {
        fun of(route: PreparedRoute): ElevationProfile? {
            val filled = fillGaps(route) ?: return null
            val samples = route.samples
            val s = DoubleArray(samples.size) { samples[it].s }
            val raw = DoubleArray(samples.size) { k ->
                val i = route.segmentAt(s[k])
                val f = (s[k] - route.distanceAt(i)) / (route.distanceAt(i + 1) - route.distanceAt(i))
                filled[i] + f * (filled[i + 1] - filled[i])
            }
            val config = route.config
            val halfWindow = (config.elevationWindowMeters / 2 / config.sampleSpacingMeters).roundToInt()
            val smoothed = Elevation.movingAverage(raw, halfWindow)
            return ElevationProfile(s, smoothed, Elevation.climb(smoothed, config.climbThresholdMeters))
        }

        /**
         * Elevation for every route point: gaps filled linearly by distance, the ends extended from
         * the nearest known value. Null when fewer than two points have elevation.
         */
        private fun fillGaps(route: PreparedRoute): DoubleArray? {
            val points = route.points
            val known = points.indices.filter { points[it].elevation?.isFinite() == true }
            if (known.size < 2) return null
            fun ele(i: Int) = points[i].elevation!!
            var k = 0
            return DoubleArray(points.size) { i ->
                while (k + 1 < known.size && known[k + 1] <= i) k++
                val a = known[k]
                if (i <= a || k + 1 == known.size) {
                    ele(a)
                } else {
                    val b = known[k + 1]
                    val f = (route.distanceAt(i) - route.distanceAt(a)) / (route.distanceAt(b) - route.distanceAt(a))
                    ele(a) + f * (ele(b) - ele(a))
                }
            }
        }
    }
}

/** Climb and descent so far at each sample; the totals are the last values. */
internal class Climb(val cumulativeAscent: DoubleArray, val cumulativeDescent: DoubleArray) {
    val ascent: Double get() = cumulativeAscent.lastOrNull() ?: 0.0
    val descent: Double get() = cumulativeDescent.lastOrNull() ?: 0.0
}

internal object Elevation {

    /**
     * Mean over [halfWindow] values either side. Near the ends the window shrinks evenly on both
     * sides, so the first and last values stay put and a steady slope is left unchanged.
     */
    fun movingAverage(values: DoubleArray, halfWindow: Int): DoubleArray {
        val n = values.size
        val prefix = DoubleArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + values[i]
        return DoubleArray(n) { i ->
            val k = min(halfWindow, min(i, n - 1 - i))
            (prefix[i + k + 1] - prefix[i - k]) / (2 * k + 1)
        }
    }

    /**
     * Climb and descent with hysteresis: a rise or fall counts only once it reaches [threshold], and
     * then counts in full, from the lowest to the highest point of the swing.
     */
    fun climb(values: DoubleArray, threshold: Double): Climb {
        val swings = swings(values, threshold)
        val n = values.size
        val up = DoubleArray(n)
        val down = DoubleArray(n)
        var baseUp = 0.0
        var baseDown = 0.0
        var i = 0
        for ((a, b) in swings) {
            while (i <= a) {
                up[i] = baseUp
                down[i] = baseDown
                i++
            }
            // Within a swing the running high (or low) so far is what has been climbed (or descended).
            val rising = values[b] > values[a]
            var extreme = values[a]
            for (j in a + 1..b) {
                if (rising) {
                    extreme = max(extreme, values[j])
                    up[j] = baseUp + (extreme - values[a])
                    down[j] = baseDown
                } else {
                    extreme = min(extreme, values[j])
                    up[j] = baseUp
                    down[j] = baseDown + (values[a] - extreme)
                }
            }
            if (rising) baseUp += values[b] - values[a] else baseDown += values[a] - values[b]
            i = b + 1
        }
        while (i < n) {
            up[i] = baseUp
            down[i] = baseDown
            i++
        }
        return Climb(up, down)
    }

    /** Consecutive (start, end) index pairs of the rises and falls of at least [threshold]. */
    private fun swings(v: DoubleArray, threshold: Double): List<Pair<Int, Int>> {
        val swings = ArrayList<Pair<Int, Int>>()
        var direction = 0 // 0 until the first swing is confirmed, then +1 rising / -1 falling
        var anchor = 0 // where the current swing started
        var extreme = 0 // highest (rising) or lowest (falling) point of the current swing so far
        var lowest = 0
        var highest = 0
        for (i in 1 until v.size) {
            when (direction) {
                0 -> {
                    if (v[i] < v[lowest]) lowest = i
                    if (v[i] > v[highest]) highest = i
                    if (v[i] - v[lowest] >= threshold) {
                        direction = 1
                        anchor = lowest
                        extreme = i
                    } else if (v[highest] - v[i] >= threshold) {
                        direction = -1
                        anchor = highest
                        extreme = i
                    }
                }
                1 -> if (v[i] >= v[extreme]) {
                    extreme = i
                } else if (v[extreme] - v[i] >= threshold) {
                    swings += anchor to extreme
                    anchor = extreme
                    extreme = i
                    direction = -1
                }
                else -> if (v[i] <= v[extreme]) {
                    extreme = i
                } else if (v[i] - v[extreme] >= threshold) {
                    swings += anchor to extreme
                    anchor = extreme
                    extreme = i
                    direction = 1
                }
            }
        }
        // The open swing already reached the threshold, so it counts.
        if (direction != 0) swings += anchor to extreme
        return swings
    }
}
