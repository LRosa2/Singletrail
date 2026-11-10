package io.github.lrosa2.singletrail.gpx

import io.github.lrosa2.singletrail.gpx.GpxParseException.Reason
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException
import java.io.InputStream
import java.io.PushbackInputStream
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import kotlin.math.abs

/**
 * Reads GPX 1.0 / 1.1 from untrusted input (spec §8.4, §9 S2).
 *
 * [newParser] supplies the pull parser: `Xml.newPullParser()` on Android, kxml2 on the JVM.
 * Any DOCTYPE is rejected before the root element, so no entity (external or internal) is ever
 * expanded. The caller owns [read]'s stream and closes it.
 */
class GpxReader(
    private val newParser: () -> XmlPullParser,
    private val limits: GpxLimits = GpxLimits.DEFAULT,
) {
    fun read(input: InputStream): GpxDocument {
        val limited = LimitedInputStream(input, limits.maxFileBytes)
        try {
            val parser = newParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            parser.setInput(skipUtf8Bom(limited), null)
            return DocumentParser(parser, limits).parse()
        } catch (e: GpxParseException) {
            throw e
        } catch (e: Exception) {
            if (e.causes().any { it is ByteLimitExceeded }) {
                throw GpxParseException(Reason.FILE_TOO_LARGE, "File is larger than ${limits.maxFileBytes} bytes", e)
            }
            if (e is IOException && e !is java.io.EOFException && e.causes().none { it is XmlPullParserException }) {
                throw e // a real I/O failure of the caller's stream, not a content problem
            }
            if (limited.bytesRead == 0L) throw GpxParseException(Reason.NOT_GPX, "The file is empty", e)
            throw GpxParseException(Reason.MALFORMED_XML, "Not well-formed XML: ${e.message}", e)
        }
    }

    private fun Throwable.causes(): Sequence<Throwable> = generateSequence(this) { it.cause }

    private fun skipUtf8Bom(input: InputStream): InputStream {
        val stream = PushbackInputStream(input, 3)
        val head = ByteArray(3)
        var n = 0
        while (n < 3) {
            val r = stream.read(head, n, 3 - n)
            if (r < 0) break
            n += r
        }
        val isBom = n == 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()
        if (!isBom && n > 0) stream.unread(head, 0, n)
        return stream
    }
}

private const val GPX_10 = "http://www.topografix.com/GPX/1/0"
private const val GPX_11 = "http://www.topografix.com/GPX/1/1"
private const val NAME_MAX = 200
private const val SYMBOL_MAX = 100
private const val RAW_TEXT_MAX = 100_000

/** One parse of one document; holds the running counts used for the limits. */
private class DocumentParser(private val p: XmlPullParser, private val limits: GpxLimits) {
    private var name: String? = null
    private var description: String? = null
    private val tracks = mutableListOf<GpxTrack>()
    private val waypoints = mutableListOf<GpxWaypoint>()
    private var trackElements = 0
    private var acceptedPoints = 0
    private var skippedPoints = 0

    fun parse(): GpxDocument {
        moveToRootElement()
        if (p.name != "gpx" || !isGpxNamespace(p.namespace)) {
            throw GpxParseException(Reason.NOT_GPX, "Root element is <${p.name}>, not <gpx>")
        }
        val creator = TextSanitizer.plainText(p.getAttributeValue(null, "creator"), NAME_MAX)
        children { tag ->
            when (tag) {
                "metadata" -> readNameAndDescription()
                "name" -> name = TextSanitizer.plainText(text(), NAME_MAX) // GPX 1.0
                "desc" -> description = TextSanitizer.plainText(text()) // GPX 1.0
                "wpt" -> readWaypoint()
                "rte" -> readTrack(GpxTrackKind.ROUTE)
                "trk" -> readTrack(GpxTrackKind.TRACK)
                else -> skip()
            }
        }
        return GpxDocument(name, description, creator, tracks.toList(), waypoints.toList(), skippedPoints)
    }

    /** Walks the prolog. A DOCTYPE is refused here, before any entity could be expanded. */
    private fun moveToRootElement() {
        while (true) {
            when (p.nextToken()) {
                XmlPullParser.START_TAG -> return
                XmlPullParser.DOCDECL -> throw GpxParseException(Reason.DOCTYPE_NOT_ALLOWED, "DOCTYPE declarations are not allowed in GPX files")
                XmlPullParser.END_DOCUMENT -> throw GpxParseException(Reason.NOT_GPX, "No root element")
                XmlPullParser.TEXT -> if (!p.text.isNullOrBlank()) {
                    throw GpxParseException(Reason.MALFORMED_XML, "Text before the root element")
                }
                else -> Unit // XML declaration, comments, processing instructions, whitespace
            }
        }
    }

    private fun readNameAndDescription() = children { tag ->
        when (tag) {
            "name" -> name = TextSanitizer.plainText(text(), NAME_MAX)
            "desc" -> description = TextSanitizer.plainText(text())
            else -> skip()
        }
    }

    private fun readWaypoint() {
        val lat = coordinate("lat", 90.0)
        val lon = coordinate("lon", 180.0)
        var ele: Double? = null
        var wptName: String? = null
        var wptDesc: String? = null
        var symbol: String? = null
        children { tag ->
            when (tag) {
                "ele" -> ele = elevation(text())
                "name" -> wptName = TextSanitizer.plainText(text(), NAME_MAX)
                "desc" -> wptDesc = TextSanitizer.plainText(text())
                "sym" -> symbol = TextSanitizer.plainText(text(), SYMBOL_MAX)
                else -> skip()
            }
        }
        if (lat == null || lon == null) return
        if (waypoints.size >= limits.maxWaypoints) {
            throw GpxParseException(Reason.TOO_MANY_WAYPOINTS, "More than ${limits.maxWaypoints} waypoints")
        }
        waypoints += GpxWaypoint(lat, lon, ele, wptName, wptDesc, symbol)
    }

