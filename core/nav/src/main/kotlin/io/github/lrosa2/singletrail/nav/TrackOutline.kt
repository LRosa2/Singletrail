package io.github.lrosa2.singletrail.nav

/**
 * A route's shape, thinned to a few points per kilometre, for "near me" in the library (spec §8.1):
 * the distance to the nearest point anywhere along the route without reading its GPX file.
 *
 * Coordinates are kept in units of 1e-5° (about a metre), the precision of [encode], so an outline
 * read back from its text is equal to the one written.
 */
class TrackOutline private constructor(
    private val lats: IntArray,
    private val lons: IntArray,
) {
    init {
        require(lats.isNotEmpty() && lats.size == lons.size) { "An outline needs at least one point" }
    }

    val size: Int get() = lats.size

    /**
     * Metres in a straight line from ([lat], [lon]) to the nearest point of the outline. That is within
     * the outline's tolerance (plus a metre) of the distance to the full track.
     */
    fun distanceMeters(lat: Double, lon: Double): Double {
        if (size == 1) return Geo.distanceMeters(lat, lon, lat(0), lon(0))
        // Pick the nearest segment on a flat plane around the query point, then measure the great
        // circle to the closest point of that segment, so far-away routes still get a true distance.
        var best = -1
        var bestT = 0.0
        var bestPlane = Double.POSITIVE_INFINITY
        for (i in 0 until size - 1) {
            val p = LocalPlane.projectOntoSegment(lat, lon, lat(i), lon(i), lat(i + 1), lon(i + 1))
            if (p.distanceMeters < bestPlane) {
                best = i
                bestT = p.t
                bestPlane = p.distanceMeters
            }
        }
        val closestLat = lat(best) + bestT * (lat(best + 1) - lat(best))
        val closestLon = lon(best) + bestT * Geo.lonDelta(lon(best), lon(best + 1))
        return Geo.distanceMeters(lat, lon, closestLat, Geo.normalizeLon(closestLon))
    }

    /** The outline as text, in the encoded polyline format (precision 1e-5°, about 5 characters a point). */
    fun encode(): String = buildString(size * 6) {
        var lastLat = 0
        var lastLon = 0
        for (i in 0 until size) {
            appendSigned(lats[i] - lastLat)
            appendSigned(lons[i] - lastLon)
            lastLat = lats[i]
            lastLon = lons[i]
        }
    }

    private fun lat(i: Int) = lats[i] / UNITS_PER_DEGREE
    private fun lon(i: Int) = lons[i] / UNITS_PER_DEGREE

    override fun equals(other: Any?): Boolean =
        other is TrackOutline && lats.contentEquals(other.lats) && lons.contentEquals(other.lons)

    override fun hashCode(): Int = 31 * lats.contentHashCode() + lons.contentHashCode()

    override fun toString(): String = "TrackOutline($size points)"

    companion object {
        /** How far a dropped track point may lie from the outline. */
        const val DEFAULT_TOLERANCE_M = 15.0

        private const val UNITS_PER_DEGREE = 1e5

        /**
         * [points] thinned so that every one of them lies within [toleranceMeters] of the outline
         * (Douglas–Peucker); the first and last points are always kept.
         */
        fun of(points: List<RoutePoint>, toleranceMeters: Double = DEFAULT_TOLERANCE_M): TrackOutline {
            require(points.isNotEmpty()) { "An outline needs at least one point" }
            val keep = BooleanArray(points.size)
            keep[0] = true
            keep[points.lastIndex] = true
            val pending = ArrayDeque<Pair<Int, Int>>()
            pending += 0 to points.lastIndex
            while (pending.isNotEmpty()) {
                val (first, last) = pending.removeLast()
                var farthest = -1
                var farthestDistance = toleranceMeters
                for (i in first + 1 until last) {
                    val d = LocalPlane.projectOntoSegment(points[i].lat, points[i].lon, points[first], points[last]).distanceMeters
                    if (d > farthestDistance) {
                        farthest = i
                        farthestDistance = d
                    }
                }
                if (farthest >= 0) {
                    keep[farthest] = true
                    pending += first to farthest
                    pending += farthest to last
                }
            }
            val kept = points.filterIndexed { i, _ -> keep[i] }
            return TrackOutline(
                IntArray(kept.size) { Math.round(kept[it].lat * UNITS_PER_DEGREE).toInt() },
                IntArray(kept.size) { Math.round(Geo.normalizeLon(kept[it].lon) * UNITS_PER_DEGREE).toInt() },
            )
        }

        /** The outline written by [encode]. */
        fun decode(text: String): TrackOutline {
            val lats = ArrayList<Int>()
            val lons = ArrayList<Int>()
            var at = 0
            var lat = 0
            var lon = 0

            fun nextSigned(): Int {
                var result = 0
                var shift = 0
                while (true) {
                    require(at < text.length) { "Outline text ends in the middle of a number" }
                    val chunk = text[at++].code - 63
                    require(chunk in 0..63) { "Not an outline character: '${text[at - 1]}'" }
                    result = result or ((chunk and 0x1f) shl shift)
                    shift += 5
                    if (chunk < 0x20) break
                }
                return if ((result and 1) != 0) (result ushr 1).inv() else result ushr 1
            }

            while (at < text.length) {
                lat += nextSigned()
                lon += nextSigned()
                lats += lat
                lons += lon
            }
            return TrackOutline(lats.toIntArray(), lons.toIntArray())
        }

        private fun StringBuilder.appendSigned(value: Int) {
            var v = if (value < 0) (value shl 1).inv() else value shl 1
            while (v >= 0x20) {
                append(((0x20 or (v and 0x1f)) + 63).toChar())
                v = v ushr 5
            }
            append((v + 63).toChar())
        }
    }
}
