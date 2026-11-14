package io.github.lrosa2.singletrail.importing

import io.github.lrosa2.singletrail.backup.BackupArchive
import io.github.lrosa2.singletrail.backup.BackupManifest
import io.github.lrosa2.singletrail.backup.BackupRestorer
import io.github.lrosa2.singletrail.backup.BackupRide
import io.github.lrosa2.singletrail.backup.BackupRoute
import io.github.lrosa2.singletrail.backup.BackupZip
import io.github.lrosa2.singletrail.backup.RestoreSummary
import io.github.lrosa2.singletrail.backup.RestoreTarget
import io.github.lrosa2.singletrail.data.db.SourceKind
import io.github.lrosa2.singletrail.data.rides.TrackPoint
import io.github.lrosa2.singletrail.doc
import io.github.lrosa2.singletrail.gpx.GpxReader
import io.github.lrosa2.singletrail.nav.RouteIdentity
import org.junit.jupiter.api.io.TempDir
import org.kxml2.io.KXmlParser
import java.io.File
import io.github.lrosa2.singletrail.gpx.GpxDocument
import io.github.lrosa2.singletrail.gpx.GpxParseException
import io.github.lrosa2.singletrail.gpx.GpxParseException.Reason
import io.github.lrosa2.singletrail.gpxAt
import io.github.lrosa2.singletrail.line
import io.github.lrosa2.singletrail.nav.PreparedRoute
import io.github.lrosa2.singletrail.track
import io.github.lrosa2.singletrail.waypointAt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class) // advanceUntilIdle
class ImportCoordinatorTest {

    private class FakeLibrary : RouteLibrary {
        class Stored(val id: String, var route: NewRoute)

        val routes = LinkedHashMap<String, Stored>()
        val replaced = ArrayList<String>()
        private var nextId = 1

        override suspend fun findByGeometryHash(hash: String) =
            routes.values.firstOrNull { it.route.hashes.forward == hash }?.let { LibraryRoute(it.id, it.route.name) }

        // No pre-filter: the coordinator itself must decide what is really similar.
        override suspend fun similarCandidates(route: PreparedRoute) =
            routes.values.map { LibraryRoute(it.id, it.route.name) }

        override suspend fun prepared(id: String) = routes.getValue(id).route.prepared

        override suspend fun add(route: NewRoute): String {
            val id = "r${nextId++}"
            routes[id] = Stored(id, route)
            return id
        }

        override suspend fun replace(id: String, route: NewRoute) {
            routes.getValue(id).route = route
            replaced += id
        }

        fun names() = routes.values.map { it.route.name }
    }

    private class FakeCollections : ImportCollections {
        val groups = LinkedHashMap<String, MutableList<String>>()

        /** The database can't be written: every call fails. */
        var broken = false

        override suspend fun names(): List<String> {
            if (broken) throw IOException("disk full")
            return groups.keys.sorted()
        }

        override suspend fun addTo(name: String, routeIds: List<String>) {
            if (broken) throw IOException("disk full")
            groups.getOrPut(name) { ArrayList() } += routeIds
        }
    }

    private val collections = FakeCollections()
    private val library = FakeLibrary()
    private val names = HashMap<String, String>()
    private val files = HashMap<String, GpxDocument>()
    private val zips = HashMap<String, File>()
    private val failures = HashMap<String, Exception>()
    private val source = object : GpxSource {
        override suspend fun displayName(request: ImportRequest) = names[request.uri]

        override suspend fun load(request: ImportRequest): LoadedFile {
            failures[request.uri]?.let { throw it }
            zips[request.uri]?.let { return LoadedFile.Archive(it) }
            return LoadedFile.Gpx(files.getValue(request.uri))
        }
    }

    @TempDir
    lateinit var dir: File

