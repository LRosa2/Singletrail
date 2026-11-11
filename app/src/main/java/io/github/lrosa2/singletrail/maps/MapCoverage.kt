package io.github.lrosa2.singletrail.maps

import io.github.lrosa2.singletrail.data.db.MapFileEntity
import io.github.lrosa2.singletrail.nav.RoutePosition
import kotlin.math.ceil

/** The area a map file covers, from its header. */
data class MapArea(val minLat: Double, val minLon: Double, val maxLat: Double, val maxLon: Double) {
    fun contains(lat: Double, lon: Double) = lat in minLat..maxLat && lon in minLon..maxLon
}

val MapFileEntity.area get() = MapArea(minLat, minLon, maxLat, maxLon)

/** Coverage check (spec §7.5): how much of a route lies outside every installed map's area. */
object MapCoverage {
    private const val STEP_METERS = 25.0

    fun uncoveredMeters(route: List<RoutePosition>, areas: List<MapArea>): Double {
        var uncovered = 0.0
        for (i in 1 until route.size) {
            val a = route[i - 1]
            val b = route[i]
            val length = b.s - a.s
            if (length <= 0) continue
            val steps = ceil(length / STEP_METERS).toInt()
            for (k in 0 until steps) {
                // Judge each short piece by its middle.
                val t = (k + 0.5) / steps
                val lat = a.lat + (b.lat - a.lat) * t
                val lon = a.lon + (b.lon - a.lon) * t
                if (areas.none { it.contains(lat, lon) }) uncovered += length / steps
            }
        }
        return uncovered
    }
}
