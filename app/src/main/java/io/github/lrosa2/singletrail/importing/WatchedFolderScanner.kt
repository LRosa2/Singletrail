package io.github.lrosa2.singletrail.importing

import io.github.lrosa2.singletrail.data.db.SourceKind
import io.github.lrosa2.singletrail.data.db.WatchedFileStatus
import io.github.lrosa2.singletrail.data.routes.routePoints
import io.github.lrosa2.singletrail.gpx.GpxDocument
import io.github.lrosa2.singletrail.gpx.GpxParseException
import io.github.lrosa2.singletrail.nav.PreparedRoute
import io.github.lrosa2.singletrail.nav.RouteIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest

/** A GPX file in the watched folder; [folder] is its own folder's name, null at the top level. */
data class FolderFile(val uri: String, val name: String, val folder: String? = null)

/** The watched folder (spec §8.4): a persisted, read-only document tree. */
interface FolderSource {
    suspend fun list(): List<FolderFile>

    /** The file's bytes; implementations refuse files over the GPX size limit. */
    suspend fun read(file: FolderFile): ByteArray
}

/** One row of the watched-file registry (spec §8.2 `watched_files`). */
data class WatchedFile(
    val uri: String,
    /** SHA-256 of the file's bytes when last read. */
    val contentHash: String?,
    val routeId: String?,
    val status: WatchedFileStatus,
    val lastSeenAt: Long,
)

interface WatchedFileRegistry {
    suspend fun get(uri: String): WatchedFile?
    suspend fun put(file: WatchedFile)
}

/** What the scanner needs from the route library. */
interface FolderLibrary {
    /** The route with this geometry, stored either way round, or null. */
    suspend fun routeWithGeometry(hashes: RouteIdentity.GeometryHashes): String?

    /** Deleted by the user from the watched folder before (D36). */
    suspend fun isTombstoned(hashes: RouteIdentity.GeometryHashes): Boolean

    suspend fun forgetTombstones(hashes: RouteIdentity.GeometryHashes)

    suspend fun exists(id: String): Boolean

    suspend fun add(route: NewRoute): String

    suspend fun replace(id: String, route: NewRoute)
}

/**
 * Scans the watched folder when the app opens (spec §8.4). New files become routes, unchanged ones
 * are skipped, a changed file updates the route it made, and files whose route the user deleted stay
 * away ("Show ignored files" lists them). Quiet by design: no questions; several tracks are joined.
 */
