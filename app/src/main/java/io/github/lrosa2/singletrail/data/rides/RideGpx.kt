package io.github.lrosa2.singletrail.data.rides

import io.github.lrosa2.singletrail.gpx.GpxDocument
import io.github.lrosa2.singletrail.gpx.GpxPoint
import io.github.lrosa2.singletrail.gpx.GpxTrack
import io.github.lrosa2.singletrail.gpx.GpxTrackKind
import io.github.lrosa2.singletrail.nav.PreparedRoute
import io.github.lrosa2.singletrail.nav.RoutePoint

/** Recorded rides as GPX (spec §8.3: "converted to GPX on export") and back (restoring a backup). */
object RideGpx {

    /** One track named [name], one `trkseg` per recorded segment, with times and elevations. */
    fun document(name: String, points: List<TrackPoint>): GpxDocument {
        val segments = points.groupBy { it.segment }.toSortedMap().values.map { segment ->
            segment.map { GpxPoint(it.lat, it.lon, it.ele, it.timeMs) }
        }
        val tracks = if (segments.isEmpty()) emptyList() else listOf(GpxTrack(name, null, GpxTrackKind.TRACK, segments))
        return GpxDocument(name, null, null, tracks, emptyList())
    }

    /** Track points from an exported ride; what GPX doesn't hold (accuracy, speed, bearing) stays empty. */
    fun trackPoints(doc: GpxDocument): List<TrackPoint> {
        val segments = doc.tracks.flatMap { it.segments }
        return segments.flatMapIndexed { i, segment ->
            segment.map { p ->
                val time = p.timeEpochMs ?: 0L
                TrackPoint(time, time, p.lat, p.lon, p.elevation, null, null, null, i + 1)
            }
        }
    }

    /**
     * Climb ridden, from the recorded elevations smoothed like a route's (60 m window, 5 m swings), so
     * GPS altitude noise doesn't add up. Null without at least two points with elevation.
     */
    fun ascent(points: List<TrackPoint>): Double? {
        if (points.count { it.ele != null } < 2) return null
        return try {
            PreparedRoute.prepare(points.map { RoutePoint(it.lat, it.lon, it.ele) }).elevation?.totalAscent
        } catch (_: IllegalArgumentException) {
            null // Not enough movement to make a line.
        }
    }
}
