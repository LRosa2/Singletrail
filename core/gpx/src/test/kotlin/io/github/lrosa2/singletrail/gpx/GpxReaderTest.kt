package io.github.lrosa2.singletrail.gpx

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

class GpxReaderTest {

    @Test
    fun `komoot-style export gives one named track with elevation and time`() {
        val doc = readFixture("komoot-style.gpx")
        assertEquals("Gurnigel loop", doc.name)
        val track = doc.tracks.single()
        assertEquals(GpxTrackKind.TRACK, track.kind)
        assertEquals(5, track.points.size)
        assertEquals(GpxPoint(46.7512, 7.4423, 1540.0, epochMs("2026-06-14T08:00:00Z")), track.points.first())
        assertEquals(GpxPoint(46.7536, 7.4466, 1550.1, epochMs("2026-06-14T08:00:40Z")), track.points.last())
    }

    @Test
    fun `segments are kept separate and in file order`() {
        val track = readFixture("garmin-style.gpx").tracks.single()
        assertEquals(listOf(3, 2), track.segments.map { it.size })
        assertEquals(47.1400, track.segments[1].first().lat)
    }

    @Test
    fun `extension values never override standard fields`() {
        // The first Garmin point carries <ns3:ele>9999</ns3:ele> inside its extensions.
        val first = readFixture("garmin-style.gpx").tracks.single().points.first()
        assertEquals(1100.0, first.elevation)
    }

    @Test
    fun `display name falls back to the first track name`() {
        val doc = readFixture("strava-style.gpx")
        assertNull(doc.name)
        assertEquals("Morning Ride", doc.displayName)
    }

    @Test
    fun `seconds-precision times are parsed`() {
        val point = readFixture("strava-style.gpx").tracks.single().points[1]
        assertEquals(epochMs("2026-07-20T06:30:04Z"), point.timeEpochMs)
    }

    @Test
    fun `planned route points become a ROUTE track`() {
        val route = readFixture("osmand-style.gpx").tracks.single()
        assertEquals(GpxTrackKind.ROUTE, route.kind)
        assertEquals("Weissenstein descent", route.name)
        assertEquals(4, route.points.size)
        assertEquals(GpxPoint(47.2399, 7.5001, 1015.0, null), route.points.last())
    }

    @Test
    fun `waypoints keep name, description and symbol`() {
        assertEquals(
            listOf(
                GpxWaypoint(47.2560, 7.5120, 1280.0, "Fountain", "Drinking water", "Drinking Water"),
                GpxWaypoint(47.2401, 7.5003, null, "Steep section", null, "Danger Area"),
            ),
            readFixture("osmand-style.gpx").waypoints,
        )
    }

    @Test
    fun `GPX 1_0 top-level name and HTML description are read as plain text`() {
        val doc = readFixture("gpx10-trailforks-style.gpx")
        assertEquals("Rote Rinne", doc.name)
        assertEquals("Black flow trail.\nClosed in winter.", doc.description)
        assertEquals(3, doc.tracks.single().points.size)
    }

    @Test
    fun `every track of a multi-track file is returned in order`() {
        assertEquals(listOf("Up", "Down"), readFixture("multi-track.gpx").tracks.map { it.name })
    }

    @Test
    fun `joined track concatenates all tracks in file order`() {
        val joined = readFixture("multi-track.gpx").joinedTrack()!!
        assertEquals("Up and down", joined.name)
        assertEquals(listOf(46.0, 46.001, 46.002, 46.003), joined.points.map { it.lat })
    }

    @Test
    fun `gpx without namespace is accepted`() {
        val doc = readXml("""<gpx version="1.1"><trk><trkseg><trkpt lat="1.5" lon="2.5"/></trkseg></trk></gpx>""")
        assertEquals(listOf(GpxPoint(1.5, 2.5)), doc.tracks.single().points)
    }

    @Test
    fun `prefixed GPX namespace is accepted`() {
        val doc = readXml(
            """<g:gpx xmlns:g="http://www.topografix.com/GPX/1/1" version="1.1"><g:trk><g:trkseg>""" +
                """<g:trkpt lat="1.5" lon="2.5"/></g:trkseg></g:trk></g:gpx>""",
        )
        assertEquals(listOf(GpxPoint(1.5, 2.5)), doc.tracks.single().points)
    }

    @Test
    fun `elements from foreign namespaces are ignored even if named like GPX`() {
        val doc = readXml(
            """<gpx xmlns="http://www.topografix.com/GPX/1/1" xmlns:x="urn:other">""" +
                """<x:trk><x:trkseg><x:trkpt lat="9" lon="9"/></x:trkseg></x:trk>""" +
                """<trk><trkseg><trkpt lat="1" lon="2"/></trkseg></trk></gpx>""",
        )
        assertEquals(listOf(GpxPoint(1.0, 2.0)), doc.tracks.single().points)
    }

    @Test
    fun `invalid coordinates are skipped and counted`() {
        val doc = readFixture("invalid-coords.gpx")
        assertEquals(listOf(46.1000, 46.1004), doc.tracks.single().points.map { it.lat })
        assertEquals(3, doc.skippedPoints)
    }

    @Test
    fun `time with an offset is converted to UTC`() {
        val doc = readXml(singlePoint("<time>2026-06-14T10:00:00+02:00</time>"))
        assertEquals(epochMs("2026-06-14T08:00:00Z"), doc.tracks.single().points.single().timeEpochMs)
    }

    @Test
    fun `unparseable time becomes null instead of failing the file`() {
        val doc = readXml(singlePoint("<time>yesterday</time>"))
        assertNull(doc.tracks.single().points.single().timeEpochMs)
    }

    @Test
    fun `non-numeric elevation becomes null`() {
        val doc = readXml(singlePoint("<ele>n/a</ele>"))
        assertNull(doc.tracks.single().points.single().elevation)
    }

    @Test
    fun `UTF-8 byte order mark is accepted`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?><gpx xmlns="http://www.topografix.com/GPX/1/1">""" +
            """<metadata><name>Zürich</name></metadata><trk><trkseg><trkpt lat="1" lon="2"/></trkseg></trk></gpx>"""
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + xml.toByteArray(Charsets.UTF_8)
        assertEquals("Zürich", reader().read(ByteArrayInputStream(bytes)).name)
    }

    @Test
    fun `markup and scripts in name and description come out as plain text`() {
        val doc = readFixture("html-description.gpx")
        assertEquals("Evil trail", doc.name)
        assertEquals("Great view\nBring water", doc.description)
    }

    @Test
    fun `empty segments and tracks without points are dropped`() {
        val doc = readXml(
            """<gpx xmlns="http://www.topografix.com/GPX/1/1"><trk><name>Empty</name></trk>""" +
                """<trk><trkseg/><trkseg><trkpt lat="1" lon="2"/></trkseg></trk></gpx>""",
        )
        assertEquals(1, doc.tracks.size)
        assertEquals(1, doc.tracks.single().segments.size)
    }
}
