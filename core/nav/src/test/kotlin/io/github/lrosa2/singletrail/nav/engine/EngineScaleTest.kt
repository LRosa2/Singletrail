package io.github.lrosa2.singletrail.nav.engine

import io.github.lrosa2.singletrail.nav.NavRoute
import io.github.lrosa2.singletrail.nav.PreparedRoute
import io.github.lrosa2.singletrail.nav.RoutePoint
import io.github.lrosa2.singletrail.nav.at
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.sin

/**
 * Spec §10: work per fix must not grow with route length (grid index, windowed matching). Times
 * are compared between a short and a long route on the same machine, so the test holds on any JVM.
 */
class EngineScaleTest {

    /** A wiggly line north, one point every 2 m: [km] kilometres, 500 points per km. */
    private fun meander(km: Int): List<RoutePoint> = (0..km * 500).map { k ->
        val y = k * 2.0
        at(40 * sin(y / 150), y)
    }

    private fun msPerFix(route: NavRoute, path: List<RoutePoint>): Double {
        // Warm up the JIT, then time a fresh ride over the same stretch.
        repeat(2) { Ride(Navigator(route), seed = 7).ride(path, noiseM = 5.0) }
        val ride = Ride(Navigator(route), seed = 7)
        val start = System.nanoTime()
        ride.ride(path, noiseM = 5.0)
        return (System.nanoTime() - start) / 1e6 / ride.updates.size
    }

    @Test
    fun `time per fix does not grow with route length`() {
        val shortRoute = meander(5)
        val longPoints = meander(100)
        val t0 = System.nanoTime()
        val longRoute = NavRoute.of(PreparedRoute.prepare(longPoints))
        val prepMs = (System.nanoTime() - t0) / 1e6
        // The same first 3 km on both routes.
        val path = longPoints.subList(0, 1500)
        val short = msPerFix(NavRoute.of(PreparedRoute.prepare(shortRoute)), path)
        val long = msPerFix(longRoute, path)
        println("Per fix: 5 km route %.3f ms, 100 km route %.3f ms; preparing 100 km (50k points, both directions): %.0f ms".format(short, long, prepMs))
        assertTrue(long <= short * 3 + 0.05, "per fix: 5 km $short ms, 100 km $long ms")
    }
}
