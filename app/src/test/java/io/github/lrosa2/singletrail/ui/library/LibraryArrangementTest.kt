package io.github.lrosa2.singletrail.ui.library

import io.github.lrosa2.singletrail.data.routes.toEntity
import io.github.lrosa2.singletrail.line
import io.github.lrosa2.singletrail.newRoute
import io.github.lrosa2.singletrail.track
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LibraryArrangementTest {

    private val template = newRoute(track("x", line(0 to 0, 100 to 0))).toEntity("t", "routes/t.gpx", 0L)

    private fun route(name: String, stars: Int? = null, km: Double = 10.0, riddenAt: Long? = null) =
        template.copy(id = name, name = name, rating = stars, distanceM = km * 1000, lastRiddenAt = riddenAt)

    private fun List<io.github.lrosa2.singletrail.data.db.RouteEntity>.names() = map { it.name }

    @Test
    fun `names sort alphabetically regardless of case and accents`() {
        val routes = listOf(route("chasseral"), route("Berg"), route("alp"), route("Älggi"))
        assertEquals(listOf("Älggi", "alp", "Berg", "chasseral"), arrange(routes, LibrarySort.NAME, null).names())
    }

    @Test
    fun `stars sort best first with unrated routes last`() {
        val routes = listOf(route("A", 3), route("B"), route("C", 5), route("D", 3))
        assertEquals(listOf("C", "A", "D", "B"), arrange(routes, LibrarySort.STARS, null).names())
    }

    @Test
    fun `length sorts shortest first`() {
        val routes = listOf(route("Long", km = 40.0), route("Short", km = 8.0), route("Mid", km = 22.0))
        assertEquals(listOf("Short", "Mid", "Long"), arrange(routes, LibrarySort.LENGTH, null).names())
    }

    @Test
    fun `last ridden sorts most recent first with never-ridden routes last`() {
        val routes = listOf(route("Old", riddenAt = 100L), route("Never"), route("Recent", riddenAt = 900L))
        assertEquals(listOf("Recent", "Old", "Never"), arrange(routes, LibrarySort.LAST_RIDDEN, null).names())
    }

    @Test
    fun `a minimum star filter hides lower-rated and unrated routes`() {
        val routes = listOf(route("A", 2), route("B", 3), route("C"), route("D", 5))
        assertEquals(listOf("B", "D"), arrange(routes, LibrarySort.NAME, minStars = 3).names())
    }

    /** A route starting [startKm] and ending [endKm] north of the test origin. */
    private fun at(name: String, startKm: Double, endKm: Double) = template.copy(
        id = name, name = name,
        startLat = io.github.lrosa2.singletrail.Plane.lat(startKm * 1000), startLon = io.github.lrosa2.singletrail.Plane.lon(0.0),
        endLat = io.github.lrosa2.singletrail.Plane.lat(endKm * 1000), endLon = io.github.lrosa2.singletrail.Plane.lon(0.0),
    )

    private val here = Here(io.github.lrosa2.singletrail.Plane.lat(0.0), io.github.lrosa2.singletrail.Plane.lon(0.0))

    @Test
    fun `near me sorts by the nearer end of each route`() {
        // "Far" starts 5 km away; "Back" starts 20 km away but ends 2 km away; "Close" starts 3 km away.
        val routes = listOf(at("Far", 5.0, 12.0), at("Back", 20.0, 2.0), at("Close", 3.0, 30.0))
        assertEquals(listOf("Back", "Close", "Far"), arrange(routes, LibrarySort.NEAR_ME, null, here).names())
    }

    @Test
    fun `near me without a location keeps name order`() {
        val routes = listOf(at("B", 1.0, 1.0), at("A", 9.0, 9.0))
        assertEquals(listOf("A", "B"), arrange(routes, LibrarySort.NEAR_ME, null, here = null).names())
    }

    @Test
    fun `distance to a route is to its nearer end`() {
        assertEquals(2_000.0, distanceTo(at("Back", 20.0, 2.0), here), 5.0)
    }
}
