package io.github.lrosa2.singletrail.gpx

import io.github.lrosa2.singletrail.gpx.GpxParseException.Reason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

/** Spec §9 S2: every GPX file is untrusted input. */
class GpxReaderSecurityTest {

    private fun reasonOf(block: () -> Unit): Reason =
        assertThrows(GpxParseException::class.java) { block() }.reason

    @Test
    fun `external entity (XXE) is rejected`() {
        assertEquals(Reason.DOCTYPE_NOT_ALLOWED, reasonOf { readFixture("malicious-xxe.gpx") })
    }

    @Test
    fun `entity expansion bomb is rejected`() {
        assertEquals(Reason.DOCTYPE_NOT_ALLOWED, reasonOf { readFixture("malicious-billion-laughs.gpx") })
    }

    @Test
    fun `any DOCTYPE is rejected even without entities`() {
        val xml = """<?xml version="1.0"?><!DOCTYPE gpx><gpx xmlns="http://www.topografix.com/GPX/1/1"/>"""
        assertEquals(Reason.DOCTYPE_NOT_ALLOWED, reasonOf { readXml(xml) })
    }

    @Test
    fun `non-GPX XML is rejected`() {
        assertEquals(Reason.NOT_GPX, reasonOf { readFixture("not-gpx.xml") })
    }

    @Test
    fun `truncated file is rejected as malformed`() {
        assertEquals(Reason.MALFORMED_XML, reasonOf { readFixture("truncated.gpx") })
    }

    @Test
    fun `empty input is rejected as not GPX`() {
        assertEquals(Reason.NOT_GPX, reasonOf { reader().read(ByteArrayInputStream(ByteArray(0))) })
    }

    @Test
    fun `binary garbage is rejected as malformed`() {
        val zipHeader = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x08, 0x00) + "garbage".toByteArray()
        assertEquals(Reason.MALFORMED_XML, reasonOf { reader().read(ByteArrayInputStream(zipHeader)) })
    }

    @Test
    fun `file over the byte limit is rejected`() {
        assertEquals(Reason.FILE_TOO_LARGE, reasonOf { readFixture("komoot-style.gpx", GpxLimits(maxFileBytes = 500)) })
    }

    @Test
    fun `file exactly at the byte limit is accepted`() {
        val bytes = singlePoint("").toByteArray(Charsets.UTF_8)
        val doc = reader(GpxLimits(maxFileBytes = bytes.size.toLong())).read(ByteArrayInputStream(bytes))
        assertEquals(1, doc.tracks.single().points.size)
    }

    @Test
    fun `more track points than the limit are rejected`() {
        assertEquals(Reason.TOO_MANY_POINTS, reasonOf { readFixture("komoot-style.gpx", GpxLimits(maxPoints = 4)) })
    }

    @Test
    fun `exactly the point limit is accepted`() {
        assertEquals(5, readFixture("komoot-style.gpx", GpxLimits(maxPoints = 5)).tracks.single().points.size)
    }

    @Test
    fun `route points count towards the point limit`() {
        assertEquals(Reason.TOO_MANY_POINTS, reasonOf { readFixture("osmand-style.gpx", GpxLimits(maxPoints = 3)) })
    }

    @Test
    fun `more waypoints than the limit are rejected`() {
        assertEquals(Reason.TOO_MANY_WAYPOINTS, reasonOf { readFixture("osmand-style.gpx", GpxLimits(maxWaypoints = 1)) })
    }

    @Test
    fun `more tracks than the limit are rejected`() {
        assertEquals(Reason.TOO_MANY_TRACKS, reasonOf { readFixture("multi-track.gpx", GpxLimits(maxTracks = 1)) })
    }
}
