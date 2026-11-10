package io.github.lrosa2.singletrail.data.gpx

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lrosa2.singletrail.gpx.GpxParseException
import io.github.lrosa2.singletrail.gpx.GpxParseException.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The JVM tests run the reader on kxml2; this runs it on Android's own parser, which is what the
 * app ships with. It checks the security guarantees (spec §9 S2) hold on the real platform too.
 */
@RunWith(AndroidJUnit4::class)
class AndroidGpxReaderTest {

    private fun read(xml: String) = androidGpxReader().read(xml.byteInputStream())

    private fun reasonOf(xml: String): Reason =
        assertThrows(GpxParseException::class.java) { read(xml) }.reason

    @Test
    fun readsAPlainTrack() {
        val doc = read(
            """<?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" creator="test" xmlns="http://www.topografix.com/GPX/1/1">
              <metadata><name>Gurnigel loop</name></metadata>
              <trk><trkseg>
                <trkpt lat="46.7512" lon="7.4423"><ele>1540.0</ele></trkpt>
                <trkpt lat="46.7518" lon="7.4431"><ele>1544.5</ele></trkpt>
              </trkseg></trk>
            </gpx>""",
        )
        assertEquals("Gurnigel loop", doc.name)
        assertEquals(listOf(1540.0, 1544.5), doc.tracks.single().points.map { it.elevation })
    }

    @Test
    fun ignoresForeignNamespaceElements() {
        val doc = read(
            """<gpx xmlns="http://www.topografix.com/GPX/1/1" xmlns:x="urn:other">
              <x:trk><x:trkseg><x:trkpt lat="9" lon="9"/></x:trkseg></x:trk>
              <trk><trkseg><trkpt lat="1" lon="2"/></trkseg></trk></gpx>""",
        )
        assertEquals(listOf(1.0), doc.tracks.single().points.map { it.lat })
    }

    @Test
    fun rejectsExternalEntity() {
        assertEquals(
            Reason.DOCTYPE_NOT_ALLOWED,
            reasonOf(
                """<?xml version="1.0"?>
                <!DOCTYPE gpx [<!ENTITY secret SYSTEM "file:///system/etc/hosts">]>
                <gpx xmlns="http://www.topografix.com/GPX/1/1"><metadata><name>&secret;</name></metadata></gpx>""",
            ),
        )
    }

    @Test
    fun rejectsEntityExpansionBomb() {
        val entities = (1..9).joinToString("\n") { i -> """<!ENTITY lol$i "${"&lol${i - 1};".repeat(10)}">""" }
        assertEquals(
            Reason.DOCTYPE_NOT_ALLOWED,
            reasonOf(
                """<?xml version="1.0"?>
                <!DOCTYPE gpx [<!ENTITY lol0 "lol">
                $entities
                ]>
                <gpx xmlns="http://www.topografix.com/GPX/1/1"><metadata><name>&lol9;</name></metadata></gpx>""",
            ),
        )
    }
}
