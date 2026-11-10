package io.github.lrosa2.singletrail.nav.engine

import io.github.lrosa2.singletrail.nav.Geo
import io.github.lrosa2.singletrail.nav.NavRoute
import io.github.lrosa2.singletrail.nav.Plane
import io.github.lrosa2.singletrail.nav.PreparedRoute
import io.github.lrosa2.singletrail.nav.RoutePoint
import io.github.lrosa2.singletrail.nav.at
import io.github.lrosa2.singletrail.nav.polyline
import java.util.Random
import kotlin.math.max
import kotlin.math.min

/** Builds a [NavRoute] from corners in test-plane metres. */
internal fun navRoute(vararg corners: Pair<Number, Number>): NavRoute =
    NavRoute.of(PreparedRoute.prepare(polyline(corners.map { it.first.toDouble() to it.second.toDouble() })))

internal fun navRoute(points: List<RoutePoint>): NavRoute = NavRoute.of(PreparedRoute.prepare(points))

/** Test-plane metres of a fix, for readable assertions. */
internal val Fix.x get() = Plane.x(lon)
internal val Fix.y get() = Plane.y(lat)

/**
 * A simulated rider with a clock. Each call adds fixes, one every [intervalS] seconds, to [fixes]
 * and feeds them to the navigator, keeping every update.
 */
internal class Ride(val nav: Navigator, var nanos: Long = 0L, val intervalS: Double = 1.0, seed: Long = 42) {
    val updates = ArrayList<NavUpdate>()

    /** Each update with the fix that caused it (null for ticks and actions). */
    val log = ArrayList<Pair<Fix?, NavUpdate>>()
    private val random = Random(seed)

    val state: NavState get() = nav.state
    val events: List<NavEvent> get() = updates.flatMap { it.events }

    /** Rides along the polyline through [corners] at [speedMps], starting at its first corner. */
    fun ride(
        vararg corners: Pair<Number, Number>,
        speedMps: Double = 5.0,
        noiseM: Double = 0.0,
        accuracyM: Float = 5f,
        ageS: Double = 0.0,
        // Corners only: positions between them are interpolated anyway.
    ) = ride(corners.map { at(it.first, it.second) }, speedMps, noiseM, accuracyM, ageS)

    fun ride(path: List<RoutePoint>, speedMps: Double = 5.0, noiseM: Double = 0.0, accuracyM: Float = 5f, ageS: Double = 0.0) {
        val track = PreparedRoute.prepare(path)
        val stepM = speedMps * intervalS
        var d = 0.0
        // A few millimetres of slack: east-west legs in plane metres come out a hair short on the sphere.
        while (d <= track.lengthMeters + 0.05) {
            val p = track.positionAt(d)
            val behind = track.positionAt(max(0.0, d - 2))
            val ahead = track.positionAt(min(track.lengthMeters, d + 2))
            val course = Geo.bearingDeg(behind.lat, behind.lon, ahead.lat, ahead.lon)
            val x = Plane.x(p.lon) + random.nextGaussian() * noiseM
            val y = Plane.y(p.lat) + random.nextGaussian() * noiseM
            val pos = at(x, y)
            fix(Fix(nanos, pos.lat, pos.lon, accuracyM, speedMps.toFloat(), course.toFloat(), 10f, null, 12), ageS)
            d += stepM
        }
    }

    /** Stands still at ([x], [y]) for [seconds], one fix per interval. */
    fun stand(x: Number, y: Number, seconds: Double, accuracyM: Float = 5f) {
        val pos = at(x, y)
        repeat((seconds / intervalS).toInt()) {
            fix(Fix(nanos, pos.lat, pos.lon, accuracyM, 0f, null, null, null, 12))
        }
    }

    fun fix(f: Fix, ageS: Double = 0.0) {
        val u = nav.onFix(f, f.elapsedNanos + (ageS * NANOS_PER_SECOND).toLong())
        updates += u
        log += f to u
        nanos = f.elapsedNanos + (intervalS * NANOS_PER_SECOND).toLong()
    }

    /** Lets [seconds] pass without fixes, ticking once a second. */
    fun silence(seconds: Double) {
        val start = nanos - (intervalS * NANOS_PER_SECOND).toLong()
        var t = 1.0
        while (t <= seconds + 1e-9) {
            tick(start + (t * NANOS_PER_SECOND).toLong())
            t += 1.0
        }
        nanos = start + (seconds * NANOS_PER_SECOND).toLong() + (intervalS * NANOS_PER_SECOND).toLong()
    }

    fun tick(at: Long) {
        val u = nav.onTick(at)
        updates += u
        log += null to u
    }

    /** The fixes at which [predicate] matched an event. */
    fun fixesWhere(predicate: (NavEvent) -> Boolean): List<Fix> =
        log.filter { (f, u) -> f != null && u.events.any(predicate) }.map { it.first!! }

    inline fun <reified E : NavEvent> count(): Int = events.count { it is E }
}
