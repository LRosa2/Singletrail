package io.github.lrosa2.singletrail.gpx

import org.kxml2.io.KXmlParser
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.time.Instant

/** On the JVM the reader runs on kxml2; on Android the app passes the platform parser. */
internal fun reader(limits: GpxLimits = GpxLimits.DEFAULT) = GpxReader({ KXmlParser() }, limits)

internal fun fixture(name: String): InputStream =
    requireNotNull(GpxReaderTest::class.java.getResourceAsStream("/gpx/$name")) { "missing fixture $name" }

internal fun readFixture(name: String, limits: GpxLimits = GpxLimits.DEFAULT): GpxDocument =
    fixture(name).use { reader(limits).read(it) }

internal fun readXml(xml: String, limits: GpxLimits = GpxLimits.DEFAULT): GpxDocument =
    reader(limits).read(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))

/** A GPX 1.1 file with a single track point at 1,2 whose children are [inner]. */
internal fun singlePoint(inner: String): String =
    """<gpx xmlns="http://www.topografix.com/GPX/1/1"><trk><trkseg><trkpt lat="1" lon="2">$inner</trkpt></trkseg></trk></gpx>"""

internal fun epochMs(iso: String): Long = Instant.parse(iso).toEpochMilli()
