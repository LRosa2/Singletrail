package io.github.lrosa2.singletrail.nav

import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Uniform grid over a route's segments (spec §5.2, ~50 m cells), so a nearest-segment query looks at
 * a handful of segments instead of the whole route.
 *
 * Cells live on one plane for the whole route, scaled at its middle latitude. Longitudes are measured
 * as wrapped steps from the first point, so a route across the antimeridian stays in one piece.
 */
internal class SegmentIndex(
    private val points: List<RoutePoint>,
    private val cumulative: DoubleArray,
    private val cellMeters: Double,
) {
    private val lat0 = (points.minOf { it.lat } + points.maxOf { it.lat }) / 2
    private val lon0 = points[0].lon
    private val cosLat0 = cos(Math.toRadians(lat0))
    private val kx = LocalPlane.METERS_PER_DEGREE * cosLat0
    private val cells: Map<Long, IntArray>

    init {
        val building = HashMap<Long, MutableList<Int>>()
        val keys = LinkedHashSet<Long>()
        for (seg in 0 until points.size - 1) {
            keys.clear()
            collectCells(points[seg], points[seg + 1], keys)
            for (key in keys) building.getOrPut(key) { ArrayList(4) }.add(seg)
        }
        cells = building.mapValuesTo(HashMap(building.size)) { it.value.toIntArray() }
    }

    /** Nearest segment within [maxDistanceMeters] of the point, or null. Ties go to the earlier segment. */
    fun nearest(lat: Double, lon: Double, maxDistanceMeters: Double): SegmentHit? {
        require(maxDistanceMeters.isFinite() && maxDistanceMeters >= 0) { "maxDistanceMeters must be finite and >= 0" }
        val cx = floor(x(lon) / cellMeters).toInt()
        val cy = floor(y(lat) / cellMeters).toInt()
        // A cell r rings out is at least (r - 1) cells away on the grid plane. The grid's east-west
        // scale is the route's middle latitude; this factor turns that into a bound at the query's.
        val scale = min(1.0, cos(Math.toRadians(lat)) / cosLat0).coerceAtLeast(0.05)
        val lastRing = ceil(maxDistanceMeters / (cellMeters * scale)).toInt() + 1

        var bestSeg = -1
        var bestT = 0.0
        var bestDist = Double.POSITIVE_INFINITY
        val seen = HashSet<Int>()
        for (ring in 0..lastRing) {
            forEachCellOnRing(cx, cy, ring) { key ->
                val segs = cells[key] ?: return@forEachCellOnRing
                for (seg in segs) {
                    if (!seen.add(seg)) continue
                    val p = LocalPlane.projectOntoSegment(lat, lon, points[seg], points[seg + 1])
                    val better = p.distanceMeters < bestDist || (p.distanceMeters == bestDist && seg < bestSeg)
                    if (p.distanceMeters <= maxDistanceMeters && better) {
                        bestSeg = seg
                        bestT = p.t
                        bestDist = p.distanceMeters
                    }
                }
            }
            // Everything not seen yet is at least ring cells away; nothing there can beat the best.
            if (bestSeg >= 0 && bestDist <= ring * cellMeters * scale) break
        }
        if (bestSeg < 0) return null
        val s = cumulative[bestSeg] + bestT * (cumulative[bestSeg + 1] - cumulative[bestSeg])
        return SegmentHit(bestSeg, bestDist, s)
    }

    private fun x(lon: Double) = Geo.lonDelta(lon0, lon) * kx
    private fun y(lat: Double) = (lat - lat0) * LocalPlane.METERS_PER_DEGREE

    /** Cells touched by the segment, found by cutting it into pieces no longer than a cell. */
    private fun collectCells(a: RoutePoint, b: RoutePoint, into: MutableSet<Long>) {
        val ax = x(a.lon)
        val ay = y(a.lat)
        val dx = Geo.lonDelta(a.lon, b.lon) * kx
        val dy = (b.lat - a.lat) * LocalPlane.METERS_PER_DEGREE
        val pieces = max(1, ceil(hypot(dx, dy) / cellMeters).toInt())
        for (p in 0 until pieces) {
            val x0 = ax + dx * p / pieces
            val y0 = ay + dy * p / pieces
            val x1 = ax + dx * (p + 1) / pieces
            val y1 = ay + dy * (p + 1) / pieces
            for (cx in cell(min(x0, x1))..cell(max(x0, x1))) {
                for (cy in cell(min(y0, y1))..cell(max(y0, y1))) into += key(cx, cy)
            }
        }
    }

    private fun cell(v: Double) = floor(v / cellMeters).toInt()

    private inline fun forEachCellOnRing(cx: Int, cy: Int, ring: Int, action: (Long) -> Unit) {
        if (ring == 0) {
            action(key(cx, cy))
            return
        }
        for (dx in -ring..ring) {
            action(key(cx + dx, cy - ring))
            action(key(cx + dx, cy + ring))
        }
        for (dy in -ring + 1..<ring) {
            action(key(cx - ring, cy + dy))
            action(key(cx + ring, cy + dy))
        }
    }

    private fun key(cx: Int, cy: Int): Long = (cx.toLong() shl 32) or (cy.toLong() and 0xffffffffL)
}
