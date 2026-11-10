package io.github.lrosa2.singletrail.importing

import io.github.lrosa2.singletrail.data.db.SourceKind
import io.github.lrosa2.singletrail.doc
import io.github.lrosa2.singletrail.gpx.GpxDocument
import io.github.lrosa2.singletrail.gpx.GpxParseException
import io.github.lrosa2.singletrail.gpx.GpxParseException.Reason
import io.github.lrosa2.singletrail.gpxAt
import io.github.lrosa2.singletrail.line
import io.github.lrosa2.singletrail.nav.PreparedRoute
import io.github.lrosa2.singletrail.track
import io.github.lrosa2.singletrail.waypointAt
import kotlinx.coroutines.CoroutineScope
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

    private val library = FakeLibrary()
    private val names = HashMap<String, String>()
    private val files = HashMap<String, GpxDocument>()
    private val failures = HashMap<String, Exception>()
    private val source = object : GpxSource {
        override suspend fun displayName(request: ImportRequest) = names[request.uri]

        override suspend fun load(request: ImportRequest): GpxDocument {
            failures[request.uri]?.let { throw it }
            return files.getValue(request.uri)
        }
    }

    /** 1 km east from the origin. */
    private val east = line(0 to 0, 1000 to 0)

    /** 1 km north, starting 1 km beyond the end of [east]. */
    private val north = line(2000 to 0, 2000 to 1000)

    // The queue runs in its own scope on the test scheduler: advanceUntilIdle() doesn't run work
    // launched in backgroundScope, and the queue's loop never ends on its own.
    private val queueScopes = ArrayList<CoroutineScope>()

    @AfterEach
    fun stopQueues() = queueScopes.forEach { it.cancel() }

    private fun TestScope.coordinator(): ImportCoordinator {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher).also { queueScopes += it }
        return ImportCoordinator(source, library, scope, dispatcher)
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

        assertNull(c.state.value.question)
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
}
