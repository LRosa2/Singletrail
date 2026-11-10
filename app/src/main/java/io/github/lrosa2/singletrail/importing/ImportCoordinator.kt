package io.github.lrosa2.singletrail.importing

import io.github.lrosa2.singletrail.data.routes.routePoints
import io.github.lrosa2.singletrail.gpx.GpxParseException
import io.github.lrosa2.singletrail.gpx.GpxTrack
import io.github.lrosa2.singletrail.gpx.GpxWaypoint
import io.github.lrosa2.singletrail.nav.PreparedRoute
import io.github.lrosa2.singletrail.nav.RouteIdentity
import io.github.lrosa2.singletrail.util.thinTo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicReference

/**
 * The import queue (spec §8.4). Files are imported strictly one at a time, so two sources delivering
 * the same file can't both add it. When a decision is needed (several tracks, a similar route, or a
 * preview for shared files) the queue publishes an [ImportQuestion] and waits for [answer].
 */
class ImportCoordinator(
    private val source: GpxSource,
    private val library: RouteLibrary,
    scope: CoroutineScope,
    /** Where parsing results are hashed and prepared; CPU-bound work. */
    private val work: CoroutineDispatcher = Dispatchers.Default,
) {
    private val _state = MutableStateFlow(ImportState())
    val state: StateFlow<ImportState> = _state.asStateFlow()

    private val queue = Channel<ImportRequest>(Channel.UNLIMITED)
    private val pending = AtomicReference<Pair<ImportQuestion, CompletableDeferred<ImportAnswer>>?>(null)

    init {
        scope.launch {
            for (request in queue) process(request)
        }
    }

    fun enqueue(requests: List<ImportRequest>) {
        if (requests.isEmpty()) return
        _state.update { it.copy(queued = it.queued + requests.size) }
        requests.forEach { queue.trySend(it) }
    }

    /** Answers the open question; ignored when there is none or [answer] isn't one of its options. */
    fun answer(answer: ImportAnswer) {
        val open = pending.get() ?: return
        if (answer !in open.first.options) return
        if (!pending.compareAndSet(open, null)) return
        _state.update { it.copy(question = null) }
        open.second.complete(answer)
    }

    fun clearResults() {
        _state.update { it.copy(results = emptyList()) }
    }

    private suspend fun process(request: ImportRequest) {
        // Named before parsing, so a file that fails is still reported by its own name.
        val fileName = source.displayName(request) ?: nameFromUri(request.uri)
        _state.update { it.copy(working = fileName, queued = it.queued - 1) }
        val results = try {
            importFile(request, fileName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Last resort: one broken file must never stop the queue.
            listOf(ImportResult(fileName, null, ImportOutcome.Failed(ImportProblem.SAVE_FAILED)))
        }
        _state.update { it.copy(working = null, results = it.results + results) }
    }

    private suspend fun importFile(request: ImportRequest, fileName: String): List<ImportResult> {
        val doc = try {
            source.load(request)
        } catch (e: GpxParseException) {
            return listOf(ImportResult(fileName, null, ImportOutcome.Failed(problemFor(e.reason))))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return listOf(ImportResult(fileName, null, ImportOutcome.Failed(ImportProblem.UNREADABLE)))
        }

        val baseName = fileName.replace(GPX_SUFFIX, "")
        val tracks = doc.tracks
        if (tracks.isEmpty()) return listOf(ImportResult(fileName, null, ImportOutcome.Failed(ImportProblem.NO_TRACK)))

        val candidates = if (tracks.size == 1) {
            val t = tracks.single()
            listOf(Candidate(t, t.name ?: doc.name ?: baseName, t.description ?: doc.description, doc.waypoints))
        } else {
            when (ask(ImportQuestion.MultipleTracks(fileName, tracks.map { it.name }))) {
                ImportAnswer.SPLIT -> tracks.mapIndexed { i, t ->
                    Candidate(t, t.name ?: "$baseName ${i + 1}", t.description, doc.waypoints, nearbyWaypointsOnly = true)
                }
                ImportAnswer.JOIN -> {
                    val joined = doc.joinedTrack()!!
                    listOf(Candidate(joined, joined.name ?: baseName, joined.description, doc.waypoints))
                }
                else -> return listOf(ImportResult(fileName, null, ImportOutcome.Skipped))
            }
        }
        return candidates.map { importTrack(request, fileName, it) }
    }

    private suspend fun importTrack(request: ImportRequest, fileName: String, c: Candidate): ImportResult {
        fun result(outcome: ImportOutcome, name: String = c.name) = ImportResult(fileName, name, outcome)

        val points = c.track.routePoints()
        val hashes = withContext(work) { RouteIdentity.hashes(points) }
        library.findByGeometryHash(hashes.forward)?.let {
            return result(ImportOutcome.AlreadyInLibrary(it.id, reversed = false), it.name)
        }
        library.findByGeometryHash(hashes.reversed)?.let {
            return result(ImportOutcome.AlreadyInLibrary(it.id, reversed = true), it.name)
        }

        val prepared = try {
            withContext(work) { PreparedRoute.prepare(points).also { it.elevation } }
        } catch (e: IllegalArgumentException) {
            return result(ImportOutcome.Failed(ImportProblem.TOO_SHORT))
        }
        val waypoints = if (c.nearbyWaypointsOnly) {
            withContext(work) {
                c.waypoints.filter { prepared.nearestSegment(it.lat, it.lon, NEARBY_WAYPOINT_M) != null }
            }
        } else {
            c.waypoints
        }
        val route = NewRoute(c.name, c.description, request.kind, fileName, c.track, waypoints, prepared, hashes)

        val similar = library.similarCandidates(prepared).firstOrNull { candidate ->
            val other = library.prepared(candidate.id)
            withContext(work) { RouteIdentity.isSimilar(other, prepared) }
        }
        val answer = when {
            similar != null -> ask(ImportQuestion.SimilarRoute(fileName, preview(route), similar))
            request.confirm -> ask(ImportQuestion.ConfirmAdd(fileName, preview(route)))
            else -> ImportAnswer.ADD
        }
        return try {
            when (answer) {
                ImportAnswer.ADD, ImportAnswer.KEEP_BOTH -> result(ImportOutcome.Added(library.add(route)))
                ImportAnswer.UPDATE -> {
                    library.replace(similar!!.id, route)
                    result(ImportOutcome.Updated(similar.id), similar.name)
                }
                else -> result(ImportOutcome.Skipped)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            result(ImportOutcome.Failed(ImportProblem.SAVE_FAILED))
        }
    }

    private suspend fun ask(question: ImportQuestion): ImportAnswer {
        val answer = CompletableDeferred<ImportAnswer>()
        pending.set(question to answer)
        _state.update { it.copy(question = question) }
        return answer.await()
    }

    private fun preview(route: NewRoute): RoutePreview {
        val prepared = route.prepared
        return RoutePreview(
            name = route.name,
            description = route.description,
            distanceM = prepared.lengthMeters,
            ascentM = prepared.elevation?.totalAscent,
            descentM = prepared.elevation?.totalDescent,
            waypointCount = route.waypoints.size,
            outline = prepared.samples.thinTo(OUTLINE_POINTS),
        )
    }

    private class Candidate(
        val track: GpxTrack,
        val name: String,
        val description: String?,
        val waypoints: List<GpxWaypoint>,
        /** For a track split out of a multi-track file: keep only the waypoints along it. */
        val nearbyWaypointsOnly: Boolean = false,
    )

    private companion object {
        val GPX_SUFFIX = Regex("""\.gpx$""", RegexOption.IGNORE_CASE)
        const val NEARBY_WAYPOINT_M = 500.0
        const val OUTLINE_POINTS = 300

        /** "…/document/primary%3ADownload%2FMy%20ride.gpx" → "My ride.gpx". */
        fun nameFromUri(uri: String): String {
            val lastSegment = uri.substringAfterLast('/')
            val decoded = try {
                URLDecoder.decode(lastSegment, Charsets.UTF_8.name())
            } catch (e: IllegalArgumentException) {
                lastSegment
            }
            return decoded.substringAfterLast('/').substringAfterLast(':')
        }

        fun problemFor(reason: GpxParseException.Reason) = when (reason) {
            GpxParseException.Reason.NOT_GPX -> ImportProblem.NOT_GPX
            GpxParseException.Reason.MALFORMED_XML -> ImportProblem.DAMAGED
            GpxParseException.Reason.DOCTYPE_NOT_ALLOWED -> ImportProblem.UNSAFE_XML
            GpxParseException.Reason.FILE_TOO_LARGE -> ImportProblem.TOO_LARGE
            GpxParseException.Reason.TOO_MANY_POINTS -> ImportProblem.TOO_MANY_POINTS
            GpxParseException.Reason.TOO_MANY_WAYPOINTS -> ImportProblem.TOO_MANY_WAYPOINTS
            GpxParseException.Reason.TOO_MANY_TRACKS -> ImportProblem.TOO_MANY_TRACKS
        }
    }
}
