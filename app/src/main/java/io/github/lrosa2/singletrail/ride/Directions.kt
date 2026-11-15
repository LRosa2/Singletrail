package io.github.lrosa2.singletrail.ride

import io.github.lrosa2.singletrail.nav.engine.LatLon
import io.github.lrosa2.singletrail.nav.engine.NavState
import io.github.lrosa2.singletrail.nav.engine.RidePhase
import java.net.URLEncoder
import java.util.Locale

/**
 * Getting to the route with another map app (D51): while the ride is finding the route or off it,
 * the point the arrow aims at goes to whichever app handles `geo:` links, which can plan a way
 * there on roads and paths.
 */
object Directions {

    /** The point to hand over: the arrow's target while finding the route or off route; null otherwise. */
    fun pointOf(state: NavState): LatLon? = when (state.phase) {
        RidePhase.ACQUIRING, RidePhase.OFF_ROUTE -> state.target?.let { LatLon(it.lat, it.lon) }
        else -> null
    }

    /** A `geo:` link with a pin on [point] labelled [name]; the label is percent-encoded, brackets too. */
    fun link(point: LatLon, name: String): String {
        val at = String.format(Locale.ROOT, "%.6f,%.6f", point.lat, point.lon)
        val label = URLEncoder.encode(name, Charsets.UTF_8).replace("+", "%20")
        return "geo:$at?q=$at($label)"
    }
}
