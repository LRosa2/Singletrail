package io.github.lrosa2.singletrail.nav

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot

/** Test routes are drawn in metres on a small plane around 46.5° N 7° E (Bernese Oberland). */
internal object Plane {
    const val LAT0 = 46.5
    const val LON0 = 7.0
    private const val M_PER_DEG_LAT = Geo.EARTH_RADIUS_M * PI / 180
    private val M_PER_DEG_LON = M_PER_DEG_LAT * cos(Math.toRadians(LAT0))

    fun lat(y: Double) = LAT0 + y / M_PER_DEG_LAT
    fun lon(x: Double) = LON0 + x / M_PER_DEG_LON
    fun x(lon: Double) = (lon - LON0) * M_PER_DEG_LON
}

/** A point [x] metres east and [y] metres north of the test origin. */
internal fun at(x: Number, y: Number, ele: Number? = null) =
    RoutePoint(Plane.lat(y.toDouble()), Plane.lon(x.toDouble()), ele?.toDouble())

/**
 * Points every [step] metres along the polyline through [corners] (x, y in metres), keeping every
 * corner. [ele] gives the elevation as a function of the distance travelled along the line.
 */
internal fun polyline(
    corners: List<Pair<Double, Double>>,
    step: Double = 10.0,
    ele: ((Double) -> Double)? = null,
): List<RoutePoint> {
    val out = ArrayList<RoutePoint>()
    out += at(corners[0].first, corners[0].second, ele?.invoke(0.0))
    var travelled = 0.0
    for (k in 1 until corners.size) {
        val (x0, y0) = corners[k - 1]
        val (x1, y1) = corners[k]
        val len = hypot(x1 - x0, y1 - y0)
        var d = step
        while (d < len) {
            val f = d / len
            out += at(x0 + f * (x1 - x0), y0 + f * (y1 - y0), ele?.invoke(travelled + d))
            d += step
        }
        travelled += len
        out += at(x1, y1, ele?.invoke(travelled))
    }
    return out
}