class WatchedFolderScanner(
    private val folder: FolderSource,
    private val registry: WatchedFileRegistry,
    private val library: FolderLibrary,
    private val readGpx: (InputStream) -> GpxDocument,
    private val work: CoroutineDispatcher = Dispatchers.Default,
    private val clock: () -> Long = System::currentTimeMillis,
    private val collections: ImportCollections? = null,
) {
    data class Scan(
        /** Routes added or updated, and files that failed; nothing for skipped or linked files. */
        val results: List<ImportResult>,
        val ignored: Int,
        val unchanged: Int,
    )

    private sealed interface Step {
        data object Unchanged : Step
        data object Ignored : Step
        data object Linked : Step
        data class Reported(val result: ImportResult) : Step
    }

    suspend fun scan(): Scan {
        val results = ArrayList<ImportResult>()
        var ignored = 0
        var unchanged = 0
        for (file in folder.list()) {
            when (val step = check(file, force = false)) {
                Step.Unchanged -> unchanged++
                Step.Ignored -> ignored++
                Step.Linked -> Unit
                is Step.Reported -> results += step.result
            }
        }
        return Scan(results, ignored, unchanged)
    }

    /** Imports an ignored file after all, forgetting that its route was deleted. */
    suspend fun importAnyway(uri: String): ImportResult? {
        val file = folder.list().firstOrNull { it.uri == uri } ?: return null
        return (check(file, force = true) as? Step.Reported)?.result
    }

    private suspend fun check(file: FolderFile, force: Boolean): Step {
        val now = clock()
        val previous = registry.get(file.uri)
        fun record(hash: String?, routeId: String?, status: WatchedFileStatus) =
            WatchedFile(file.uri, hash, routeId, status, now)
        suspend fun failed(hash: String?, problem: ImportProblem): Step {
            registry.put(record(hash, null, WatchedFileStatus.ERROR))
            return Step.Reported(ImportResult(file.name, null, ImportOutcome.Failed(problem)))
        }

        val bytes = try {
            folder.read(file)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Reported once: a file that keeps failing to open isn't news.
            if (previous?.status == WatchedFileStatus.ERROR && previous.contentHash == null) {
                registry.put(previous.copy(lastSeenAt = now))
                return Step.Unchanged
            }
            return failed(null, ImportProblem.UNREADABLE)
        }
        val hash = withContext(work) { sha256(bytes) }
        if (!force && previous != null && previous.contentHash == hash) {
            val routeDeleted = previous.status == WatchedFileStatus.IMPORTED &&
                (previous.routeId == null || !library.exists(previous.routeId))
            if (routeDeleted) {
                // The user deleted the route this file made: it stays out, and shows under ignored files.
                registry.put(previous.copy(routeId = null, status = WatchedFileStatus.IGNORED, lastSeenAt = now))
                return Step.Ignored
            }
            registry.put(previous.copy(lastSeenAt = now))
            return Step.Unchanged
        }

        val doc = try {
            withContext(work) { readGpx(ByteArrayInputStream(bytes)) }
        } catch (e: GpxParseException) {
            return failed(hash, importProblemFor(e.reason))
        }
        val track = doc.joinedTrack() ?: return failed(hash, ImportProblem.NO_TRACK)
        val points = track.routePoints()
        val hashes = withContext(work) { RouteIdentity.hashes(points) }
        if (force) {
            library.forgetTombstones(hashes)
        } else if (library.isTombstoned(hashes)) {
            registry.put(record(hash, null, WatchedFileStatus.IGNORED))
            return Step.Ignored
        }
        library.routeWithGeometry(hashes)?.let { id ->
            registry.put(record(hash, id, WatchedFileStatus.IMPORTED))
            group(file, id)
            return Step.Linked
        }
        val prepared = try {
            withContext(work) { PreparedRoute.prepare(points).also { it.elevation } }
        } catch (_: IllegalArgumentException) {
            return failed(hash, ImportProblem.TOO_SHORT)
        }
        val name = RouteNames.clean(track.name) ?: file.name.replace(GPX_SUFFIX, "")
        val route = NewRoute(name, track.description, SourceKind.FOLDER, file.name, track, doc.waypoints, prepared, hashes)
        val ownRoute = previous?.routeId?.takeIf { library.exists(it) }
        return try {
            if (ownRoute != null) {
                // The file changed: it is still the source of the route it made.
                library.replace(ownRoute, route)
                registry.put(record(hash, ownRoute, WatchedFileStatus.IMPORTED))
                group(file, ownRoute)
                Step.Reported(ImportResult(file.name, name, ImportOutcome.Updated(ownRoute)))
            } else {
                val id = library.add(route)
                registry.put(record(hash, id, WatchedFileStatus.IMPORTED))
                group(file, id)
                Step.Reported(ImportResult(file.name, name, ImportOutcome.Added(id)))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failed(hash, ImportProblem.SAVE_FAILED)
        }
    }

    /**
     * A file in a subfolder joins the collection named after that folder (spec §8.4 step 7). Only a
     * side effect: when it fails, the file is still imported and linked to its route.
     */
    private suspend fun group(file: FolderFile, routeId: String) {
        val name = file.folder ?: return
        val group = collections ?: return
        try {
            group.addTo(name, listOf(routeId))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Left out of the collection; the next change to the file tries again.
        }
    }

    private companion object {
        val GPX_SUFFIX = Regex("""\.gpx$""", RegexOption.IGNORE_CASE)

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

/** The import problem for a GPX parse failure. */
internal fun importProblemFor(reason: GpxParseException.Reason): ImportProblem = when (reason) {
    GpxParseException.Reason.NOT_GPX -> ImportProblem.NOT_GPX
    GpxParseException.Reason.MALFORMED_XML -> ImportProblem.DAMAGED
    GpxParseException.Reason.DOCTYPE_NOT_ALLOWED -> ImportProblem.UNSAFE_XML
    GpxParseException.Reason.FILE_TOO_LARGE -> ImportProblem.TOO_LARGE
    GpxParseException.Reason.TOO_MANY_POINTS -> ImportProblem.TOO_MANY_POINTS
    GpxParseException.Reason.TOO_MANY_WAYPOINTS -> ImportProblem.TOO_MANY_WAYPOINTS
    GpxParseException.Reason.TOO_MANY_TRACKS -> ImportProblem.TOO_MANY_TRACKS
}
