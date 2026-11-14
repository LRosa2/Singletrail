package io.github.lrosa2.singletrail.data.importing

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import io.github.lrosa2.singletrail.data.db.WatchedFileDao
import io.github.lrosa2.singletrail.data.db.WatchedFileEntity
import io.github.lrosa2.singletrail.data.routes.RouteRepository
import io.github.lrosa2.singletrail.data.settings.SettingsStore
import io.github.lrosa2.singletrail.gpx.GpxDocument
import io.github.lrosa2.singletrail.importing.FolderFile
import io.github.lrosa2.singletrail.importing.FolderLibrary
import io.github.lrosa2.singletrail.importing.FolderSource
import io.github.lrosa2.singletrail.importing.ImportCollections
import io.github.lrosa2.singletrail.importing.ImportCoordinator
import io.github.lrosa2.singletrail.importing.NewRoute
import io.github.lrosa2.singletrail.importing.WatchedFile
import io.github.lrosa2.singletrail.importing.WatchedFileRegistry
import io.github.lrosa2.singletrail.importing.WatchedFolderScanner
import io.github.lrosa2.singletrail.nav.RouteIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream

/** The watched-folder registry in Room (spec §8.2 `watched_files`). */
class RoomWatchedFileRegistry(private val dao: WatchedFileDao) : WatchedFileRegistry {
    override suspend fun get(uri: String): WatchedFile? =
        dao.get(uri)?.let { WatchedFile(it.uri, it.lastHash, it.routeId, it.status, it.lastSeenAt) }

    override suspend fun put(file: WatchedFile) =
        dao.put(WatchedFileEntity(file.uri, file.contentHash, file.routeId, file.status, file.lastSeenAt))
}

class RepositoryFolderLibrary(private val routes: RouteRepository) : FolderLibrary {
    override suspend fun routeWithGeometry(hashes: RouteIdentity.GeometryHashes): String? =
        (routes.findByGeometryHash(hashes.forward) ?: routes.findByGeometryHash(hashes.reversed))?.id

    override suspend fun isTombstoned(hashes: RouteIdentity.GeometryHashes) = routes.isTombstoned(hashes.forward, hashes.reversed)

    override suspend fun forgetTombstones(hashes: RouteIdentity.GeometryHashes) = routes.forgetTombstones(hashes.forward, hashes.reversed)

    override suspend fun exists(id: String) = routes.exists(id)

    override suspend fun add(route: NewRoute): String = routes.add(route)

    override suspend fun replace(id: String, route: NewRoute) = routes.replace(id, route)
}

/**
 * GPX files in a document tree the user picked (`ACTION_OPEN_DOCUMENT_TREE`), read-only. Looks a
 * few folders deep and stops at [MAX_FILES], so a huge folder can't stall the app.
 */
class TreeFolderSource(private val resolver: ContentResolver, private val tree: Uri) : FolderSource {

    override suspend fun list(): List<FolderFile> = withContext(Dispatchers.IO) {
        val found = ArrayList<FolderFile>()
        // Each folder to list with its name; the top level has none, so its files join no collection.
        var level = listOf<Pair<String, String?>>(DocumentsContract.getTreeDocumentId(tree) to null)
        for (depth in 0..MAX_DEPTH) {
            val next = ArrayList<Pair<String, String?>>()
            for ((parent, folderName) in level) {
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
                val columns = arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                )
                resolver.query(children, columns, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0) ?: continue
                        val name = c.getString(1) ?: continue
                        if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                            next += id to name
                        } else if (name.endsWith(".gpx", ignoreCase = true)) {
                            found += FolderFile(DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(), name, folderName)
                            if (found.size >= MAX_FILES) return@withContext found
                        }
                    }
                }
            }
            level = next
        }
        found
    }

    override suspend fun read(file: FolderFile): ByteArray = withContext(Dispatchers.IO) {
        val input = resolver.openInputStream(file.uri.toUri()) ?: throw IOException("No stream for ${file.name}")
        input.use { it.readCapped(MAX_BYTES) }
    }

    private fun InputStream.readCapped(max: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = read(buffer)
            if (n < 0) break
            total += n
            if (total > max) throw IOException("Larger than $max bytes")
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private companion object {
        const val MAX_DEPTH = 3
        const val MAX_FILES = 1_000
        const val MAX_BYTES = 20L * 1024 * 1024
    }
}

/** The watched folder setting and its scans (spec §8.4). Scans run in the import queue. */
class WatchedFolder(
    private val resolver: ContentResolver,
    private val settings: SettingsStore,
    private val importer: ImportCoordinator,
    private val routes: RouteRepository,
    private val dao: WatchedFileDao,
    private val readGpx: (InputStream) -> GpxDocument,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Subfolders become collections (spec §8.4 step 7). */
    private val collections: ImportCollections? = null,
) {
    @Volatile
    private var lastScan = 0L

    val folder: Flow<String?> = settings.watchedFolder

    val notImported: Flow<List<WatchedFileEntity>> = dao.observeNotImported()

    /** Remembers the folder with a persistable read grant, then scans it. */
    suspend fun choose(tree: Uri) {
        resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        settings.setWatchedFolder(tree.toString())
        lastScan = 0L
        scanIfDue()
    }

    suspend fun stop() {
        val current = settings.currentWatchedFolder() ?: return
        try {
            resolver.releasePersistableUriPermission(current.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Already gone.
        }
        settings.setWatchedFolder(null)
    }

    /** "Scanned when the app opens": at most once a minute, however often the app comes to the front. */
    suspend fun scanIfDue() {
        val tree = settings.currentWatchedFolder()?.toUri() ?: return
        val now = clock()
        if (now - lastScan < MIN_INTERVAL_MS) return
        lastScan = now
        importer.enqueueJob(JOB_NAME) {
            try {
                scanner(tree).scan().results
            } catch (_: SecurityException) {
                emptyList() // The folder was removed or access revoked; the settings screen says so.
            }
        }
    }

    suspend fun importAnyway(fileUri: String) {
        val tree = settings.currentWatchedFolder()?.toUri() ?: return
        importer.enqueueJob(JOB_NAME) { listOfNotNull(scanner(tree).importAnyway(fileUri)) }
    }

    /** Whether the app still holds read access to the watched [folder] (a moved or deleted folder loses it). */
    fun canRead(folder: String): Boolean =
        resolver.persistedUriPermissions.any { it.uri.toString() == folder && it.isReadPermission }

    /** A display name for a folder (tree) or file (document in a tree) URI. */
    suspend fun displayName(uri: String): String? = withContext(Dispatchers.IO) {
        try {
            val documentUri = uri.toUri().let {
                // A tree URI is ".../tree/<id>"; a file in it is ".../tree/<id>/document/<docId>".
                val segments = it.pathSegments
                if (segments.firstOrNull() == "tree" && "document" !in segments) {
                    DocumentsContract.buildDocumentUriUsingTree(it, DocumentsContract.getTreeDocumentId(it))
                } else {
                    it
                }
            }
            resolver.query(documentUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun scanner(tree: Uri) = WatchedFolderScanner(
        TreeFolderSource(resolver, tree),
        RoomWatchedFileRegistry(dao),
        RepositoryFolderLibrary(routes),
        readGpx,
        clock = clock,
        collections = collections,
    )

    private companion object {
        const val JOB_NAME = "Watched folder"
        const val MIN_INTERVAL_MS = 60_000L
    }
}
