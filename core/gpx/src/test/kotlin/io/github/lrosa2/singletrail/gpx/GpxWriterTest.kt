package io.github.lrosa2.singletrail.gpx

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.kxml2.io.KXmlParser
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

class GpxWriterTest {

    private val plannedRoute = GpxTrack(
        name = "Planned",
        description = "From the planner",
        kind = GpxTrackKind.ROUTE,
        segments = listOf(listOf(GpxPoint(47.0, 8.0), GpxPoint(47.001, 8.001))),
    )

    private val recordedTrack = GpxTrack(
        name = "Up",
        description = null,
        kind = GpxTrackKind.TRACK,
        segments = listOf(
            listOf(
                GpxPoint(46.7512345, 7.4423456, 1540.4, epochMs("2026-06-14T08:00:00.250Z")),
                GpxPoint(46.7513, 7.4424),
            ),
            listOf(GpxPoint(46.76, 7.45, -12.5)),
        ),
    )

    // Routes before tracks: the order the GPX 1.1 schema requires, so a round trip keeps it.
    private val sample = GpxDocument(
        name = "Tom & Jerry <\"fast\"> loop",
        description = "Line one\nLine two",
        creator = null,
        tracks = listOf(plannedRoute, recordedTrack),
        waypoints = listOf(GpxWaypoint(46.75, 7.44, 1500.0, "Spring", "Water", "Drinking Water")),
    )

    @Test
    fun `written document reads back identically`() {
        val back = readXml(GpxWriter.writeToString(sample))
        assertEquals(sample.copy(creator = "Singletrail"), back)
    }

    @Test
    fun `routes are written before tracks as the GPX schema requires`() {
        val doc = sample.copy(tracks = listOf(recordedTrack, plannedRoute))
        val kinds = readXml(GpxWriter.writeToString(doc)).tracks.map { it.kind }
        assertEquals(listOf(GpxTrackKind.ROUTE, GpxTrackKind.TRACK), kinds)
    }

    @Test
    fun `creator can be set by the caller`() {
        assertEquals("Singletrail 0.1.0", readXml(GpxWriter.writeToString(sample, creator = "Singletrail 0.1.0")).creator)
    }

    @Test
    fun `characters that are illegal in XML are dropped`() {
        val doc = sample.copy(name = "bad\u0001name")
        assertEquals("badname", readXml(GpxWriter.writeToString(doc)).name)
    }

    @Test
    fun `output is GPX 1_1 in the GPX namespace so other apps accept it`() {
        val parser = KXmlParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(StringReader(GpxWriter.writeToString(sample)))
            nextTag()
        }
        assertEquals("gpx", parser.name)
        assertEquals("http://www.topografix.com/GPX/1/1", parser.namespace)
        assertEquals("1.1", parser.getAttributeValue(null, "version"))
    }
}
