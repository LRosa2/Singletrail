package io.github.lrosa2.singletrail.backup

import io.github.lrosa2.singletrail.gpx.GpxDocument
import io.github.lrosa2.singletrail.gpx.GpxWaypoint
import io.github.lrosa2.singletrail.line
import io.github.lrosa2.singletrail.track
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RouteGpxTest {

    private val stored = GpxDocument(
        "Old name", "Old text", "Singletrail",
        listOf(track("Old name", line(0 to 0, 300 to 0), "Old text")),
        listOf(GpxWaypoint(46.5, 7.0, name = "Bench")),
    )

    @Test
    fun `a shared route carries its current name and description`() {
        val shared = RouteGpx.forSharing(stored, "Ridge loop", "Best in autumn")
        assertEquals("Ridge loop", shared.name)
        assertEquals("Best in autumn", shared.description)
        assertEquals("Ridge loop", shared.tracks.single().name)
        assertEquals("Best in autumn", shared.tracks.single().description)
    }

    @Test
    fun `geometry and waypoints stay as they are`() {
        val shared = RouteGpx.forSharing(stored, "Ridge loop", null)
        assertEquals(stored.tracks.single().points, shared.tracks.single().points)
        assertEquals(stored.waypoints, shared.waypoints)
        assertNull(shared.description)
    }
}
