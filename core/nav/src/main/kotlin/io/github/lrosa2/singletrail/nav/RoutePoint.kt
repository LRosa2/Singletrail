package io.github.lrosa2.singletrail.nav

/** A route vertex in WGS84 degrees, with elevation in metres when the source has it. */
data class RoutePoint(val lat: Double, val lon: Double, val elevation: Double? = null)

/** A position [s] metres along a route. */
data class RoutePosition(val s: Double, val lat: Double, val lon: Double)

/**
 * The route segment nearest to a query point: segment index (from `points[segment]` to
 * `points[segment + 1]`), distance from the query point, and the progress [s] of the closest
 * point on that segment.
 */
data class SegmentHit(val segment: Int, val distanceMeters: Double, val s: Double)
