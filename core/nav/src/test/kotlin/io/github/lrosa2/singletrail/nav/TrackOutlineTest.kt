package io.github.lrosa2.singletrail.nav

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.sin
import kotlin.random.Random

class TrackOutlineTest {

    /** 2 km north, then 2 km east, a point every 10 m. */
    private val elbow = polyline(listOf(0.0 to 0.0, 0.0 to 2000.0, 2000.0 to 2000.0))

    private fun TrackOutline.distanceTo(x: Number, y: Number) =
        distanceMeters(Plane.lat(y.toDouble()), Plane.lon(x.toDouble()))

    @Test
    fun `distance is to the nearest point anywhere along the track`() {
        // 300 m east of the middle of the first leg; both ends are more than 1 km away.
        assertEquals(300.0, TrackOutline.of(elbow).distanceTo(300, 1000), 1.5)
    }

    @Test
    fun `past an end the distance is to that end`() {
        assertEquals(500.0, TrackOutline.of(elbow).distanceTo(0, -500), 1.5)
        assertEquals(400.0, TrackOutline.of(elbow).distanceTo(2400, 2000), 1.5)
    }

    @Test
    fun `distance is right tens of kilometres away`() {
        // 30 km south and 20 km west of the start: the start is the nearest point.
        val expected = Geo.distanceMeters(Plane.lat(-30_000.0), Plane.lon(-20_000.0), Plane.lat(0.0), Plane.lon(0.0))
        assertEquals(expected, TrackOutline.of(elbow).distanceTo(-20_000, -30_000), 2.0)
    }

    @Test
    fun `straight stretches shrink to their ends`() {
        assertEquals(3, TrackOutline.of(elbow).size)
    }

    @Test
    fun `a bump wider than the tolerance stays`() {
        // A 40 m bump to the east in the middle of a 1 km straight.
        val bumpy = polyline(listOf(0.0 to 0.0, 0.0 to 480.0, 40.0 to 500.0, 0.0 to 520.0, 0.0 to 1000.0))
        assertEquals(0.0, TrackOutline.of(bumpy, toleranceMeters = 15.0).distanceTo(40, 500), 1.5)
    }

    @Test
    fun `every track point stays within the tolerance of the outline`() {
        val random = Random(7)
        // A winding 20 km track with GPS-like jitter.
        val track = (0..4000).map { i ->
            val y = i * 5.0
            at(300 * sin(y / 400) + 40 * sin(y / 37) + random.nextDouble(-3.0, 3.0), y)
        }
        val outline = TrackOutline.of(track, toleranceMeters = 15.0)
        assertTrue(outline.size < track.size / 4) { "${outline.size} of ${track.size} points kept" }
        val worst = track.maxOf { outline.distanceMeters(it.lat, it.lon) }
        // The tolerance, plus the metre the stored coordinates are rounded to.
        assertTrue(worst <= 16.5) { "worst point is $worst m off the outline" }
    }

    @Test
    fun `encoding round trips exactly`() {
        val outline = TrackOutline.of(elbow)
        val text = outline.encode()
        assertEquals(outline, TrackOutline.decode(text))
        assertEquals(text, TrackOutline.decode(text).encode())
    }

    @Test
    fun `stored points are within a metre of the track`() {
        val outline = TrackOutline.of(elbow)
        assertEquals(0.0, outline.distanceTo(0, 0), 1.0)
        assertEquals(0.0, outline.distanceTo(2000, 2000), 1.0)
    }

    @Test
    fun `a one-point track is that point`() {
        val outline = TrackOutline.decode(TrackOutline.of(listOf(at(0, 0))).encode())
        assertEquals(250.0, outline.distanceTo(0, 250), 1.5)
    }

    @Test
    fun `routes across the antimeridian stay short`() {
        val outline = TrackOutline.of(listOf(RoutePoint(-17.0, 179.999), RoutePoint(-17.0, -179.999)))
        // A point right between the two, at 180°: on the line, not half a world away.
        assertEquals(0.0, outline.distanceMeters(-17.0, 180.0), 1.5)
    }
}