    /** Restores into a list, so tests can see what a restore did. */
    private val restored = ArrayList<String>()
    private val restoredRides = ArrayList<String>()
    private val restoreTarget = object : RestoreTarget {
        override suspend fun routeWithGeometry(hashes: RouteIdentity.GeometryHashes): String? = null
        override suspend fun addRoute(route: NewRoute, rating: Int?, importedAt: Long, lastRiddenAt: Long?): String {
            restored += route.name
            return "b${restored.size}"
        }
        override suspend fun hasRide(id: String) = false
        override suspend fun addRide(ride: BackupRide, routeId: String?, points: List<TrackPoint>) {
            restoredRides += ride.id
        }
    }
    private val gpxReader = GpxReader({ KXmlParser() })

    /** 1 km east from the origin. */
    private val east = line(0 to 0, 1000 to 0)

    /** 1 km north, starting 1 km beyond the end of [east]. */
    private val north = line(2000 to 0, 2000 to 1000)

    // The queue runs in its own scope on the test scheduler: advanceUntilIdle() doesn't run work
    // launched in backgroundScope, and the queue's loop never ends on its own.
    private val queueScopes = ArrayList<CoroutineScope>()

    @AfterEach
    fun stopQueues() = queueScopes.forEach { it.cancel() }

    private fun TestScope.coordinator(
        largeRoutePoints: Int = 50_000,
        log: (String) -> Unit = {},
        clock: () -> Long = System::nanoTime,
    ): ImportCoordinator {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher).also { queueScopes += it }
        val archives = ArchiveSupport(
            open = { BackupArchive.open(it, { input -> gpxReader.read(input) }) },
            restorer = BackupRestorer(restoreTarget, dispatcher),
            ownerId = { "me" },
        )
        return ImportCoordinator(source, library, scope, dispatcher, archives, log, largeRoutePoints, clock, collections)
    }

    /** A full backup made by [owner] with one route and one ride. */
    private fun backupZip(uri: String, owner: String): File {
        val file = File(dir, "$uri.zip")
        val route = BackupRoute("r1", "Backed up", importedAt = 1, geometryHash = "h", file = "routes/r1.gpx")
        val ride = BackupRide("d1", "r1", null, "Backed up", "FORWARD", 5, 6, "FINISHED", 10.0, 2, null, 1, "rides/d1.gpx")
        file.outputStream().use {
            BackupZip.writeFull(
                it,
                BackupManifest(kind = BackupManifest.KIND_FULL, app = "t", createdAt = 1, owner = owner, routes = 1, rides = 1),
                listOf(route),
                { doc(track("Backed up", east)) },
                listOf(ride),
                { doc(track("ride", north)) },
                null,
            )
        }
        zips[uri] = file
        names[uri] = "$uri.zip"
        return file
    }

    private fun file(uri: String, displayName: String?, document: GpxDocument) {
        if (displayName != null) names[uri] = displayName
        files[uri] = document
    }

    private fun picked(vararg uris: String) = uris.map { ImportRequest(it, SourceKind.PICKER, confirm = false) }
    private fun shared(vararg uris: String) = uris.map { ImportRequest(it, SourceKind.SHARE, confirm = true) }

    private fun ImportCoordinator.outcomes() = state.value.results.map { it.outcome }

    @Test
    fun `a picked file with one track is added without asking`() = runTest {
        file("u1", "Gurnigel.gpx", doc(track("Gurnigel loop", east)))
        val c = coordinator()
        c.enqueue(picked("u1"))
        advanceUntilIdle()

        val state = c.state.value
        assertNull(state.question)
        assertFalse(state.busy)
        val result = state.results.single()
        assertEquals("Gurnigel.gpx", result.fileName)
        assertEquals("Gurnigel loop", result.routeName)
        assertInstanceOf(ImportOutcome.Added::class.java, result.outcome)
        val stored = library.routes.values.single().route
        assertEquals("Gurnigel loop", stored.name)
        assertEquals(SourceKind.PICKER, stored.source)
        assertEquals("Gurnigel.gpx", stored.sourceRef)
        assertEquals(1000.0, stored.prepared.lengthMeters, 1.0)
    }

    @Test
    fun `a route with many points comes with a warning, in the preview and in the result`() = runTest {
        // [east] has 101 points; pretend the warning starts at 50.
        file("u1", "Big.gpx", doc(track("Big", east)))
        file("u2", "Small.gpx", doc(track("Small", north)))
        val c = coordinator(largeRoutePoints = 50)
        c.enqueue(shared("u1"))
        advanceUntilIdle()

        val preview = (c.state.value.question as ImportQuestion.ConfirmAdd).preview
        assertEquals(101, preview.pointCount)
        assertTrue(preview.large)
        c.answer(ImportAnswer.ADD)
        advanceUntilIdle()
        assertEquals(101, c.state.value.results.single().largePoints)

        val relaxed = coordinator(largeRoutePoints = 500)
        relaxed.enqueue(picked("u2"))
        advanceUntilIdle()
        assertNull(relaxed.state.value.results.single().largePoints)
    }

    @Test
    fun `the log says how long each file took`() = runTest {
        file("u1", "Gurnigel.gpx", doc(track("Gurnigel loop", east)))
        val lines = ArrayList<String>()
        var now = 0L
        val c = coordinator(log = { lines += it }, clock = { now.also { now += 1_250_000_000L } })
        c.enqueue(picked("u1"))
        advanceUntilIdle()
        val line = lines.single()
        assertTrue(line.startsWith("Import PICKER Gurnigel.gpx: Added("), line)
        assertTrue(line.endsWith(" in 1250 ms"), line)
    }

    @Test
    fun `a shared file shows a preview before it is added`() = runTest {
        file("u1", "Gurnigel.gpx", doc(track("Gurnigel loop", east)))
        val c = coordinator()
        c.enqueue(shared("u1"))
        advanceUntilIdle()

        val question = c.state.value.question as ImportQuestion.ConfirmAdd
        assertEquals("Gurnigel loop", question.preview.name)
        assertEquals(1000.0, question.preview.distanceM, 1.0)
        assertTrue(library.routes.isEmpty())

        c.answer(ImportAnswer.ADD)
        advanceUntilIdle()
        assertNull(c.state.value.question)
        assertEquals(1, library.routes.size)
        assertInstanceOf(ImportOutcome.Added::class.java, c.outcomes().single())
    }

    @Test
    fun `cancelling the preview adds nothing`() = runTest {
        file("u1", "Gurnigel.gpx", doc(track("Gurnigel loop", east)))
        val c = coordinator()
        c.enqueue(shared("u1"))
        advanceUntilIdle()
        c.answer(ImportAnswer.CANCEL)
        advanceUntilIdle()

        assertTrue(library.routes.isEmpty())
        assertEquals(listOf(ImportOutcome.Skipped), c.outcomes())
    }

    @Test
    fun `an answer that is not one of the options is ignored`() = runTest {
        file("u1", "Gurnigel.gpx", doc(track("Gurnigel loop", east)))
        val c = coordinator()
        c.enqueue(shared("u1"))
        advanceUntilIdle()
        c.answer(ImportAnswer.SPLIT)
        advanceUntilIdle()

        assertInstanceOf(ImportQuestion.ConfirmAdd::class.java, c.state.value.question)
        assertTrue(library.routes.isEmpty())
    }

    @Test
    fun `the same route again is already in the library`() = runTest {
        file("u1", "a.gpx", doc(track("A", east)))
        file("u2", "b.gpx", doc(track("B", east)))
        val c = coordinator()
        c.enqueue(picked("u1", "u2"))
        advanceUntilIdle()

        assertEquals(listOf("A"), library.names())
        val id = library.routes.keys.single()
        assertEquals(ImportOutcome.AlreadyInLibrary(id, reversed = false), c.outcomes()[1])
    }

    @Test
    fun `the same route reversed is recognised`() = runTest {
        file("u1", "a.gpx", doc(track("A", east)))
        file("u2", "b.gpx", doc(track("B", east.reversed())))
        val c = coordinator()
        c.enqueue(picked("u1", "u2"))
        advanceUntilIdle()

        assertEquals(listOf("A"), library.names())
        val id = library.routes.keys.single()
        assertEquals(ImportOutcome.AlreadyInLibrary(id, reversed = true), c.outcomes()[1])
    }

    @Test
    fun `a similar route asks whether to update the existing one`() = runTest {
        // The same trail recorded again: 3 m to the side, points every 7 m.
        file("u1", "old.gpx", doc(track("Old", east)))
        file("u2", "new.gpx", doc(track("New", line(0 to 3, 1000 to 3, step = 7.0))))
        val c = coordinator()
        c.enqueue(picked("u1", "u2"))
        advanceUntilIdle()

        val question = c.state.value.question as ImportQuestion.SimilarRoute
        assertEquals("Old", question.existing.name)
        assertEquals("New", question.preview.name)

        c.answer(ImportAnswer.UPDATE)
        advanceUntilIdle()
        val id = library.routes.keys.single()
        assertEquals(listOf(id), library.replaced)
        assertEquals(ImportOutcome.Updated(id), c.outcomes().last())
    }

    @Test
    fun `keep both adds the similar route as a new one`() = runTest {
        file("u1", "old.gpx", doc(track("Old", east)))
        file("u2", "new.gpx", doc(track("New", line(0 to 3, 1000 to 3, step = 7.0))))
        val c = coordinator()
        c.enqueue(picked("u1", "u2"))
        advanceUntilIdle()
        c.answer(ImportAnswer.KEEP_BOTH)
        advanceUntilIdle()

        assertEquals(listOf("Old", "New"), library.names())
        assertTrue(library.replaced.isEmpty())
    }

    @Test
    fun `a different route nearby is simply added`() = runTest {
        file("u1", "a.gpx", doc(track("East", east)))
        file("u2", "b.gpx", doc(track("Parallel", line(0 to 200, 1000 to 200))))
        val c = coordinator()
        c.enqueue(picked("u1", "u2"))
        advanceUntilIdle()

        // Not asked whether it is the same route; only, at the end, about a collection (spec §8.4 step 7).
        assertInstanceOf(ImportQuestion.PutInCollection::class.java, c.state.value.question)
        assertEquals(listOf("East", "Parallel"), library.names())
    }

    @Test
    fun `a file with several tracks asks to split or join`() = runTest {
        file("u1", "two.gpx", doc(track("East", east), track("North", north)))
        val c = coordinator()
        c.enqueue(picked("u1"))
        advanceUntilIdle()

        val question = c.state.value.question as ImportQuestion.MultipleTracks
        assertEquals(listOf("East", "North"), question.trackNames)

        c.answer(ImportAnswer.SPLIT)
        advanceUntilIdle()
        assertEquals(listOf("East", "North"), library.names())
    }

    @Test
    fun `joining tracks makes one route`() = runTest {
        file("u1", "two.gpx", doc(track("East", east), track("North", north), name = "Both"))
        val c = coordinator()
        c.enqueue(picked("u1"))
        advanceUntilIdle()
        c.answer(ImportAnswer.JOIN)
        advanceUntilIdle()

        val route = library.routes.values.single().route
        assertEquals("Both", route.name)
        // East, the 1 km gap between the tracks, then north.
        assertEquals(3000.0, route.prepared.lengthMeters, 2.0)
    }

    @Test
    fun `split tracks keep only the waypoints near them`() = runTest {
        val spring = waypointAt(500, 50, "Spring")
        val hut = waypointAt(2050, 900, "Hut")
        file("u1", "two.gpx", doc(track("East", east), track("North", north), waypoints = listOf(spring, hut)))
        val c = coordinator()
        c.enqueue(picked("u1"))
        advanceUntilIdle()
        c.answer(ImportAnswer.SPLIT)
        advanceUntilIdle()

        val (eastRoute, northRoute) = library.routes.values.map { it.route }
        assertEquals(listOf(spring), eastRoute.waypoints)
        assertEquals(listOf(hut), northRoute.waypoints)
    }

    @Test
    fun `a single route keeps all waypoints`() = runTest {
        val farAway = waypointAt(5000, 5000, "Station")
        file("u1", "a.gpx", doc(track("East", east), waypoints = listOf(farAway)))
        val c = coordinator()
        c.enqueue(picked("u1"))
        advanceUntilIdle()

        assertEquals(listOf(farAway), library.routes.values.single().route.waypoints)
    }

    @Test
    fun `without names the file name is used`() = runTest {
        file("u1", "Morning ride.gpx", doc(track(null, east)))
        val c = coordinator()
        c.enqueue(picked("u1"))
        advanceUntilIdle()

        assertEquals(listOf("Morning ride"), library.names())
    }

    @Test
    fun `komoot's GPX Download prefix is left out of the route name`() = runTest {
        file("u1", "2025-09-28_2605563075_GPX Download_ Waldegg loop.gpx", doc(track("GPX Download: Waldegg loop", east)))
        val c = coordinator()
        c.enqueue(picked("u1"))
        advanceUntilIdle()

        assertEquals(listOf("Waldegg loop"), library.names())
        assertEquals("Waldegg loop", c.state.value.results.single().routeName)
    }

    @Test
    fun `a track named only GPX Download falls back to the file name`() = runTest {
        file("u1", "Waldegg loop.gpx", doc(track("GPX Download:", east)))
        val c = coordinator()
        c.enqueue(picked("u1"))
        advanceUntilIdle()

        assertEquals(listOf("Waldegg loop"), library.names())
    }

    @Test
    fun `split tracks lose the prefix too`() = runTest {
        file("u1", "two.gpx", doc(track("GPX Download: East", east), track("GPX Download: North", north)))
        val c = coordinator()
        c.enqueue(picked("u1"))
        advanceUntilIdle()

        assertEquals(listOf("East", "North"), (c.state.value.question as ImportQuestion.MultipleTracks).trackNames)
        c.answer(ImportAnswer.SPLIT)
        advanceUntilIdle()
        assertEquals(listOf("East", "North"), library.names())
    }

    @Test
    fun `invalid and unreadable files are reported`() = runTest {
        failures["bad"] = GpxParseException(Reason.NOT_GPX, "not gpx")
        failures["gone"] = IOException("gone")
        failures["evil"] = GpxParseException(Reason.DOCTYPE_NOT_ALLOWED, "doctype")
        val c = coordinator()
        c.enqueue(picked("bad", "gone", "evil"))
        advanceUntilIdle()

        assertEquals(
            listOf(
                ImportOutcome.Failed(ImportProblem.NOT_GPX),
                ImportOutcome.Failed(ImportProblem.UNREADABLE),
                ImportOutcome.Failed(ImportProblem.UNSAFE_XML),
            ),
            c.outcomes(),
        )
        assertTrue(library.routes.isEmpty())
    }

    @Test
    fun `a file that fails to parse is reported by its display name`() = runTest {
        names["u1"] = "notes.txt"
        failures["u1"] = GpxParseException(Reason.NOT_GPX, "not gpx")
        val c = coordinator()
        c.enqueue(picked("u1"))
        advanceUntilIdle()

        assertEquals("notes.txt", c.state.value.results.single().fileName)
    }

    @Test
    fun `without a display name the file name comes from the URI`() = runTest {
        val uri = "content://com.android.externalstorage.documents/document/primary%3ADownload%2FMy%20ride.gpx"
        file(uri, null, doc(track(null, east)))
        val c = coordinator()
        c.enqueue(picked(uri))
        advanceUntilIdle()

        assertEquals("My ride.gpx", c.state.value.results.single().fileName)
        assertEquals(listOf("My ride"), library.names())
    }

    @Test
    fun `a file with only waypoints has no track`() = runTest {
        file("u1", "pois.gpx", doc(waypoints = listOf(waypointAt(0, 0, "Spring"))))
        val c = coordinator()
        c.enqueue(picked("u1"))
        advanceUntilIdle()

        assertEquals(listOf(ImportOutcome.Failed(ImportProblem.NO_TRACK)), c.outcomes())
    }

    @Test
    fun `a track shorter than a metre is too short`() = runTest {
        file("u1", "stop.gpx", doc(track("Stop", listOf(gpxAt(0, 0), gpxAt(0.3, 0)))))
        val c = coordinator()
        c.enqueue(picked("u1"))
        advanceUntilIdle()

        assertEquals(listOf(ImportOutcome.Failed(ImportProblem.TOO_SHORT)), c.outcomes())
    }

    @Test
    fun `files are imported one at a time`() = runTest {
        file("u1", "a.gpx", doc(track("A", east)))
        file("u2", "b.gpx", doc(track("B", north)))
        val c = coordinator()
        c.enqueue(shared("u1", "u2"))
        advanceUntilIdle()

        assertEquals("a.gpx", c.state.value.question?.fileName)
        assertEquals(1, c.state.value.queued)
        c.answer(ImportAnswer.ADD)
        advanceUntilIdle()

        assertEquals("b.gpx", c.state.value.question?.fileName)
        assertEquals(0, c.state.value.queued)
        c.answer(ImportAnswer.ADD)
        advanceUntilIdle()

        assertFalse(c.state.value.busy)
        assertEquals(listOf("A", "B"), library.names())
        c.clearResults()
        assertTrue(c.state.value.results.isEmpty())
    }

    @Test
    fun `a backup from this phone asks before restoring everything`() = runTest {
        val zip = backupZip("mine", owner = "me")
        val c = coordinator()
        c.enqueue(picked("mine"))
        advanceUntilIdle()

        val question = c.state.value.question as ImportQuestion.RestoreBackup
        assertTrue(question.fromThisPhone)
        assertEquals(1, question.routes)
        assertEquals(1, question.rides)
        assertEquals(listOf(ImportAnswer.RESTORE, ImportAnswer.CANCEL), question.options)
        c.answer(ImportAnswer.RESTORE)
        advanceUntilIdle()

        assertEquals(listOf("Backed up"), restored)
        assertEquals(listOf("d1"), restoredRides)
        assertEquals(listOf<ImportOutcome>(ImportOutcome.Restored(RestoreSummary(1, 0, 1, 0, 0))), c.outcomes())
        assertFalse(zip.exists(), "the temporary copy is deleted")
    }

    @Test
    fun `a backup from another phone offers routes only first`() = runTest {
        backupZip("theirs", owner = "someone else")
        val c = coordinator()
        c.enqueue(shared("theirs"))
        advanceUntilIdle()

        val question = c.state.value.question as ImportQuestion.RestoreBackup
        assertFalse(question.fromThisPhone)
        assertEquals(listOf(ImportAnswer.ROUTES_ONLY, ImportAnswer.RESTORE, ImportAnswer.CANCEL), question.options)
        c.answer(ImportAnswer.ROUTES_ONLY)
        advanceUntilIdle()

        assertEquals(listOf("Backed up"), restored)
        assertTrue(restoredRides.isEmpty())
    }

    @Test
    fun `cancelling a backup restores nothing`() = runTest {
        backupZip("mine", owner = "me")
        val c = coordinator()
        c.enqueue(picked("mine"))
        advanceUntilIdle()
        c.answer(ImportAnswer.CANCEL)
        advanceUntilIdle()

        assertTrue(restored.isEmpty())
        assertEquals(listOf<ImportOutcome>(ImportOutcome.Skipped), c.outcomes())
    }

    @Test
    fun `a zip of routes imports each one`() = runTest {
        val file = File(dir, "routes.zip")
        file.outputStream().use {
            BackupZip.writeRoutes(it, "t", 1, listOf("East" to doc(track("East", east)), "North" to doc(track("North", north))))
        }
        zips["z"] = file
        names["z"] = "routes.zip"
        val c = coordinator()
        c.enqueue(shared("z"))
        advanceUntilIdle()

        // Routes from a zip go in without a preview each; similar ones would still ask.
        assertNull(c.state.value.question)
        assertEquals(listOf("East", "North"), library.names())
        assertEquals(listOf("East.gpx", "North.gpx"), c.state.value.results.map { it.fileName })
        assertEquals(listOf("East.gpx", "North.gpx"), library.routes.values.map { it.route.sourceRef })
    }

    @Test
    fun `a job runs in turn with the files and reports its results`() = runTest {
        file("u1", "a.gpx", doc(track("A", east)))
        file("u2", "b.gpx", doc(track("B", north)))
        val order = ArrayList<String>()
        val c = coordinator()
        c.enqueue(picked("u1"))
        c.enqueueJob("Watched folder") {
            order += "job saw ${library.names()}"
            listOf(ImportResult("c.gpx", "C", ImportOutcome.Added("x")))
        }
        c.enqueue(picked("u2"))
        assertEquals(3, c.state.value.queued)
        advanceUntilIdle()

        assertEquals(listOf("job saw [A]"), order)
        assertEquals(listOf("a.gpx", "c.gpx", "b.gpx"), c.state.value.results.map { it.fileName })
        assertFalse(c.state.value.busy)
    }

    @Test
    fun `a failing job doesn't stop the queue`() = runTest {
        file("u1", "a.gpx", doc(track("A", east)))
        val c = coordinator()
        c.enqueueJob("Watched folder") { throw IllegalStateException("boom") }
        c.enqueue(picked("u1"))
        advanceUntilIdle()

        assertEquals(listOf("A"), library.names())
        assertFalse(c.state.value.busy)
    }

    @Test
    fun `a damaged zip fails like a damaged file`() = runTest {
        zips["bad"] = File(dir, "bad.zip").apply { writeText("PK not really") }
        names["bad"] = "bad.zip"
        val c = coordinator()
        c.enqueue(picked("bad"))
        advanceUntilIdle()

        assertEquals(listOf<ImportOutcome>(ImportOutcome.Failed(ImportProblem.DAMAGED)), c.outcomes())
    }

    private fun routesZip(uri: String, fileName: String, vararg routes: Pair<String, GpxDocument>, collection: String? = null) {
        val file = File(dir, "$uri.zip")
        file.outputStream().use { BackupZip.writeRoutes(it, "t", 1, routes.toList(), collection) }
        zips[uri] = file
        names[uri] = fileName
    }

    @Test
    fun `a zip's routes go into a collection named after the zip`() = runTest {
        routesZip("z", "SchweizMobil-MTB-Ostschweiz.zip", "East" to doc(track("East", east)), "North" to doc(track("North", north)))
        val c = coordinator()
        c.enqueue(picked("z"))
        advanceUntilIdle()

        assertEquals(mapOf("SchweizMobil-MTB-Ostschweiz" to library.routes.keys.toList()), collections.groups)
        assertEquals(listOf("SchweizMobil-MTB-Ostschweiz", "SchweizMobil-MTB-Ostschweiz"), c.state.value.results.map { it.collection })
    }

    @Test
    fun `importing the same zip again groups routes already in the library`() = runTest {
        file("u1", "east.gpx", doc(track("East", east)))
        val c = coordinator()
        c.enqueue(picked("u1"))
        advanceUntilIdle()
        val eastId = library.routes.keys.single()

        routesZip("z", "Ostschweiz.zip", "East" to doc(track("East", east)), "North" to doc(track("North", north)))
        c.enqueue(picked("z"))
        advanceUntilIdle()

        val northId = library.routes.keys.last()
        assertEquals(mapOf("Ostschweiz" to listOf(eastId, northId)), collections.groups)
    }

    @Test
    fun `a shared collection zip uses the name in its manifest`() = runTest {
        routesZip("z", "Singletrail routes.zip", "East" to doc(track("East", east)), collection = "Waldegg loops")
        val c = coordinator()
        c.enqueue(shared("z"))
        advanceUntilIdle()

        assertEquals(setOf("Waldegg loops"), collections.groups.keys)
    }

    @Test
    fun `several files at once ask to be put in a collection`() = runTest {
        file("u1", "east.gpx", doc(track("East", east)))
        file("u2", "north.gpx", doc(track("North", north)))
        collections.groups["To ride"] = ArrayList()
        val c = coordinator()
        c.enqueue(picked("u1", "u2"))
        advanceUntilIdle()

        val question = c.state.value.question as ImportQuestion.PutInCollection
        assertEquals(2, question.routes)
        assertEquals(listOf("To ride"), question.existing)
        c.putInCollection("Waldegg loops")
        advanceUntilIdle()

        assertNull(c.state.value.question)
        assertEquals(library.routes.keys.toList(), collections.groups["Waldegg loops"])
    }

    @Test
    fun `saying no to the collection keeps the routes ungrouped`() = runTest {
        file("u1", "east.gpx", doc(track("East", east)))
        file("u2", "north.gpx", doc(track("North", north)))
        val c = coordinator()
        c.enqueue(picked("u1", "u2"))
        advanceUntilIdle()
        c.answer(ImportAnswer.CANCEL)
        advanceUntilIdle()

        assertEquals(2, library.routes.size)
        assertTrue(collections.groups.isEmpty())
    }

    @Test
    fun `routes already in the library count for the collection`() = runTest {
        file("u1", "east.gpx", doc(track("East", east)))
        val c = coordinator()
        c.enqueue(picked("u1"))
        advanceUntilIdle()
        file("u2", "north.gpx", doc(track("North", north)))
        c.enqueue(picked("u1", "u2"))
        advanceUntilIdle()

        assertEquals(2, (c.state.value.question as ImportQuestion.PutInCollection).routes)
    }

    @Test
    fun `two files of the same route don't ask about a collection`() = runTest {
        file("u1", "east.gpx", doc(track("East", east)))
        file("u2", "east copy.gpx", doc(track("East again", east)))
        val c = coordinator()
        c.enqueue(picked("u1", "u2"))
        advanceUntilIdle()

        assertNull(c.state.value.question)
        assertEquals(1, library.routes.size)
    }

    @Test
    fun `a single file is not put in a collection`() = runTest {
        file("u1", "east.gpx", doc(track("East", east)))
        val c = coordinator()
        c.enqueue(picked("u1"))
        advanceUntilIdle()

        assertNull(c.state.value.question)
        assertTrue(collections.groups.isEmpty())
    }

    @Test
    fun `a zip in a batch keeps its own collection and isn't counted again`() = runTest {
        routesZip("z", "Ostschweiz.zip", "East" to doc(track("East", east)))
        file("u1", "north.gpx", doc(track("North", north)))
        val c = coordinator()
        c.enqueue(picked("z", "u1"))
        advanceUntilIdle()

        assertNull(c.state.value.question) // Only one loose route: no question.
        assertEquals(setOf("Ostschweiz"), collections.groups.keys)
    }

    @Test
    fun `a collection that can't be saved doesn't fail the zip's routes`() = runTest {
        collections.broken = true
        routesZip("z", "Ostschweiz.zip", "East" to doc(track("East", east)), "North" to doc(track("North", north)))
        val c = coordinator()
        c.enqueue(picked("z"))
        advanceUntilIdle()

        assertEquals(2, library.routes.size)
        assertTrue(c.outcomes().all { it is ImportOutcome.Added }, c.outcomes().toString())
    }

    @Test
    fun `a collection that can't be saved doesn't stop the queue`() = runTest {
        collections.broken = true
        file("u1", "east.gpx", doc(track("East", east)))
        file("u2", "north.gpx", doc(track("North", north)))
        file("u3", "far.gpx", doc(track("Far", line(5000 to 0, 6000 to 0))))
        val c = coordinator()
        c.enqueue(picked("u1", "u2"))
        advanceUntilIdle()
        c.enqueue(picked("u3"))
        advanceUntilIdle()

        assertNull(c.state.value.question)
        assertEquals(listOf("East", "North", "Far"), library.names())
    }
}
