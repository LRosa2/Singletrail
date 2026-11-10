package io.github.lrosa2.singletrail.nav

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Flat-earth (equirectangular) maths around a point. At the few hundred metres the matcher and the
 * grid index look at, the error against the sphere is far below GPS noise.
 */
internal object LocalPlane {
    const val METERS_PER_DEGREE = Geo.EARTH_RADIUS_M * PI / 180

    /** Where the closest point of a segment lies: fraction [t] from its start, and its distance in metres. */
    class Projection(val t: Double, val distanceMeters: Double)

    /** Projects the point ([lat], [lon]) onto the segment from [a] to [b], on a plane centred on the point. */
    fun projectOntoSegment(lat: Double, lon: Double, a: RoutePoint, b: RoutePoint): Projection {
        val kx = METERS_PER_DEGREE * cos(Math.toRadians(lat))
        val ax = Geo.lonDelta(lon, a.lon) * kx
        val ay = (a.lat - lat) * METERS_PER_DEGREE
        val dx = Geo.lonDelta(a.lon, b.lon) * kx
        val dy = (b.lat - a.lat) * METERS_PER_DEGREE
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
        return Projection(t, hypot(ax + t * dx, ay + t * dy))
    }
}
