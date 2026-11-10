package io.github.lrosa2.singletrail.importing

import io.github.lrosa2.singletrail.data.db.SourceKind
import io.github.lrosa2.singletrail.data.db.WatchedFileStatus
import io.github.lrosa2.singletrail.doc
import io.github.lrosa2.singletrail.gpx.GpxReader
import io.github.lrosa2.singletrail.gpx.GpxWriter
import io.github.lrosa2.singletrail.line
import io.github.lrosa2.singletrail.nav.RouteIdentity
import io.github.lrosa2.singletrail.track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.kxml2.io.KXmlParser
import java.io.ByteArrayInputStream

class WatchedFolderScannerTest {

    private val reader = GpxReader({ KXmlParser() })

    private class Folder : FolderSource {
        val files = LinkedHashMap<String, Pair<String, ByteArray>>()
        override suspend fun list() = files.map { (uri, f) -> FolderFile(uri, f.first) }
        override suspend fun read(file: FolderFile) = files.getValue(file.uri).second
    }

    private class Registry : WatchedFileRegistry {
        val entries = LinkedHashMap<String, WatchedFile>()
        override suspend fun get(uri: String) = entries[uri]
        override suspend fun put(file: WatchedFile) {
            entries[file.uri] = file
        }
    }

    private class Library : FolderLibrary {
        val routes = LinkedHashMap<String, NewRoute>()
        val tombstones = HashSet<String>()
        val replaced = ArrayList<String>()
        override suspend fun routeWithGeometry(hashes: RouteIdentity.GeometryHashes): String? =
            routes.entries.firstOrNull { it.value.hashes.forward in setOf(hashes.forward, hashes.reversed) }?.key
        override suspend fun isTombstoned(hashes: RouteIdentity.GeometryHashes) = hashes.forward in tombstones || hashes.reversed in tombstones
        override suspend fun forgetTombstones(hashes: RouteIdentity.GeometryHashes) {
            tombstones -= hashes.forward
            tombstones -= hashes.reversed
        }
        override suspend fun exists(id: String) = id in routes
        override suspend fun add(route: NewRoute): String = "r${routes.size + 1}".also { routes[it] = route }
        override suspend fun replace(id: String, route: NewRoute) {
            routes[id] = route
            replaced += id
        }

        /** The user deletes a folder route: it goes, its geometry is tombstoned (D36). */
        fun deleteFromFolder(id: String) {
            tombstones += routes.remove(id)!!.hashes.forward
        }
    }

    private val folder = Folder()
    private val registry = Registry()
    private val library = Library()
    private var now = 1_000L
    private val scanner = WatchedFolderScanner(folder, registry, library, { reader.read(it) }, Dispatchers.Unconfined, { now })

    private fun gpx(name: String, vararg corners: Pair<Number, Number>) =
        GpxWriter.writeToString(doc(track(name, line(*corners)))).toByteArray()

    private val east = gpx("East", 0 to 0, 800 to 0)

    @Test
    fun `a new file becomes a route`() = runTest {
        folder.files["u1"] = "east.gpx" to east
        val scan = scanner.scan()
        val result = scan.results.single()
        assertEquals("east.gpx", result.fileName)
        assertInstanceOf(ImportOutcome.Added::class.java, result.outcome)
        val route = library.routes.values.single()
        assertEquals("East", route.name)
        assertEquals(SourceKind.FOLDER, route.source)
        assertEquals("east.gpx", route.sourceRef)
        val entry = registry.entries.getValue("u1")
        assertEquals(WatchedFileStatus.IMPORTED, entry.status)
        assertEquals("r1", entry.routeId)
        assertEquals(1_000L, entry.lastSeenAt)
    }

    @Test
    fun `an unchanged file is skipped next time`() = runTest {
        folder.files["u1"] = "east.gpx" to east
        scanner.scan()
        now = 2_000L
        val scan = scanner.scan()
        assertEquals(emptyList<ImportResult>(), scan.results)
        assertEquals(1, scan.unchanged)
        assertEquals(1, library.routes.size)
        assertEquals(2_000L, registry.entries.getValue("u1").lastSeenAt)
    }

