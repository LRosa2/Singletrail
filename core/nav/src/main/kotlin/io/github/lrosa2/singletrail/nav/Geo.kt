package io.github.lrosa2.singletrail.nav

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Basic geodesy on the WGS84 mean-radius sphere; accurate to well under 0.5% at trail scale. */
object Geo {
    const val EARTH_RADIUS_M = 6_371_008.8

    /** Great-circle distance in metres between two lat/lon points given in degrees. */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dPhi = Math.toRadians(lat2 - lat1)
        val dLambda = Math.toRadians(lon2 - lon1)
        val a = sin(dPhi / 2).let { it * it } +
            cos(phi1) * cos(phi2) * sin(dLambda / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /** Longitude step from [from] to [to] in degrees, wrapped to [-180, 180) so lines across the antimeridian stay short. */
    fun lonDelta(from: Double, to: Double): Double {
        val d = (to - from) % 360.0
        return when {
            d >= 180.0 -> d - 360.0
            d < -180.0 -> d + 360.0
            else -> d
        }
    }

    /** [lon] normalised to [-180, 180). */
    fun normalizeLon(lon: Double): Double = lonDelta(0.0, lon)
}

internal fun RoutePoint.distanceTo(other: RoutePoint): Double =
    Geo.distanceMeters(lat, lon, other.lat, other.lon)