    private fun readTrack(kind: GpxTrackKind) {
        if (++trackElements > limits.maxTracks) {
            throw GpxParseException(Reason.TOO_MANY_TRACKS, "More than ${limits.maxTracks} tracks and routes")
        }
        var trackName: String? = null
        var trackDesc: String? = null
        val segments = mutableListOf<List<GpxPoint>>()
        val routePoints = mutableListOf<GpxPoint>()
        children { tag ->
            when {
                tag == "name" -> trackName = TextSanitizer.plainText(text(), NAME_MAX)
                tag == "desc" -> trackDesc = TextSanitizer.plainText(text())
                kind == GpxTrackKind.TRACK && tag == "trkseg" -> readSegment().takeIf { it.isNotEmpty() }?.let(segments::add)
                kind == GpxTrackKind.ROUTE && tag == "rtept" -> readPoint()?.let(routePoints::add)
                else -> skip()
            }
        }
        if (routePoints.isNotEmpty()) segments += routePoints.toList()
        if (segments.isNotEmpty()) tracks += GpxTrack(trackName, trackDesc, kind, segments.toList())
    }

    private fun readSegment(): List<GpxPoint> {
        val points = mutableListOf<GpxPoint>()
        children { tag -> if (tag == "trkpt") readPoint()?.let(points::add) else skip() }
        return points
    }

    private fun readPoint(): GpxPoint? {
        val lat = coordinate("lat", 90.0)
        val lon = coordinate("lon", 180.0)
        var ele: Double? = null
        var time: Long? = null
        children { tag ->
            when (tag) {
                "ele" -> ele = elevation(text())
                "time" -> time = parseTime(text())
                else -> skip()
            }
        }
        if (lat == null || lon == null) {
            skippedPoints++
            return null
        }
        if (++acceptedPoints > limits.maxPoints) {
            throw GpxParseException(Reason.TOO_MANY_POINTS, "More than ${limits.maxPoints} points")
        }
        return GpxPoint(lat, lon, ele, time)
    }

    // ---- pull-parser helpers -------------------------------------------------------------

    /** Calls [onChild] for each child element in a GPX namespace, skipping foreign ones; stops at the end tag. */
    private inline fun children(onChild: (String) -> Unit) {
        val depth = p.depth
        while (true) {
            when (p.next()) {
                XmlPullParser.START_TAG -> if (isGpxNamespace(p.namespace)) onChild(p.name) else skip()
                XmlPullParser.END_TAG -> if (p.depth == depth) return
                XmlPullParser.END_DOCUMENT -> throw unexpectedEnd()
            }
        }
    }

    /** All text inside the current element (nested markup included as text), bounded. */
    private fun text(): String {
        val depth = p.depth
        val out = StringBuilder()
        while (true) {
            when (p.next()) {
                XmlPullParser.TEXT -> if (out.length < RAW_TEXT_MAX) out.append(p.text)
                XmlPullParser.END_TAG -> if (p.depth == depth) return out.toString()
                XmlPullParser.END_DOCUMENT -> throw unexpectedEnd()
            }
        }
    }

    private fun skip() {
        val depth = p.depth
        while (true) {
            when (p.next()) {
                XmlPullParser.END_TAG -> if (p.depth == depth) return
                XmlPullParser.END_DOCUMENT -> throw unexpectedEnd()
            }
        }
    }

    private fun unexpectedEnd() = GpxParseException(Reason.MALFORMED_XML, "Unexpected end of file")

    private fun isGpxNamespace(ns: String?) = ns.isNullOrEmpty() || ns == GPX_11 || ns == GPX_10

    // ---- value parsing ------------------------------------------------------------------

    private fun coordinate(attribute: String, maxAbs: Double): Double? =
        p.getAttributeValue(null, attribute)?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() && abs(it) <= maxAbs }

    private fun elevation(raw: String): Double? =
        raw.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it in -1_000.0..10_000.0 }

    private fun parseTime(raw: String): Long? {
        val s = raw.trim()
        if (s.isEmpty() || s.length > 40) return null
        return try {
            OffsetDateTime.parse(s).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            try {
                LocalDateTime.parse(s).toInstant(ZoneOffset.UTC).toEpochMilli() // no zone given: assume UTC
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }
}

/** Thrown by [LimitedInputStream] once more than the allowed bytes have been read. */
private class ByteLimitExceeded(limit: Long) : IOException("More than $limit bytes")

/** Counts bytes and fails as soon as one byte past [maxBytes] is read; exactly [maxBytes] is fine. */
private class LimitedInputStream(private val input: InputStream, private val maxBytes: Long) : InputStream() {
    var bytesRead = 0L
        private set

    override fun read(): Int {
        val b = input.read()
        if (b >= 0) count(1)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        val allowed = minOf(len.toLong(), maxBytes - bytesRead + 1).toInt().coerceAtLeast(1)
        val n = input.read(b, off, allowed)
        if (n > 0) count(n)
        return n
    }

    private fun count(n: Int) {
        bytesRead += n
        if (bytesRead > maxBytes) throw ByteLimitExceeded(maxBytes)
    }
}
