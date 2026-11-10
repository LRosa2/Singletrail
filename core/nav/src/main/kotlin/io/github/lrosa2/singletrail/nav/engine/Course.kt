package io.github.lrosa2.singletrail.nav.engine

import io.github.lrosa2.singletrail.nav.Geo
import io.github.lrosa2.singletrail.nav.LocalPlane
import io.github.lrosa2.singletrail.nav.Maneuver
import io.github.lrosa2.singletrail.nav.ManeuverKind
import io.github.lrosa2.singletrail.nav.PreparedRoute
import io.github.lrosa2.singletrail.nav.RoutePosition
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The geometry being followed right now: the route in the riding direction, rotated to start at the
 * join point for loops. On a [closed] course, distances may run below 0 or past the length to mean
 * the previous or next lap, so matching continues smoothly across the start.
 */
internal class Course(
    val route: PreparedRoute,
    val maneuvers: List<Maneuver>,
    val closed: Boolean,
    barrierKinds: Set<ManeuverKind>,
) {
    val length: Double = route.lengthMeters
    private val bearings = route.segmentBearings

    /** Turns that matching and return targets never skip until ridden, in order (switchbacks turn by turn). */
    val barriers: DoubleArray = maneuvers
        .flatMap { if (it.parts.isEmpty()) listOf(it) else it.parts }
        .filter { it.kind in barrierKinds }
        .map { it.s }
        .sorted()
        .toDoubleArray()

    class Candidate(val s: Double, val distance: Double, val bearing: Double, val score: Double)

    /** Index of the first barrier beyond [s], or [barriers] size when there is none. */
    fun firstBarrierAfter(s: Double): Int {
        var lo = 0
        var hi = barriers.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (barriers[mid] > s) hi = mid else lo = mid + 1
        }
        return lo
    }

    /** The position [s] metres along, wrapped round a closed course and clamped on an open one. */
    fun position(s: Double): RoutePosition {
        if (!closed) return route.positionAt(s)
        val local = ((s % length) + length) % length
        return route.positionAt(local).copy(s = s)
    }

    /** The best-scoring point between [from] and [to] metres; [score] gets (s, distance, segment bearing). */
    fun bestInRange(lat: Double, lon: Double, from: Double, to: Double, score: (Double, Double, Double) -> Double): Candidate? {
        if (!closed) return scan(lat, lon, max(0.0, from), min(length, to), 0.0, score)
        var best: Candidate? = null
        val firstLap = floor(from / length).toInt()
        val lastLap = floor(to / length).toInt()
        for (lap in firstLap..lastLap) {
            val offset = lap * length
            val c = scan(lat, lon, max(0.0, from - offset), min(length, to - offset), offset, score) ?: continue
            if (best == null || c.score < best.score) best = c
        }
        return best
    }

    fun nearestInRange(lat: Double, lon: Double, from: Double, to: Double): Candidate? =
        bestInRange(lat, lon, from, to) { _, d, _ -> d }

    private fun scan(
        lat: Double,
        lon: Double,
        from: Double,
        to: Double,
        offset: Double,
        score: (Double, Double, Double) -> Double,
    ): Candidate? {
        if (from > to) return null
        var best: Candidate? = null
        val first = route.segmentAt(from)
        val last = route.segmentAt(to)
        for (seg in first..last) {
            val c = candidate(lat, lon, seg, from, to, offset, score)
            if (best == null || c.score < best.score) best = c
        }
        return best
    }

    /** The point of segment [seg] nearest to the rider, kept within [from]..[to]. */
    private fun candidate(
        lat: Double,
        lon: Double,
        seg: Int,
        from: Double,
        to: Double,
        offset: Double,
        score: (Double, Double, Double) -> Double,
    ): Candidate {
        val a = route.points[seg]
        val b = route.points[seg + 1]
        val p = LocalPlane.projectOntoSegment(lat, lon, a, b)
        val s0 = route.distanceAt(seg)
        val s = s0 + p.t * (route.distanceAt(seg + 1) - s0)
        val (at, distance) = if (s < from || s > to) {
            val clamped = s.coerceIn(from, to)
            val pos = route.positionAt(clamped)
            clamped to Geo.distanceMeters(lat, lon, pos.lat, pos.lon)
        } else {
            s to p.distanceMeters
        }
        val unwrapped = at + offset
        return Candidate(unwrapped, distance, bearings[seg], score(unwrapped, distance, bearings[seg]))
    }

    /**
     * The best-scoring point among the stretches of route within [radius] of the rider. Each pass of
     * the route near the rider counts once; an earlier pass wins when the scores are close.
     */
    fun bestNear(lat: Double, lon: Double, radius: Double, score: (Double, Double, Double) -> Double): Candidate? {
        val segs = route.segmentsNear(lat, lon, radius)
        if (segs.isEmpty()) return null
        val passes = ArrayList<Candidate>()
        var passBest: Candidate? = null
        var prevSeg = -2
        for (seg in segs) {
            val c = candidate(lat, lon, seg, 0.0, length, 0.0, score)
            if (seg != prevSeg + 1 && passBest != null) {
                passes += passBest
                passBest = null
            }
            if (passBest == null || c.score < passBest.score) passBest = c
            prevSeg = seg
        }
        passBest?.let { passes += it }
        val best = passes.minBy { it.score }
        return passes.first { it.score <= best.score + EARLIER_PASS_MARGIN_M }
    }

    /** The nearest point of the route, however far. */
    fun nearestAnywhere(lat: Double, lon: Double): Candidate {
        for (radius in SEARCH_RADII) {
            val hit = route.nearestSegment(lat, lon, radius) ?: continue
            return Candidate(hit.s, hit.distanceMeters, bearings[hit.segment], hit.distanceMeters)
        }
        return scan(lat, lon, 0.0, length, 0.0) { _, d, _ -> d }!!
    }

    private companion object {
        val SEARCH_RADII = doubleArrayOf(250.0, 1_000.0, 4_000.0, 16_000.0, 64_000.0)

        /** A later pass of the route has to score this much better to win over an earlier one. */
        const val EARLIER_PASS_MARGIN_M = 3.0
    }
}
