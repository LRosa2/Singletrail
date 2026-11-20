package io.github.lrosa2.singletrail.ui.route

import io.github.lrosa2.singletrail.data.routes.toEntity
import io.github.lrosa2.singletrail.line
import io.github.lrosa2.singletrail.nav.RoutePosition
import io.github.lrosa2.singletrail.newRoute
import io.github.lrosa2.singletrail.track
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RouteDirectionTest {

    @Test
    fun `turned round, the outline starts at the finish and the profile runs backwards`() {
        val recorded = GeometryLoad.Loaded(
            outline = listOf(RoutePosition(0.0, 47.0, 9.0), RoutePosition(100.0, 47.001, 9.0), RoutePosition(250.0, 47.002, 9.0)),
            profile = doubleArrayOf(400.0, 500.0, 600.0),
            waypoints = emptyList(),
        )
        val turned = recorded.turnedRound()
        assertEquals(listOf(47.002, 47.001, 47.0), turned.outline.map { it.lat })
        assertEquals(listOf(0.0, 150.0, 250.0), turned.outline.map { it.s })
        assertArrayEquals(doubleArrayOf(600.0, 500.0, 400.0), turned.profile)
    }

    @Test
    fun `ridden the other way, the climb becomes the descent`() {
        // 2 km climbing steadily from 400 m to 600 m.
        val route = newRoute(track("Climb", line(0 to 0, 2000 to 0, ele = { 400 + it / 10 }))).toEntity("Climb", "routes/Climb.gpx", 0L)
        assertTrue(route.ascentM!! > 150)

        assertEquals(route.ascentM to route.descentM, route.climbAndDescent(otherWay = false))
        assertEquals(route.descentM to route.ascentM, route.climbAndDescent(otherWay = true))
    }
}
