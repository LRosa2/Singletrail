package io.github.lrosa2.singletrail.maps

import io.github.lrosa2.singletrail.nav.Maneuver
import io.github.lrosa2.singletrail.nav.PreparedRoute
import io.github.lrosa2.singletrail.nav.RoutePosition
import io.github.lrosa2.singletrail.nav.engine.GpsStatus
import io.github.lrosa2.singletrail.nav.engine.HeadingSource
import io.github.lrosa2.singletrail.nav.engine.LatLon
import io.github.lrosa2.singletrail.nav.engine.NavState
import io.github.lrosa2.singletrail.nav.engine.TargetKind

/**
 * A piece of the route for drawing: [start], then the route's own points with an index from [from]
 * until [to], then [end]. The points themselves are not copied (spec §10: no full-route churn per fix).
 */
data class Stretch(val start: RoutePosition, val from: Int, val to: Int, val end: RoutePosition)

/** Cuts a route into stretches by distance: ridden, ahead and skipped parts (spec §4.1, §5.7). */
class RouteCuts(val route: PreparedRoute) {
    private val distances = DoubleArray(route.points.size) { route.distanceAt(it) }

    /** The route from [from] to [to] metres, clamped to the route; null when nothing is left. */
    fun stretch(from: Double, to: Double): Stretch? {
        val a = from.coerceIn(0.0, route.lengthMeters)
        val b = to.coerceIn(0.0, route.lengthMeters)
        if (b <= a) return null
        return Stretch(route.positionAt(a), firstAfter(a), firstAtOrAfter(b), route.positionAt(b))
    }

    /** Index of the first point further along than [s]. */
    private fun firstAfter(s: Double): Int {
        var i = firstAtOrAfter(s)
        while (i < distances.size && distances[i] <= s) i++
        return i
    }

    /** Index of the first point at [s] or further along. */
    private fun firstAtOrAfter(s: Double): Int {
        val found = distances.binarySearch(s)
        return if (found >= 0) found else -found - 1
    }
}

/** Where the map looks while it follows the rider. */
data class MapCamera(
    val lat: Double,
    val lon: Double,
    /** Degrees clockwise from north that point up on the screen; 0 for north-up. */
    val bearingDeg: Double,
    /** Heading-up puts the rider low on the screen, so more of the way ahead shows. */
    val headingUp: Boolean,
)

/**
 * What the ride map shows besides the map itself (spec §4.1, §5.7, §5.9), worked out from the
 * engine's state so it can be tested without a map.
 */
data class MapScene(
    /** Null before the first fix: the map shows the whole route. */
    val camera: MapCamera?,
    val rider: LatLon?,
    /** The rider arrow's angle on the screen, clockwise from up; null draws a dot. */
    val riderArrowDeg: Double?,
    /** GPS lost or the heading frozen: stale data must not look trustworthy (D31). */
    val riderInactive: Boolean,
    /** The other end of a straight line from the rider, when the arrow points somewhere the route line doesn't. */
    val target: LatLon?,
    /** Turns not yet passed; each turn of a switchback group on its own. */
    val turns: List<LatLon>,
) {
    companion object {
        fun of(nav: NavState, maneuvers: List<Maneuver>, northUpChosen: Boolean): MapScene {
            val position = nav.position
            val heading = nav.heading
            val frozen = heading?.source == HeadingSource.FROZEN
            // Compass off and slow (spec §5.9): north-up with a bearing line to the target.
            val headingUp = !northUpChosen && heading != null && !frozen
            val camera = position?.let { MapCamera(it.lat, it.lon, if (headingUp) heading.degrees else 0.0, headingUp) }
            val target = nav.target?.takeIf { position != null && (it.kind != TargetKind.LOOK_AHEAD || frozen) }
            return MapScene(
                camera = camera,
                rider = position,
                riderArrowDeg = when {
                    heading == null -> null
                    headingUp -> 0.0
                    else -> heading.degrees
                },
                riderInactive = nav.gps == GpsStatus.LOST || frozen,
                target = target?.let { LatLon(it.lat, it.lon) },
                turns = maneuvers.flatMap { m -> m.parts.ifEmpty { listOf(m) } }
                    .filter { it.s > nav.progressM }
                    .map { LatLon(it.lat, it.lon) },
            )
        }
    }
}
