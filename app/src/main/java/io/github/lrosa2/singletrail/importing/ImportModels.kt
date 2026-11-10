package io.github.lrosa2.singletrail.importing

import io.github.lrosa2.singletrail.backup.BackupArchive
import io.github.lrosa2.singletrail.backup.BackupRestorer
import io.github.lrosa2.singletrail.backup.RestoreSummary
import io.github.lrosa2.singletrail.data.db.SourceKind
import java.io.File
import io.github.lrosa2.singletrail.gpx.GpxDocument
import io.github.lrosa2.singletrail.gpx.GpxParseException
import io.github.lrosa2.singletrail.gpx.GpxTrack
import io.github.lrosa2.singletrail.gpx.GpxWaypoint
import io.github.lrosa2.singletrail.nav.PreparedRoute
import io.github.lrosa2.singletrail.nav.RouteIdentity
import io.github.lrosa2.singletrail.nav.RoutePosition

/** One file to import. [confirm] shows a preview with "Add to library" before storing it. */
data class ImportRequest(val uri: String, val kind: SourceKind, val confirm: Boolean)

/** What an import request turned out to hold. */
sealed interface LoadedFile {
    data class Gpx(val doc: GpxDocument) : LoadedFile

    /** A zip, copied to a temporary [file] that the import queue deletes when it is done. */
    data class Archive(val file: File) : LoadedFile
}

/** Opens and parses an import request. */
interface GpxSource {
    /** The file's name to show in messages, or null when unknown. Never throws. */
    suspend fun displayName(request: ImportRequest): String?

    /**
     * @throws GpxParseException when the content isn't safe, valid GPX
     * @throws java.io.IOException or [SecurityException] when it can't be read
     */
    suspend fun load(request: ImportRequest): LoadedFile
}

/** A link that couldn't be downloaded (spec §8.4, S4). */
class DownloadException(val reason: Reason, message: String) : java.io.IOException(message) {
    enum class Reason { NOT_HTTPS, HTTP_ERROR, TOO_LARGE }
}

/** Zips in the import queue (spec §8.5: "Import detects which kind of file it is"). */
class ArchiveSupport(
    val open: (File) -> BackupArchive,
    val restorer: BackupRestorer,
    /** This installation's backup owner id, to recognise its own full backups. */
    val ownerId: suspend () -> String,
)

/** What the import queue needs from the route library. */
interface RouteLibrary {
    suspend fun findByGeometryHash(hash: String): LibraryRoute?

    /** Routes that could be similar to [route]: about the same length, overlapping area. */
    suspend fun similarCandidates(route: PreparedRoute): List<LibraryRoute>

    suspend fun prepared(id: String): PreparedRoute

    /** Stores a new route and returns its id. */
    suspend fun add(route: NewRoute): String

    /** Replaces the geometry of route [id], keeping its name, description, stars and rides. */
    suspend fun replace(id: String, route: NewRoute)
}

data class LibraryRoute(val id: String, val name: String)

/** A parsed, prepared route ready to be stored. */
class NewRoute(
    val name: String,
    val description: String?,
    val source: SourceKind,
    val sourceRef: String?,
    val track: GpxTrack,
    val waypoints: List<GpxWaypoint>,
    val prepared: PreparedRoute,
    val hashes: RouteIdentity.GeometryHashes,
)

/** A summary shown before adding or updating a route. */
data class RoutePreview(
    val name: String,
    val description: String?,
    val distanceM: Double,
    val ascentM: Double?,
    val descentM: Double?,
    val waypointCount: Int,
    /** A thinned copy of the route's shape for drawing an outline. */
    val outline: List<RoutePosition>,
)

enum class ImportAnswer { ADD, SPLIT, JOIN, UPDATE, KEEP_BOTH, RESTORE, ROUTES_ONLY, CANCEL }

/** A decision the import queue waits for before it continues. */
sealed interface ImportQuestion {
    val fileName: String
    val options: List<ImportAnswer>

    data class MultipleTracks(override val fileName: String, val trackNames: List<String?>) : ImportQuestion {
        override val options = listOf(ImportAnswer.SPLIT, ImportAnswer.JOIN, ImportAnswer.CANCEL)
    }

    data class ConfirmAdd(override val fileName: String, val preview: RoutePreview) : ImportQuestion {
        override val options = listOf(ImportAnswer.ADD, ImportAnswer.CANCEL)
    }

    data class SimilarRoute(
        override val fileName: String,
        val preview: RoutePreview,
        val existing: LibraryRoute,
    ) : ImportQuestion {
        override val options = listOf(ImportAnswer.UPDATE, ImportAnswer.KEEP_BOTH, ImportAnswer.CANCEL)
    }

    /**
     * A full backup (spec §8.5). Someone else's backup offers its routes only as the default, since
     * its rides are another person's location history.
     */
    data class RestoreBackup(
        override val fileName: String,
        val routes: Int,
        val rides: Int,
        val fromThisPhone: Boolean,
    ) : ImportQuestion {
        override val options = if (fromThisPhone) {
            listOf(ImportAnswer.RESTORE, ImportAnswer.CANCEL)
        } else {
            listOf(ImportAnswer.ROUTES_ONLY, ImportAnswer.RESTORE, ImportAnswer.CANCEL)
        }
    }
}

/** Why a file or track couldn't be imported. */
enum class ImportProblem {
    NOT_GPX, DAMAGED, UNSAFE_XML, TOO_LARGE, TOO_MANY_POINTS, TOO_MANY_WAYPOINTS, TOO_MANY_TRACKS,
    NO_TRACK, TOO_SHORT, UNREADABLE, SAVE_FAILED, DOWNLOAD_FAILED,
}

sealed interface ImportOutcome {
    data class Added(val routeId: String) : ImportOutcome
    data class Updated(val routeId: String) : ImportOutcome
    data class AlreadyInLibrary(val routeId: String, val reversed: Boolean) : ImportOutcome
    data object Skipped : ImportOutcome
    data class Failed(val problem: ImportProblem) : ImportOutcome
    data class Restored(val summary: RestoreSummary) : ImportOutcome
}

data class ImportResult(val fileName: String, val routeName: String?, val outcome: ImportOutcome)

data class ImportState(
    /** File being imported right now, or null when idle. */
    val working: String? = null,
    /** Files waiting behind the current one. */
    val queued: Int = 0,
    val question: ImportQuestion? = null,
    /** Results since the user last dismissed them. */
    val results: List<ImportResult> = emptyList(),
) {
    val busy: Boolean get() = working != null || queued > 0
}
