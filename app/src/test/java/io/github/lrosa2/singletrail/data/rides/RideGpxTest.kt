package io.github.lrosa2.singletrail.data.rides

import io.github.lrosa2.singletrail.Plane
import io.github.lrosa2.singletrail.gpx.GpxTrackKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RideGpxTest {

    /** A point [y] metres north of the test origin, [k] seconds into the ride. */
    private fun p(k: Int, y: Double, segment: Int = 1, ele: Double? = 1000.0 + y / 10) = TrackPoint(
        timeMs = 1_700_000_000_000 + k * 1000L,
        elapsedMs = 9_000L + k * 1000L,
        lat = Plane.lat(y),
        lon = Plane.lon(0.0),
        ele = ele,
        accuracy = 4f,
        speed = 5f,
        bearing = 0f,
        segment = segment,
    )

    @Test
    fun `a ride exports as one track with a segment per resume`() {
        val points = listOf(p(0, 0.0), p(1, 5.0), p(5, 25.0, segment = 2), p(6, 30.0, segment = 2))
        val doc = RideGpx.document("Morning loop", points)
        val track = doc.tracks.single()
        assertEquals(GpxTrackKind.TRACK, track.kind)
        assertEquals("Morning loop", track.name)
        assertEquals(listOf(2, 2), track.segments.map { it.size })
        assertEquals(1_700_000_005_000L, track.segments[1][0].timeEpochMs)
        assertEquals(1002.5, track.segments[1][0].elevation!!, 1e-9)
        assertEquals(points[2].lat, track.segments[1][0].lat, 0.0)
    }

    @Test
    fun `an empty ride exports no track`() {
        assertEquals(emptyList<Any>(), RideGpx.document("Nothing", emptyList()).tracks)
    }

    @Test
    fun `exported tracks read back as track points`() {
        val points = listOf(p(0, 0.0), p(1, 5.0), p(5, 25.0, segment = 2))
        val back = RideGpx.trackPoints(RideGpx.document("x", points))
        assertEquals(listOf(1, 1, 2), back.map { it.segment })
        assertEquals(points.map { it.timeMs }, back.map { it.timeMs })
        assertEquals(points.map { it.lat }, back.map { it.lat })
        assertEquals(points.map { it.ele }, back.map { it.ele })
        // What GPX can't hold comes back empty.
        assertNull(back[0].accuracy)
        assertNull(back[0].speed)
    }

    @Test
    fun `climb comes from the smoothed recorded elevation`() {
        // 600 m north climbing 60 m, one point every 5 m, with 1 m jitter that must not count.
        val points = (0..120).map { k -> p(k, k * 5.0, ele = 1000.0 + k * 0.5 + if (k % 2 == 0) 1.0 else -1.0) }
        assertEquals(60.0, RideGpx.ascent(points)!!, 4.0)
    }

    @Test
    fun `no climb without elevation`() {
        assertNull(RideGpx.ascent(listOf(p(0, 0.0, ele = null), p(1, 50.0, ele = null))))
        assertNull(RideGpx.ascent(listOf(p(0, 0.0))))
    }
}
