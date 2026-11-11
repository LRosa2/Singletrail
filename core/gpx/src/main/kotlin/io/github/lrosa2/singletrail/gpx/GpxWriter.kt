package io.github.lrosa2.singletrail.gpx


/**
 * Writes GPX 1.1 for sharing routes and exporting rides. Routes (`rte`) come before tracks (`trk`)
 * as the schema requires. Coordinates keep 7 decimals (~1 cm), elevation 1 decimal.
 */
object GpxWriter {
    private const val GPX_11 = "http://www.topografix.com/GPX/1/1"
    private val PADS = Array(12) { " ".repeat(it) }

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

    /** One point, built in a single buffer: routes can have 200,000 of them (spec §5.2). */
    private fun point(out: Appendable, indent: Int, tag: String, p: GpxPoint) {
        val line = StringBuilder(128)
        line.append(PADS[indent]).append('<').append(tag).append(" lat=\"")
        Decimals.appendPlain(line, p.lat, 7)
        line.append("\" lon=\"")
        Decimals.appendPlain(line, p.lon, 7)
        line.append('"')
        if (p.elevation == null && p.timeEpochMs == null) {
            out.append(line.append("/>\n"))
            return
        }
        line.append(">\n")
        p.elevation?.let {
            line.append(PADS[indent + 2]).append("<ele>")
            Decimals.appendPlain(line, it, 1)
            line.append("</ele>\n")
        }
        p.timeEpochMs?.let {
            line.append(PADS[indent + 2]).append("<time>")
            IsoTime.appendInstant(line, it)
            line.append("</time>\n")
        }
        out.append(line.append(PADS[indent]).append("</").append(tag).append(">\n"))
    }

    private fun element(out: Appendable, indent: Int, tag: String, value: String?) {
        if (value == null) return
        out.append(" ".repeat(indent)).append('<').append(tag).append('>')
            .append(escape(value))
            .append("</").append(tag).append(">\n")
    }

    private fun latLon(lat: Double, lon: Double) = """ lat="${decimal(lat, 7)}" lon="${decimal(lon, 7)}""""

    private fun elevation(value: Double) = decimal(value, 1)

    private fun decimal(value: Double, scale: Int): String = Decimals.plain(value, scale)

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
