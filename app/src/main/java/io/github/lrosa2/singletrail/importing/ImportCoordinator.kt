package io.github.lrosa2.singletrail.importing

import io.github.lrosa2.singletrail.backup.ArchiveException
import io.github.lrosa2.singletrail.backup.BackupArchive
import io.github.lrosa2.singletrail.backup.BackupKind
import io.github.lrosa2.singletrail.data.routes.routePoints
import io.github.lrosa2.singletrail.gpx.GpxDocument
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
import java.io.File
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
    /** Backups and zips of routes; without it a zip is "not GPX". */
    private val archives: ArchiveSupport? = null,
    /** The app log (spec §11): file names and outcomes only, never positions. */
    private val log: (String) -> Unit = {},
) {
    private val _state = MutableStateFlow(ImportState())
    val state: StateFlow<ImportState> = _state.asStateFlow()

    private sealed interface Work {
        data class File(val request: ImportRequest) : Work
        class Job(val name: String, val run: suspend () -> List<ImportResult>) : Work
    }

    private val queue = Channel<Work>(Channel.UNLIMITED)
    private val pending = AtomicReference<Pair<ImportQuestion, CompletableDeferred<ImportAnswer>>?>(null)

    init {
        scope.launch {
            for (work in queue) {
                when (work) {
                    is Work.File -> process(work.request)
                    is Work.Job -> runJob(work)
                }
            }
        }
    }

    fun enqueue(requests: List<ImportRequest>) {
        if (requests.isEmpty()) return
        _state.update { it.copy(queued = it.queued + requests.size) }
        requests.forEach { queue.trySend(Work.File(it)) }
    }

    /**
     * Runs [run] in turn with the files, so nothing else imports at the same time (the watched
     * folder scan); its results are reported like files'.
     */
    fun enqueueJob(name: String, run: suspend () -> List<ImportResult>) {
        _state.update { it.copy(queued = it.queued + 1) }
        queue.trySend(Work.Job(name, run))
    }

    private suspend fun runJob(job: Work.Job) {
        _state.update { it.copy(working = job.name, queued = it.queued - 1) }
        val results = try {
            job.run()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList() // A job reports its own failures; an unexpected one must not stop the queue.
        }
        _state.update { it.copy(working = null, results = it.results + results) }
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
        val fileName = try {
            source.displayName(request)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: nameFromUri(request.uri)
        _state.update { it.copy(working = fileName, queued = it.queued - 1) }
        val results = try {
            importFile(request, fileName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Last resort: one broken file must never stop the queue.
            log("Import of $fileName failed: ${e.javaClass.simpleName}")
            listOf(ImportResult(fileName, null, ImportOutcome.Failed(ImportProblem.SAVE_FAILED)))
        }
        results.forEach { log("Import ${request.kind} $fileName: ${it.outcome}") }
        _state.update { it.copy(working = null, results = it.results + results) }
    }

    private suspend fun importFile(request: ImportRequest, fileName: String): List<ImportResult> {
        val loaded = try {
            source.load(request)
        } catch (e: GpxParseException) {
            return listOf(ImportResult(fileName, null, ImportOutcome.Failed(importProblemFor(e.reason))))
        } catch (e: ArchiveException) {
            return listOf(ImportResult(fileName, null, ImportOutcome.Failed(ImportProblem.TOO_LARGE)))
        } catch (e: DownloadException) {
            val problem = if (e.reason == DownloadException.Reason.TOO_LARGE) ImportProblem.TOO_LARGE else ImportProblem.DOWNLOAD_FAILED
            return listOf(ImportResult(fileName, null, ImportOutcome.Failed(problem)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return listOf(ImportResult(fileName, null, ImportOutcome.Failed(ImportProblem.UNREADABLE)))
        }
        return when (loaded) {
            is LoadedFile.Gpx -> importDocument(request, fileName, loaded.doc)
            is LoadedFile.Archive -> try {
                importArchive(request, fileName, loaded.file)
            } finally {
                loaded.file.delete()
            }
        }
    }

    private suspend fun importArchive(request: ImportRequest, fileName: String, file: File): List<ImportResult> {
        fun failed(problem: ImportProblem) = listOf(ImportResult(fileName, null, ImportOutcome.Failed(problem)))
        val support = archives ?: return failed(ImportProblem.NOT_GPX)
        val archive = try {
            withContext(work) { support.open(file) }
        } catch (e: ArchiveException) {
            return failed(if (e.reason == ArchiveException.Reason.NOT_A_ZIP) ImportProblem.DAMAGED else ImportProblem.TOO_LARGE)
        } catch (e: java.io.IOException) {
            return failed(ImportProblem.DAMAGED)
        }
        return archive.use {
            try {
                when (archive.kind) {
                    BackupKind.FULL -> restoreBackup(archive, fileName, support)
                    BackupKind.ROUTES -> importRoutesZip(archive, request, fileName)
                }
            } catch (e: ArchiveException) {
                failed(if (e.reason == ArchiveException.Reason.ENTRY_TOO_LARGE) ImportProblem.TOO_LARGE else ImportProblem.DAMAGED)
            }
        }
    }

    private suspend fun restoreBackup(archive: BackupArchive, fileName: String, support: ArchiveSupport): List<ImportResult> {
        val owner = archive.manifest?.owner
        val fromThisPhone = owner != null && owner == support.ownerId()
        val question = ImportQuestion.RestoreBackup(fileName, archive.routes().size, archive.rides().size, fromThisPhone)
        val withRides = when (ask(question)) {
            ImportAnswer.RESTORE -> true
            ImportAnswer.ROUTES_ONLY -> false
            else -> return listOf(ImportResult(fileName, null, ImportOutcome.Skipped))
        }
        val summary = support.restorer.restore(archive, withRides)
        return listOf(ImportResult(fileName, null, ImportOutcome.Restored(summary)))
    }

    /** Each GPX in a zip is imported like a picked file: no preview each, but still the questions. */
    private suspend fun importRoutesZip(archive: BackupArchive, request: ImportRequest, fileName: String): List<ImportResult> {
        val entries = archive.gpxEntries()
        if (entries.isEmpty()) return listOf(ImportResult(fileName, null, ImportOutcome.Failed(ImportProblem.NO_TRACK)))
        return entries.flatMap { entry ->
            val entryName = entry.substringAfterLast('/')
            val doc = try {
                withContext(work) { archive.gpx(entry) }
            } catch (e: GpxParseException) {
                return@flatMap listOf(ImportResult(entryName, null, ImportOutcome.Failed(importProblemFor(e.reason))))
            }
            importDocument(request.copy(confirm = false), entryName, doc)
        }
    }

    private suspend fun importDocument(request: ImportRequest, fileName: String, doc: GpxDocument): List<ImportResult> {
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
    }
}
