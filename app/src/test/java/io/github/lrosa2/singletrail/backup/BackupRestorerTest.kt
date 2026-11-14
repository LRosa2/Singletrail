package io.github.lrosa2.singletrail.backup

import io.github.lrosa2.singletrail.Plane
import io.github.lrosa2.singletrail.data.db.SourceKind
import io.github.lrosa2.singletrail.data.rides.RideGpx
import io.github.lrosa2.singletrail.data.rides.TrackPoint
import io.github.lrosa2.singletrail.gpx.GpxDocument
import io.github.lrosa2.singletrail.gpx.GpxReader
import io.github.lrosa2.singletrail.gpx.GpxTrack
import io.github.lrosa2.singletrail.gpx.GpxTrackKind
import io.github.lrosa2.singletrail.importing.NewRoute
import io.github.lrosa2.singletrail.line
import io.github.lrosa2.singletrail.nav.RouteIdentity
import io.github.lrosa2.singletrail.track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.kxml2.io.KXmlParser
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupRestorerTest {

    @TempDir
    lateinit var dir: File

    private val reader = GpxReader({ KXmlParser() })

    private class Added(val route: NewRoute, val rating: Int?, val importedAt: Long, val lastRiddenAt: Long?)
    private class AddedRide(val ride: BackupRide, val routeId: String?, val points: List<TrackPoint>)

    private class FakeTarget : RestoreTarget {
        val byHash = HashMap<String, String>()
        val routes = ArrayList<Added>()
        val rides = ArrayList<AddedRide>()
        val existingRides = HashSet<String>()

        fun local(id: String, doc: GpxDocument) {
            val hashes = RouteIdentity.hashes(doc.tracks.single().points.map { io.github.lrosa2.singletrail.nav.RoutePoint(it.lat, it.lon) })
            byHash[hashes.forward] = id
            byHash[hashes.reversed] = id
        }

        override suspend fun routeWithGeometry(hashes: RouteIdentity.GeometryHashes): String? =
            byHash[hashes.forward] ?: byHash[hashes.reversed]

        override suspend fun addRoute(route: NewRoute, rating: Int?, importedAt: Long, lastRiddenAt: Long?): String {
            routes += Added(route, rating, importedAt, lastRiddenAt)
            return "new-${routes.size}"
        }

        override suspend fun hasRide(id: String): Boolean = id in existingRides

        override suspend fun addRide(ride: BackupRide, routeId: String?, points: List<TrackPoint>) {
            rides += AddedRide(ride, routeId, points)
        }

        val collected = LinkedHashMap<String, MutableList<String>>()
        override suspend fun collect(name: String, routeIds: List<String>) {
            collected.getOrPut(name) { ArrayList() } += routeIds
        }
    }

    private fun gpx(name: String, vararg corners: Pair<Number, Number>) =
        GpxDocument(name, null, null, listOf(track(name, line(*corners))), emptyList())

    private val east = gpx("East", 0 to 0, 500 to 0)
    private val north = gpx("North", 0 to 0, 0 to 400)

    private fun rideTrack(): GpxDocument = RideGpx.document(
        "ride",
        (0..10).map { k -> TrackPoint(1_000L * k, 1_000L * k, Plane.lat(k * 10.0), Plane.lon(0.0), 900.0, null, null, null, 1) },
    )

    private fun backup(
        routes: List<Pair<BackupRoute, GpxDocument>> = listOf(route("r1", "East") to east, route("r2", "North", rating = 5) to north),
        rides: List<BackupRide> = listOf(ride("d1", "r2"), ride("d2", null)),
        collections: List<BackupCollection> = emptyList(),
    ): BackupArchive = BackupArchive.open(backupFile(routes, rides, collections), { reader.read(it) })

    private fun backupFile(
        routes: List<Pair<BackupRoute, GpxDocument>> = listOf(route("r1", "East") to east, route("r2", "North", rating = 5) to north),
        rides: List<BackupRide> = listOf(ride("d1", "r2"), ride("d2", null)),
        collections: List<BackupCollection> = emptyList(),
    ): File {
        val file = File(dir, "b${System.nanoTime()}.zip")
        file.outputStream().use { out ->
            BackupZip.writeFull(
                out,
                BackupManifest(kind = BackupManifest.KIND_FULL, app = "t", createdAt = 1, owner = "x"),
                routes.map { it.first },
                { r -> routes.first { it.first == r }.second },
                rides,
                { rideTrack() },
                null,
                collections,
            )
        }
        return file
    }

    /** [source] as versions before collections wrote it: no collections.json, no "collections" on routes. */
    private fun beforeCollections(source: File): File {
        val out = File(dir, "old-${source.name}")
        java.util.zip.ZipFile(source).use { zip ->
            ZipOutputStream(out.outputStream()).use { z ->
                for (e in zip.entries()) {
                    if (e.name == "collections.json") continue
                    var bytes = zip.getInputStream(e).readBytes()
                    if (e.name == "routes.json") {
                        bytes = String(bytes).replace(Regex(""",?"collections":\[[^\]]*]"""), "").toByteArray()
                        assertFalse("collections" in String(bytes))
                    }
                    z.putNextEntry(ZipEntry(e.name))
                    z.write(bytes)
                    z.closeEntry()
                }
            }
        }
        return out
    }

    private fun route(id: String, name: String, rating: Int? = null) = BackupRoute(
        id = id, name = name, description = "$name description", rating = rating, sourceKind = "PICKER", sourceRef = "$name.gpx",
        importedAt = 111, updatedAt = 222, lastRiddenAt = 333, geometryHash = "ignored", file = "routes/$id.gpx",
    )

    private fun ride(id: String, routeId: String?) = BackupRide(
        id = id, routeId = routeId, routeGeometryHash = null, routeName = "North", direction = "FORWARD", startedAt = 5,
        endedAt = 6, status = "FINISHED", distanceM = 100.0, movingTimeS = 20, ascentM = null, segmentCount = 1, file = "rides/$id.gpx",
    )

    @Test
    fun `into an empty library everything is added`() = runTest {
        val target = FakeTarget()
        val summary = backup().use { BackupRestorer(target, Dispatchers.Unconfined).restore(it, withRides = true) }
        assertEquals(RestoreSummary(routesAdded = 2, routesKept = 0, ridesAdded = 2, ridesSkipped = 0, failed = 0), summary)
        val added = target.routes.associateBy { it.route.name }
        assertEquals(SourceKind.BACKUP, added.getValue("North").route.source)
        assertEquals(5, added.getValue("North").rating)
        assertEquals("North description", added.getValue("North").route.description)
        assertEquals(111L, added.getValue("North").importedAt)
        assertEquals(333L, added.getValue("North").lastRiddenAt)
        // Rides attach to the routes as stored here; one had no route.
        val rides = target.rides.associateBy { it.ride.id }
        assertEquals("new-2", rides.getValue("d1").routeId)
        assertNull(rides.getValue("d2").routeId)
        assertEquals(11, rides.getValue("d1").points.size)
    }

    @Test
    fun `a route already here keeps the local copy and gets the rides`() = runTest {
        val target = FakeTarget().apply { local("mine", north) }
        val summary = backup().use { BackupRestorer(target, Dispatchers.Unconfined).restore(it, withRides = true) }
        assertEquals(1, summary.routesAdded)
        assertEquals(1, summary.routesKept)
        assertEquals(listOf("East"), target.routes.map { it.route.name })
        assertEquals("mine", target.rides.first { it.ride.id == "d1" }.routeId)
    }

    @Test
    fun `the same route stored the other way round counts as here`() = runTest {
        val reversedNorth = GpxDocument("Back", null, null, listOf(GpxTrack("Back", null, GpxTrackKind.TRACK, listOf(north.tracks.single().points.reversed()))), emptyList())
        val target = FakeTarget().apply { local("mine", reversedNorth) }
        val summary = backup().use { BackupRestorer(target, Dispatchers.Unconfined).restore(it, withRides = false) }
        assertEquals(1, summary.routesKept)
    }

    @Test
    fun `rides already here are skipped`() = runTest {
        val target = FakeTarget().apply { existingRides += "d1" }
        val summary = backup().use { BackupRestorer(target, Dispatchers.Unconfined).restore(it, withRides = true) }
        assertEquals(1, summary.ridesAdded)
        assertEquals(1, summary.ridesSkipped)
        assertEquals(listOf("d2"), target.rides.map { it.ride.id })
    }

    @Test
    fun `routes only leaves the rides out`() = runTest {
        val target = FakeTarget()
        val summary = backup().use { BackupRestorer(target, Dispatchers.Unconfined).restore(it, withRides = false) }
        assertEquals(2, summary.routesAdded)
        assertEquals(0, summary.ridesAdded)
        assertEquals(emptyList<AddedRide>(), target.rides)
    }

    @Test
    fun `a broken route entry is counted and the rest still restore`() = runTest {
        val file = File(dir, "broken.zip")
        file.outputStream().use { out ->
            BackupZip.writeFull(
                out,
                BackupManifest(kind = BackupManifest.KIND_FULL, app = "t", createdAt = 1),
                listOf(route("r1", "East"), route("r2", "Missing")),
                { east },
                emptyList(),
                { rideTrack() },
                null,
            )
        }
        // Rewrite without the second route's GPX.
        val stripped = File(dir, "stripped.zip")
        java.util.zip.ZipFile(file).use { src ->
            ZipOutputStream(stripped.outputStream()).use { out ->
                for (e in src.entries()) {
                    if (e.name == "routes/r2.gpx") continue
                    out.putNextEntry(ZipEntry(e.name))
                    src.getInputStream(e).use { it.copyTo(out) }
                    out.closeEntry()
                }
            }
        }
        val target = FakeTarget()
        val summary = BackupArchive.open(stripped, { reader.read(it) }).use { BackupRestorer(target, Dispatchers.Unconfined).restore(it, withRides = true) }
        assertEquals(1, summary.routesAdded)
        assertEquals(1, summary.failed)
    }

    @Test
    fun `restored and kept routes go back into their collections, and empty collections come back too`() = runTest {
        val target = FakeTarget().apply { local("here-1", east) }
        val archive = backup(
            routes = listOf(
                route("r1", "East").copy(collections = listOf("komoot")) to east,
                route("r2", "North").copy(collections = listOf("komoot", "To ride")) to north,
            ),
            rides = emptyList(),
            collections = listOf(BackupCollection("komoot", 1), BackupCollection("Empty", 2)),
        )

        archive.use { BackupRestorer(target, Dispatchers.Unconfined).restore(it, withRides = false) }

        assertEquals(listOf("here-1", "new-1"), target.collected["komoot"])
        assertEquals(listOf("new-1"), target.collected["To ride"])
        assertEquals(emptyList<String>(), target.collected["Empty"])
    }

    @Test
    fun `a backup without collections restores without them`() = runTest {
        val target = FakeTarget()
        val old = BackupArchive.open(beforeCollections(backupFile()), { reader.read(it) })
        val summary = old.use { BackupRestorer(target, Dispatchers.Unconfined).restore(it, withRides = true) }

        assertEquals(RestoreSummary(routesAdded = 2, routesKept = 0, ridesAdded = 2, ridesSkipped = 0, failed = 0), summary)
        assertTrue(target.collected.isEmpty())
    }
}
