package io.github.lrosa2.singletrail.nav

/**
 * A route ready for navigation (spec §5.2): near-duplicate points dropped, cumulative distance,
 * loop flag, a polyline resampled every few metres, a nearest-segment index and a smoothed
 * elevation profile. The resampled polyline, index and profile are built on first use.
 */
class PreparedRoute private constructor(
    /** The route's points after dropping those closer than [RoutePrepConfig.minPointSpacingMeters]. */
    val points: List<RoutePoint>,
    private val cumulative: DoubleArray,
    val config: RoutePrepConfig,
) {
    val lengthMeters: Double get() = cumulative[cumulative.size - 1]

    /** Start and end are less than [RoutePrepConfig.loopMaxGapMeters] apart. */
    val isLoop: Boolean = points.first().distanceTo(points.last()) < config.loopMaxGapMeters

    /** Positions every [RoutePrepConfig.sampleSpacingMeters] from the start, plus the finish. */
    val samples: List<RoutePosition> by lazy {
        val step = config.sampleSpacingMeters
        // Skip a regular sample that would sit within a tenth of a step of the finish.
        val lastRegular = lengthMeters - step * 0.1
        buildList {
            var k = 0
            while (k * step < lastRegular) {
                add(positionAt(k * step))
                k++
            }
            add(positionAt(lengthMeters))
        }
    }

    /** Smoothed elevation with climb and descent, or null when fewer than two points have elevation. */
    val elevation: ElevationProfile? by lazy { ElevationProfile.of(this) }

    private val index by lazy { SegmentIndex(points, cumulative, config.gridCellMeters) }

    /** Distance from the start to `points[index]`, in metres. */
    fun distanceAt(index: Int): Double = cumulative[index]

    /** The point [s] metres along the route, clamped to the start and the finish. */
    fun positionAt(s: Double): RoutePosition {
        val at = s.coerceIn(0.0, lengthMeters)
        val i = segmentAt(at)
        val a = points[i]
        val b = points[i + 1]
        val f = (at - cumulative[i]) / (cumulative[i + 1] - cumulative[i])
        return RoutePosition(
            at,
            a.lat + f * (b.lat - a.lat),
            Geo.normalizeLon(a.lon + f * Geo.lonDelta(a.lon, b.lon)),
        )
    }

    /** The segment nearest to the point within [maxDistanceMeters], or null when there is none. */
    fun nearestSegment(lat: Double, lon: Double, maxDistanceMeters: Double): SegmentHit? =
        index.nearest(lat, lon, maxDistanceMeters)

    /** The same route ridden from the finish to the start. */
    fun reversed(): PreparedRoute = prepare(points.asReversed(), config)

    /**
     * The loop ridden once round from [s]: it starts and finishes at that point, and its last
     * segment closes the gap between the original finish and start. Requires [isLoop].
     */
    fun rotatedLoop(s: Double): PreparedRoute {
        require(isLoop) { "Only a loop can be rotated" }
        val at = s.coerceIn(0.0, lengthMeters)
        val start = pointAt(at)
        val i = segmentAt(at)
        val out = ArrayList<RoutePoint>(points.size + 2)
        out += start
        for (k in i + 1 until points.size) out += points[k]
        for (k in 0..i) out += points[k]
        // Keep the closing point: drop what sits too close to it, or preparation would drop it instead.
        while (out.size > 1 && out.last().distanceTo(start) < config.minPointSpacingMeters) out.removeAt(out.lastIndex)
        out += start
        return prepare(out, config)
    }

    /** The point [s] metres along, with elevation interpolated where both neighbours have it. */
    internal fun pointAt(s: Double): RoutePoint {
        val p = positionAt(s)
        val i = segmentAt(p.s)
        val a = points[i].elevation
        val b = points[i + 1].elevation
        val ele = when {
            a != null && b != null -> a + (p.s - cumulative[i]) / (cumulative[i + 1] - cumulative[i]) * (b - a)
            else -> a ?: b
        }
        return RoutePoint(p.lat, p.lon, ele)
    }

    /** Bearing of each segment, degrees clockwise from north. */
    internal val segmentBearings: DoubleArray by lazy {
        DoubleArray(points.size - 1) { Geo.bearingDeg(points[it].lat, points[it].lon, points[it + 1].lat, points[it + 1].lon) }
    }

    /** Indices of the segments that come within [radiusMeters] of the point, in route order. */
    internal fun segmentsNear(lat: Double, lon: Double, radiusMeters: Double): IntArray = index.within(lat, lon, radiusMeters)

    /** Index of the segment that contains distance [s]; the last segment for the finish. */
    internal fun segmentAt(s: Double): Int {
        val found = cumulative.binarySearch(s)
        val i = if (found >= 0) found else -found - 2
        return i.coerceIn(0, points.size - 2)
    }

    companion object {
        /** @throws IllegalArgumentException when fewer than two points are left after dropping near-duplicates. */
        fun prepare(points: List<RoutePoint>, config: RoutePrepConfig = RoutePrepConfig.DEFAULT): PreparedRoute {
            val kept = ArrayList<RoutePoint>(points.size)
            val cumulative = DoubleArray(points.size)
            for (p in points) {
                if (kept.isEmpty()) {
                    kept += p
                    continue
                }
                val d = kept.last().distanceTo(p)
                if (d >= config.minPointSpacingMeters) {
                    cumulative[kept.size] = cumulative[kept.size - 1] + d
                    kept += p
                }
            }
            require(kept.size >= 2) {
                "A route needs at least two points ${config.minPointSpacingMeters} m or more apart"
            }
            return PreparedRoute(kept, cumulative.copyOf(kept.size), config)
        }
    }
}
