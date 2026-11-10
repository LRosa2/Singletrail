package io.github.lrosa2.singletrail.gpx

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

/**
 * Writes GPX 1.1 for sharing routes and exporting rides. Routes (`rte`) come before tracks (`trk`)
 * as the schema requires. Coordinates keep 7 decimals (~1 cm), elevation 1 decimal.
 */
object GpxWriter {
    private const val GPX_11 = "http://www.topografix.com/GPX/1/1"

    fun write(doc: GpxDocument, out: Appendable, creator: String = "Singletrail") {
        out.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        out.append("""<gpx version="1.1" creator="""").append(escape(creator))
            .append("""" xmlns="""").append(GPX_11).append("\">\n")

        if (doc.name != null || doc.description != null) {
            out.append("  <metadata>\n")
            element(out, 4, "name", doc.name)
            element(out, 4, "desc", doc.description)
            out.append("  </metadata>\n")
        }

        for (wpt in doc.waypoints) {
            out.append("  <wpt").append(latLon(wpt.lat, wpt.lon)).append(">\n")
            element(out, 4, "ele", wpt.elevation?.let(::elevation))
            element(out, 4, "name", wpt.name)
            element(out, 4, "desc", wpt.description)
            element(out, 4, "sym", wpt.symbol)
            out.append("  </wpt>\n")
        }

        for (route in doc.tracks.filter { it.kind == GpxTrackKind.ROUTE }) {
            out.append("  <rte>\n")
            element(out, 4, "name", route.name)
            element(out, 4, "desc", route.description)
            route.points.forEach { point(out, 4, "rtept", it) }
            out.append("  </rte>\n")
        }

        for (track in doc.tracks.filter { it.kind == GpxTrackKind.TRACK }) {
            out.append("  <trk>\n")
            element(out, 4, "name", track.name)
            element(out, 4, "desc", track.description)
            for (segment in track.segments) {
                out.append("    <trkseg>\n")
                segment.forEach { point(out, 6, "trkpt", it) }
                out.append("    </trkseg>\n")
            }
            out.append("  </trk>\n")
        }

        out.append("</gpx>\n")
    }

    fun writeToString(doc: GpxDocument, creator: String = "Singletrail"): String =
        buildString { write(doc, this, creator) }

    private fun point(out: Appendable, indent: Int, tag: String, p: GpxPoint) {
        val pad = " ".repeat(indent)
        out.append(pad).append('<').append(tag).append(latLon(p.lat, p.lon))
        if (p.elevation == null && p.timeEpochMs == null) {
            out.append("/>\n")
            return
        }
        out.append(">\n")
        element(out, indent + 2, "ele", p.elevation?.let(::elevation))
        element(out, indent + 2, "time", p.timeEpochMs?.let { Instant.ofEpochMilli(it).toString() })
        out.append(pad).append("</").append(tag).append(">\n")
    }

    private fun element(out: Appendable, indent: Int, tag: String, value: String?) {
        if (value == null) return
        out.append(" ".repeat(indent)).append('<').append(tag).append('>')
            .append(escape(value))
            .append("</").append(tag).append(">\n")
    }

    private fun latLon(lat: Double, lon: Double) = """ lat="${decimal(lat, 7)}" lon="${decimal(lon, 7)}""""

    private fun elevation(value: Double) = decimal(value, 1)

    private fun decimal(value: Double, scale: Int): String =
        BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    /** Escapes markup and drops characters that XML 1.0 cannot contain at all. */
    private fun escape(text: String): String = buildString(text.length) {
        for (ch in text) {
            when {
                ch == '&' -> append("&amp;")
                ch == '<' -> append("&lt;")
                ch == '>' -> append("&gt;")
                ch == '"' -> append("&quot;")
                ch == '\t' || ch == '\n' || ch == '\r' -> append(ch)
                ch < ' ' || ch == '￾' || ch == '￿' -> Unit
                else -> append(ch)
            }
        }
    }
}
