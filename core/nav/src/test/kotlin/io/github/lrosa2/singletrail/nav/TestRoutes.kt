package io.github.lrosa2.singletrail.nav

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/** Test routes are drawn in metres on a small plane around 46.5° N 7° E (Bernese Oberland). */
internal object Plane {
    const val LAT0 = 46.5
    const val LON0 = 7.0
    private const val M_PER_DEG_LAT = Geo.EARTH_RADIUS_M * PI / 180
    private val M_PER_DEG_LON = M_PER_DEG_LAT * cos(Math.toRadians(LAT0))

    fun lat(y: Double) = LAT0 + y / M_PER_DEG_LAT
    fun lon(x: Double) = LON0 + x / M_PER_DEG_LON
    fun x(lon: Double) = (lon - LON0) * M_PER_DEG_LON
    fun y(lat: Double) = (lat - LAT0) * M_PER_DEG_LAT
}

/**
 * A corner: [leg] metres north to the corner, then [leg] metres on bearing [angleDeg] (clockwise from
 * north), so the rider turns right by [angleDeg] (left when negative).
 */
internal fun corner(angleDeg: Double, leg: Double = 100.0, step: Double = 10.0): List<RoutePoint> {
    val a = Math.toRadians(angleDeg)
    return polyline(listOf(0.0 to 0.0, 0.0 to leg, leg * sin(a) to leg + leg * cos(a)), step)
}

/**
 * A switchback stack: a [lead]-metre run north, then [hairpins] square hairpins [width] metres wide
 * joined by [leg]-metre legs, alternating right and left, then a [lead]-metre run out.
 */
internal fun zigzag(hairpins: Int, leg: Double = 40.0, width: Double = 8.0, lead: Double = 60.0): List<RoutePoint> {
    val corners = arrayListOf(0.0 to -lead)
    var x = 0.0
    var top = true
    repeat(hairpins) {
        val y = if (top) leg else 0.0
        corners += x to y
        x += width
        corners += x to y
        top = !top
    }
    // After a hairpin at the top the route heads south; after one at the bottom, north. Either way it
    // ends well over 50 m from the start, so the stack is never mistaken for a loop.
    corners += x to (if (top) leg + lead else -lead - 60)
    return polyline(corners, step = 5.0)
}

/** Points every [step] metres on a circle of radius [r] round ([cx], [cy]), from polar angle [fromDeg] to [toDeg]. */
internal fun arc(cx: Double, cy: Double, r: Double, fromDeg: Double, toDeg: Double, step: Double = 2.0): List<RoutePoint> {
    val sweep = Math.toRadians(toDeg - fromDeg)
    val n = max(1, ceil(abs(sweep) * r / step).toInt())
    return (0..n).map { k ->
        val a = Math.toRadians(fromDeg) + sweep * k / n
        at(cx + r * cos(a), cy + r * sin(a))
    }
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