    @Test
    fun `a file whose route is already here is linked, not added`() = runTest {
        library.add(newRoute("Mine"))
        folder.files["u1"] = "east.gpx" to east
        val scan = scanner.scan()
        assertEquals(emptyList<ImportResult>(), scan.results)
        assertEquals(1, library.routes.size)
        assertEquals("r1", registry.entries.getValue("u1").routeId)
    }

    @Test
    fun `a changed file updates its route`() = runTest {
        folder.files["u1"] = "east.gpx" to east
        scanner.scan()
        folder.files["u1"] = "east.gpx" to gpx("East v2", 0 to 0, 800 to 5, 900 to 50)
        val scan = scanner.scan()
        val result = scan.results.single()
        assertEquals(ImportOutcome.Updated("r1"), result.outcome)
        assertEquals(listOf("r1"), library.replaced)
        assertEquals(1, library.routes.size)
    }

    @Test
    fun `a route deleted by the user stays away, silently`() = runTest {
        folder.files["u1"] = "east.gpx" to east
        scanner.scan()
        library.deleteFromFolder("r1")
        // The registry still says imported, but the file reappears unchanged; or it is new to another install.
        registry.entries.clear()
        val scan = scanner.scan()
        assertEquals(emptyList<ImportResult>(), scan.results)
        assertEquals(1, scan.ignored)
        assertEquals(WatchedFileStatus.IGNORED, registry.entries.getValue("u1").status)
        assertEquals(0, library.routes.size)
    }

    @Test
    fun `deleting the route of an unchanged file lists the file as ignored`() = runTest {
        folder.files["u1"] = "east.gpx" to east
        scanner.scan()
        library.deleteFromFolder("r1")
        val scan = scanner.scan()
        assertEquals(emptyList<ImportResult>(), scan.results)
        assertEquals(1, scan.ignored)
        val entry = registry.entries.getValue("u1")
        assertEquals(WatchedFileStatus.IGNORED, entry.status)
        assertNull(entry.routeId)
        assertEquals(0, library.routes.size)
    }

    @Test
    fun `an ignored file can be imported anyway`() = runTest {
        folder.files["u1"] = "east.gpx" to east
        scanner.scan()
        library.deleteFromFolder("r1")
        registry.entries.clear()
        scanner.scan()
        val result = scanner.importAnyway("u1")!!
        assertInstanceOf(ImportOutcome.Added::class.java, result.outcome)
        assertEquals(1, library.routes.size)
        assertEquals(WatchedFileStatus.IMPORTED, registry.entries.getValue("u1").status)
        assertEquals(emptySet<String>(), library.tombstones)
    }

    @Test
    fun `a broken file is reported once`() = runTest {
        folder.files["u1"] = "broken.gpx" to "<gpx><trk>".toByteArray()
        val first = scanner.scan()
        assertInstanceOf(ImportOutcome.Failed::class.java, first.results.single().outcome)
        assertEquals(WatchedFileStatus.ERROR, registry.entries.getValue("u1").status)
        assertNull(registry.entries.getValue("u1").routeId)
        assertEquals(emptyList<ImportResult>(), scanner.scan().results)
    }

    @Test
    fun `several tracks in one file become one route`() = runTest {
        val two = GpxWriter.writeToString(doc(track("Up", line(0 to 0, 0 to 400)), track("Down", line(0 to 400, 300 to 400)), name = "Day out")).toByteArray()
        folder.files["u1"] = "day.gpx" to two
        scanner.scan()
        val route = library.routes.values.single()
        assertEquals("Day out", route.name)
        assertEquals(700.0, route.prepared.lengthMeters, 5.0)
    }

    private fun newRoute(name: String): NewRoute {
        val d = reader.read(ByteArrayInputStream(east))
        val t = d.tracks.single()
        val points = t.points.map { io.github.lrosa2.singletrail.nav.RoutePoint(it.lat, it.lon, it.elevation) }
        return NewRoute(name, null, SourceKind.PICKER, null, t, emptyList(), io.github.lrosa2.singletrail.nav.PreparedRoute.prepare(points), RouteIdentity.hashes(points))
    }
}
