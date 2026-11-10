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
}
