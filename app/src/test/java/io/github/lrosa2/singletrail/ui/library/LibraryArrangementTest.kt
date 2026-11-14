package io.github.lrosa2.singletrail.ui.library

import io.github.lrosa2.singletrail.Plane
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

    private fun described(name: String, description: String?, stars: Int? = null) =
        template.copy(id = name, name = name, description = description, rating = stars)

    private val swiss = listOf(
        described("Farner Bike (481) · Uznach loop", "SchweizMobil local MTB route 481. Canton St. Gallen."),
        described("Hörnli Bike (33), stage 2 · Steg → Wattwil", "SchweizMobil regional MTB route 33. Cantons Zürich, St. Gallen."),
        described("Chasseral", null),
    )

    private fun search(query: String, minStars: Int? = null, routes: List<io.github.lrosa2.singletrail.data.db.RouteEntity> = swiss) =
        arrange(routes, LibrarySort.NAME, LibraryFilters(minStars = minStars), query = query).names()

    @Test
    fun `search finds words in the name or the description`() {
        assertEquals(listOf("Farner Bike (481) · Uznach loop"), search("uznach"))
        assertEquals(listOf("Farner Bike (481) · Uznach loop"), search("481"))
        assertEquals(listOf("Farner Bike (481) · Uznach loop", "Hörnli Bike (33), stage 2 · Steg → Wattwil"), search("st. gallen"))
    }

    @Test
    fun `search ignores case, accents and the German ae-oe-ue spellings`() {
        val hornli = listOf("Hörnli Bike (33), stage 2 · Steg → Wattwil")
        assertEquals(hornli, search("hornli"))
        assertEquals(hornli, search("HOERNLI"))
        assertEquals(hornli, search("zurich"))
    }

    @Test
    fun `every word has to match, in any order`() {
        assertEquals(listOf("Farner Bike (481) · Uznach loop"), search("  gallen   farner "))
        assertEquals(emptyList<String>(), search("farner wattwil"))
    }

    @Test
    fun `a blank search shows everything, and search works with the star filter`() {
        assertEquals(3, search("  ").size)
        val rated = swiss.map { if (it.name.startsWith("Farner")) it.copy(rating = 4) else it }
        assertEquals(listOf("Farner Bike (481) · Uznach loop"), search("gallen", minStars = 3, routes = rated))
    }

    @Test
    fun `names sort alphabetically regardless of case and accents`() {
        val routes = listOf(route("chasseral"), route("Berg"), route("alp"), route("Älggi"))
        assertEquals(listOf("Älggi", "alp", "Berg", "chasseral"), arrange(routes, LibrarySort.NAME).names())
    }

    @Test
    fun `stars sort best first with unrated routes last`() {
        val routes = listOf(route("A", 3), route("B"), route("C", 5), route("D", 3))
        assertEquals(listOf("C", "A", "D", "B"), arrange(routes, LibrarySort.STARS).names())
    }

    @Test
    fun `length sorts shortest first`() {
        val routes = listOf(route("Long", km = 40.0), route("Short", km = 8.0), route("Mid", km = 22.0))
        assertEquals(listOf("Short", "Mid", "Long"), arrange(routes, LibrarySort.LENGTH).names())
    }

    @Test
    fun `last ridden sorts most recent first with never-ridden routes last`() {
        val routes = listOf(route("Old", riddenAt = 100L), route("Never"), route("Recent", riddenAt = 900L))
        assertEquals(listOf("Recent", "Old", "Never"), arrange(routes, LibrarySort.LAST_RIDDEN).names())
    }

    @Test
    fun `a minimum star filter hides lower-rated and unrated routes`() {
        val routes = listOf(route("A", 2), route("B", 3), route("C"), route("D", 5))
        assertEquals(listOf("B", "D"), arrange(routes, LibrarySort.NAME, LibraryFilters(minStars = 3)).names())
    }

    /** A route straight north of the test origin, from [startKm] to [endKm]; no outline, as before it is filled in. */
    private fun at(name: String, startKm: Double, endKm: Double) = template.copy(
        id = name, name = name,
        startLat = Plane.lat(startKm * 1000), startLon = Plane.lon(0.0),
        endLat = Plane.lat(endKm * 1000), endLon = Plane.lon(0.0),
        outline = null,
    )

    /** A route along the polyline through [corners] (x, y in km), with its outline. */
    private fun through(name: String, vararg corners: Pair<Number, Number>) =
        newRoute(track(name, line(*corners.map { (x, y) -> x.toDouble() * 1000 to y.toDouble() * 1000 }.toTypedArray(), step = 50.0)))
            .toEntity(name, "routes/$name.gpx", 0L)

    private val here = Here(Plane.lat(0.0), Plane.lon(0.0))

    @Test
    fun `near me sorts by the nearest point anywhere along each route`() {
        // "Passing" runs from 10 km west to 10 km east, 1 km north of here: both ends are 10 km away.
        // "Start" begins 3 km north; "Finish" ends 2 km south.
        val routes = listOf(
            through("Start", 0 to 3, 0 to 12),
            through("Passing", -10 to 1, 10 to 1),
            through("Finish", 8 to -8, 0 to -2),
        )
        assertEquals(listOf("Passing", "Finish", "Start"), arrange(routes, LibrarySort.NEAR_ME, here = here).names())
    }

    @Test
    fun `distance to a route is to its nearest point`() {
        assertEquals(1_000.0, distanceTo(through("Passing", -10 to 1, 10 to 1), here), 5.0)
    }

    @Test
    fun `a route without an outline yet is measured to its nearer end`() {
        // "Far" starts 5 km away; "Back" starts 20 km away but ends 2 km away; "Close" starts 3 km away.
        val routes = listOf(at("Far", 5.0, 12.0), at("Back", 20.0, 2.0), at("Close", 3.0, 30.0))
        assertEquals(listOf("Back", "Close", "Far"), arrange(routes, LibrarySort.NEAR_ME, here = here).names())
        assertEquals(2_000.0, distanceTo(at("Back", 20.0, 2.0), here), 5.0)
    }

    @Test
    fun `near me without a location keeps name order`() {
        val routes = listOf(at("B", 1.0, 1.0), at("A", 9.0, 9.0))
        assertEquals(listOf("A", "B"), arrange(routes, LibrarySort.NEAR_ME, here = null).names())
    }

    private fun shaped(name: String, km: Double = 10.0, climb: Double? = 300.0, loop: Boolean = false, riddenAt: Long? = null, stars: Int? = null) =
        template.copy(id = name, name = name, distanceM = km * 1000, ascentM = climb, isLoop = loop, lastRiddenAt = riddenAt, rating = stars)

    private fun filtered(routes: List<io.github.lrosa2.singletrail.data.db.RouteEntity>, filters: LibraryFilters) =
        arrange(routes, LibrarySort.NAME, filters).names()

    @Test
    fun `length bands split at 20 and 40 km`() {
        val routes = listOf(shaped("A", km = 20.0), shaped("B", km = 20.1), shaped("C", km = 40.0), shaped("D", km = 40.1))
        assertEquals(listOf("A"), filtered(routes, LibraryFilters(length = LengthBand.UP_TO_20_KM)))
        assertEquals(listOf("B", "C"), filtered(routes, LibraryFilters(length = LengthBand.KM_20_TO_40)))
        assertEquals(listOf("D"), filtered(routes, LibraryFilters(length = LengthBand.OVER_40_KM)))
    }

    @Test
    fun `climb bands split at 500 and 1000 m and leave out routes without elevation`() {
        val routes = listOf(shaped("A", climb = 500.0), shaped("B", climb = 501.0), shaped("C", climb = 1000.0), shaped("D", climb = 1200.0), shaped("Flat file", climb = null))
        assertEquals(listOf("A"), filtered(routes, LibraryFilters(climb = ClimbBand.UP_TO_500_M)))
        assertEquals(listOf("B", "C"), filtered(routes, LibraryFilters(climb = ClimbBand.M_500_TO_1000)))
        assertEquals(listOf("D"), filtered(routes, LibraryFilters(climb = ClimbBand.OVER_1000_M)))
        assertEquals(5, filtered(routes, LibraryFilters()).size)
    }

    @Test
    fun `not ridden yet and loops only`() {
        val routes = listOf(shaped("Ridden loop", loop = true, riddenAt = 5), shaped("New loop", loop = true), shaped("New line"))
        assertEquals(listOf("New line", "New loop"), filtered(routes, LibraryFilters(notRidden = true)))
        assertEquals(listOf("New loop", "Ridden loop"), filtered(routes, LibraryFilters(loopsOnly = true)))
        assertEquals(listOf("New loop"), filtered(routes, LibraryFilters(notRidden = true, loopsOnly = true)))
    }

    @Test
    fun `filters combine with each other, search and sorting`() {
        val routes = listOf(
            shaped("Waldegg long", km = 45.0, climb = 1100.0, loop = true, stars = 4),
            shaped("Waldegg short", km = 18.0, climb = 400.0, loop = true, stars = 4),
            shaped("Waldegg longer", km = 55.0, climb = 1300.0, loop = true, stars = 5),
            shaped("Gäbris long", km = 50.0, climb = 1200.0, loop = true, stars = 5),
        )
        val filters = LibraryFilters(minStars = 4, length = LengthBand.OVER_40_KM, climb = ClimbBand.OVER_1000_M, loopsOnly = true)
        assertEquals(listOf("Waldegg long", "Waldegg longer"), arrange(routes, LibrarySort.LENGTH, filters, query = "waldegg").names())
    }

    @Test
    fun `the filter count says how many are on`() {
        assertEquals(0, LibraryFilters().count)
        assertEquals(5, LibraryFilters(2, LengthBand.UP_TO_20_KM, ClimbBand.UP_TO_500_M, notRidden = true, loopsOnly = true).count)
        assertEquals(2, LibraryFilters(minStars = 3, loopsOnly = true).count)
    }

    private val grouped = listOf(shaped("A"), shaped("B"), shaped("C"))
    private val members = mapOf("komoot" to setOf("A", "C"), "toRide" to setOf("C"))

    private fun shown(choice: CollectionChoice) =
        arrange(grouped, LibrarySort.NAME, allowed = choice.allowed(members, grouped.map { it.id })).names()

    @Test
    fun `a collection shows only its routes`() {
        assertEquals(listOf("A", "C"), shown(CollectionChoice.One("komoot")))
        assertEquals(listOf("C"), shown(CollectionChoice.One("toRide")))
        assertEquals(listOf("A", "B", "C"), shown(CollectionChoice.All))
    }

    @Test
    fun `not in a collection shows routes in none`() {
        assertEquals(listOf("B"), shown(CollectionChoice.NotInAny))
    }

    @Test
    fun `an empty collection shows nothing`() {
        assertEquals(emptyList<String>(), shown(CollectionChoice.One("empty")))
    }

    @Test
    fun `a deleted collection falls back to all routes`() {
        val existing = setOf("komoot", "toRide")
        assertEquals(CollectionChoice.All, CollectionChoice.One("deleted").resolve(existing))
        assertEquals(CollectionChoice.One("komoot"), CollectionChoice.One("komoot").resolve(existing))
        assertEquals(CollectionChoice.NotInAny, CollectionChoice.NotInAny.resolve(emptySet()))
    }
}
