package io.github.lrosa2.singletrail.backup

import io.github.lrosa2.singletrail.data.settings.RideSettings
import io.github.lrosa2.singletrail.gpx.GpxDocument
import io.github.lrosa2.singletrail.gpx.GpxReader
import io.github.lrosa2.singletrail.gpx.GpxWaypoint
import io.github.lrosa2.singletrail.line
import io.github.lrosa2.singletrail.track
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.kxml2.io.KXmlParser
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupZipTest {

    @TempDir
    lateinit var dir: File

    private val reader = GpxReader({ KXmlParser() })
    private fun open(file: File, limits: ArchiveLimits = ArchiveLimits()) = BackupArchive.open(file, { reader.read(it) }, limits)

    private val east = GpxDocument("East", "Along the ridge", null, listOf(track("East", line(0 to 0, 500 to 0), "Along the ridge")), listOf(GpxWaypoint(46.5, 7.0, name = "Bench")))
    private val north = GpxDocument("North", null, null, listOf(track("North", line(0 to 0, 0 to 400))), emptyList())

    private val routes = listOf(
        BackupRoute("r1", "East", "Along the ridge", rating = 4, sourceKind = "PICKER", sourceRef = "east.gpx", importedAt = 10, updatedAt = 20, lastRiddenAt = 30, geometryHash = "h1", file = "routes/r1.gpx"),
        BackupRoute("r2", "North", null, rating = null, sourceKind = "SHARE", sourceRef = null, importedAt = 11, updatedAt = 11, lastRiddenAt = null, geometryHash = "h2", file = "routes/r2.gpx"),
    )
    private val rides = listOf(
        BackupRide("d1", routeId = "r1", routeGeometryHash = "h1", routeName = "East", direction = "FORWARD", startedAt = 100, endedAt = 200, status = "FINISHED", distanceM = 512.0, movingTimeS = 95, ascentM = 12.0, segmentCount = 1, file = "rides/d1.gpx"),
    )

    private fun writeFull(file: File) {
        file.outputStream().use { out ->
            BackupZip.writeFull(
                out,
                BackupManifest(kind = BackupManifest.KIND_FULL, app = "0.1.0", createdAt = 1234, owner = "me", routes = 2, rides = 1),
                routes,
                { if (it.id == "r1") east else north },
                rides,
                { north },
                BackupSettings(offRouteMeters = 40, beepVolume = 0.5f, compassOff = true, debugTrace = false),
            )
        }
    }

    @Test
    fun `every ride setting goes into a backup and comes back`() {
        val mine = RideSettings(
            offRouteMeters = 45, beepVolume = 0.3f, compassOff = true, debugTrace = true,
            dimBetweenTurns = false, dimLevel = 0.4f, lowPowerAt = 35,
        )
        val json = BackupJson.encodeToString(BackupSettings.serializer(), BackupSettings.of(mine))
        val back = BackupJson.decodeFromString(BackupSettings.serializer(), json)
        assertEquals(mine, back.applyTo(RideSettings()))
    }

    @Test
    fun `a backup from before the screen settings keeps the phone's own`() {
        val old = BackupJson.decodeFromString(
            BackupSettings.serializer(),
            """{"offRouteMeters":50,"beepVolume":0.2,"compassOff":false,"debugTrace":false}""",
        )
        val phone = RideSettings(dimBetweenTurns = false, dimLevel = 0.3f, lowPowerAt = 40)
        val merged = old.applyTo(phone)
        assertEquals(50, merged.offRouteMeters)
        assertEquals(0.2f, merged.beepVolume)
        assertEquals(false, merged.dimBetweenTurns)
        assertEquals(0.3f, merged.dimLevel)
        assertEquals(40, merged.lowPowerAt)
    }

    @Test
    fun `a full backup reads back`() {
        val file = File(dir, "backup.zip")
        writeFull(file)
        open(file).use { archive ->
            assertEquals(BackupManifest.KIND_FULL, archive.manifest!!.kind)
            assertEquals("me", archive.manifest.owner)
            assertEquals(BackupKind.FULL, archive.kind)
            assertEquals(routes, archive.routes())
            assertEquals(rides, archive.rides())
            assertEquals(BackupSettings(40, 0.5f, compassOff = true, debugTrace = false), archive.settings())
            val gpx = archive.gpx("routes/r1.gpx")
            assertEquals("East", gpx.name)
            assertEquals("Bench", gpx.waypoints.single().name)
            assertEquals(east.tracks.single().points.size, gpx.tracks.single().points.size)
        }
    }

    @Test
    fun `a routes zip holds one GPX per route and says what it is`() {
        val file = File(dir, "routes.zip")
        file.outputStream().use { BackupZip.writeRoutes(it, "0.1.0", 1234, listOf("East" to east, "East" to north)) }
        open(file).use { archive ->
            assertEquals(BackupKind.ROUTES, archive.kind)
            assertEquals(listOf("East.gpx", "East (2).gpx"), archive.gpxEntries())
            assertEquals("North", archive.gpx("East (2).gpx").name)
        }
    }

    @Test
    fun `a zip of GPX files without a manifest is a routes zip`() {
        val file = File(dir, "plain.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("trails/one.gpx"))
            zip.write(io.github.lrosa2.singletrail.gpx.GpxWriter.writeToString(north).toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("readme.txt"))
            zip.write("hello".toByteArray())
            zip.closeEntry()
        }
        open(file).use { archive ->
            assertNull(archive.manifest)
            assertEquals(BackupKind.ROUTES, archive.kind)
            assertEquals(listOf("trails/one.gpx"), archive.gpxEntries())
        }
    }

    @Test
    fun `entries that try to leave the archive are ignored`() {
        val file = File(dir, "evil.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            for (name in listOf("../evil.gpx", "/abs.gpx", "ok.gpx")) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(io.github.lrosa2.singletrail.gpx.GpxWriter.writeToString(north).toByteArray())
                zip.closeEntry()
            }
        }
        open(file).use { assertEquals(listOf("ok.gpx"), it.gpxEntries()) }
    }

    @Test
    fun `too many entries is refused`() {
        val file = File(dir, "many.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            repeat(30) {
                zip.putNextEntry(ZipEntry("f$it.gpx"))
                zip.closeEntry()
            }
        }
        val e = assertThrows<ArchiveException> { open(file, ArchiveLimits(maxEntries = 20)) }
        assertEquals(ArchiveException.Reason.TOO_MANY_ENTRIES, e.reason)
    }

    @Test
    fun `an entry bigger than its limit is refused when read`() {
        val file = File(dir, "big.zip")
        writeFull(file)
        // Big enough for the manifest (read on open), too small for routes.json.
        open(file, ArchiveLimits(maxJsonBytes = 300)).use { archive ->
            val e = assertThrows<ArchiveException> { archive.routes() }
            assertEquals(ArchiveException.Reason.ENTRY_TOO_LARGE, e.reason)
        }
    }

    @Test
    fun `a file that is not a zip is refused`() {
        val file = File(dir, "not.zip").apply { writeText("<gpx/>") }
        val e = assertThrows<ArchiveException> { open(file) }
        assertEquals(ArchiveException.Reason.NOT_A_ZIP, e.reason)
    }

    @Test
    fun `a GPX entry that is not safe GPX fails like a GPX file`() {
        val file = File(dir, "xxe.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("x.gpx"))
            zip.write("""<?xml version="1.0"?><!DOCTYPE gpx [<!ENTITY x SYSTEM "file:///etc/passwd">]><gpx>&x;</gpx>""".toByteArray())
            zip.closeEntry()
        }
        open(file).use { archive ->
            assertThrows<io.github.lrosa2.singletrail.gpx.GpxParseException> { archive.gpx("x.gpx") }
        }
    }
}
