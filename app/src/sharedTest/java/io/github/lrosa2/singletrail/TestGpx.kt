package io.github.lrosa2.singletrail

import io.github.lrosa2.singletrail.data.db.SourceKind
import io.github.lrosa2.singletrail.gpx.GpxDocument
import io.github.lrosa2.singletrail.gpx.GpxPoint
import io.github.lrosa2.singletrail.gpx.GpxTrack
import io.github.lrosa2.singletrail.gpx.GpxTrackKind
import io.github.lrosa2.singletrail.gpx.GpxWaypoint
import io.github.lrosa2.singletrail.importing.NewRoute
import io.github.lrosa2.singletrail.nav.Geo
import io.github.lrosa2.singletrail.nav.PreparedRoute
import io.github.lrosa2.singletrail.nav.RouteIdentity
import io.github.lrosa2.singletrail.nav.RoutePoint
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot

/** Test geometry is drawn in metres on a small plane around 46.5° N 7° E. */
internal object Plane {
    private const val LAT0 = 46.5
    private const val LON0 = 7.0
    private const val M_PER_DEG_LAT = Geo.EARTH_RADIUS_M * PI / 180
    private val M_PER_DEG_LON = M_PER_DEG_LAT * cos(Math.toRadians(LAT0))

    fun lat(y: Double) = LAT0 + y / M_PER_DEG_LAT
    fun lon(x: Double) = LON0 + x / M_PER_DEG_LON
}

internal fun gpxAt(x: Number, y: Number, ele: Number? = null) =
    GpxPoint(Plane.lat(y.toDouble()), Plane.lon(x.toDouble()), ele?.toDouble())

internal fun waypointAt(x: Number, y: Number, name: String) =
    GpxWaypoint(Plane.lat(y.toDouble()), Plane.lon(x.toDouble()), name = name)

/** Points every [step] metres along the polyline through [corners]; [ele] maps distance to elevation. */
internal fun line(
    vararg corners: Pair<Number, Number>,
    step: Double = 10.0,
    ele: ((Double) -> Double)? = null,
): List<GpxPoint> {
    val c = corners.map { (x, y) -> x.toDouble() to y.toDouble() }
    val out = ArrayList<GpxPoint>()
    out += gpxAt(c[0].first, c[0].second, ele?.invoke(0.0))
    var travelled = 0.0
    for (k in 1 until c.size) {
        val (x0, y0) = c[k - 1]
        val (x1, y1) = c[k]
        val len = hypot(x1 - x0, y1 - y0)
        var d = step
        while (d < len) {
            val f = d / len
            out += gpxAt(x0 + f * (x1 - x0), y0 + f * (y1 - y0), ele?.invoke(travelled + d))
            d += step
        }
        travelled += len
        out += gpxAt(x1, y1, ele?.invoke(travelled))
    }
    return out
}

internal fun track(name: String?, points: List<GpxPoint>, description: String? = null) =
    GpxTrack(name, description, GpxTrackKind.TRACK, listOf(points))

internal fun doc(
    vararg tracks: GpxTrack,
    name: String? = null,
    waypoints: List<GpxWaypoint> = emptyList(),
) = GpxDocument(name, null, null, tracks.toList(), waypoints)

internal fun newRoute(track: GpxTrack, waypoints: List<GpxWaypoint> = emptyList()): NewRoute {
    val points = track.points.map { RoutePoint(it.lat, it.lon, it.elevation) }
    return NewRoute(
        name = track.name ?: "Route",
        description = track.description,
        source = SourceKind.PICKER,
        sourceRef = "test.gpx",
        track = track,
        waypoints = waypoints,
        prepared = PreparedRoute.prepare(points),
        hashes = RouteIdentity.hashes(points),
    )
}
