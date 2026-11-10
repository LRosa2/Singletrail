package io.github.lrosa2.singletrail.data.routes

import io.github.lrosa2.singletrail.data.db.RouteEntity
import io.github.lrosa2.singletrail.data.db.RouteTombstoneEntity
import io.github.lrosa2.singletrail.data.db.SingletrailDatabase
import io.github.lrosa2.singletrail.data.db.SourceKind
import io.github.lrosa2.singletrail.data.db.WaypointEntity
import io.github.lrosa2.singletrail.gpx.GpxDocument
import io.github.lrosa2.singletrail.gpx.GpxWriter
import io.github.lrosa2.singletrail.importing.LibraryRoute
import io.github.lrosa2.singletrail.importing.NewRoute
import io.github.lrosa2.singletrail.importing.RouteLibrary
import io.github.lrosa2.singletrail.nav.PreparedRoute
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import kotlin.math.cos

/**
 * The route library: Room rows plus a normalised GPX copy of each route under `filesDir/routes/`
 * (spec §8.3). A row and its file are stored together: the file is written first and removed again
 * when the database write fails, so neither outlives the other.
 */
class RouteRepository(
    private val db: SingletrailDatabase,
    private val filesDir: File,
    private val readGpx: (InputStream) -> GpxDocument,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) : RouteLibrary {
    private val routes = db.routes()

    fun observeRoutes(): Flow<List<RouteEntity>> = routes.observeAll()

    fun observeRoute(id: String): Flow<RouteEntity?> = routes.observe(id)

    suspend fun waypoints(id: String): List<WaypointEntity> = routes.waypoints(id)

    fun gpxFile(route: RouteEntity): File = File(filesDir, route.gpxPath)

    /** Sets the personal stars (1–5), or clears them with null. */
    suspend fun setRating(id: String, rating: Int?) {
        require(rating == null || rating in 1..5) { "rating must be 1-5 or null, was $rating" }
        routes.setRating(id, rating, clock())
    }

    suspend fun edit(id: String, name: String, description: String?) {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "name must not be blank" }
        routes.edit(id, cleanName, description?.trim()?.ifEmpty { null }, clock())
    }

    suspend fun delete(id: String) {
        val route = routes.get(id) ?: return
        // A route deleted from the watched folder must not come back on the next scan (D36).
        val tombstone = if (route.sourceKind == SourceKind.FOLDER) RouteTombstoneEntity(route.geometryHash, clock()) else null
        routes.delete(id, tombstone)
        withContext(io) { gpxFile(route).delete() }
    }

    override suspend fun findByGeometryHash(hash: String): LibraryRoute? =
        routes.findByGeometryHash(hash)?.toLibraryRoute()

    override suspend fun similarCandidates(route: PreparedRoute): List<LibraryRoute> {
        val length = route.lengthMeters
        val points = route.points
        // Widen the box by the similarity tolerance, so a straight line shifted sideways still overlaps.
        val minLat = points.minOf { it.lat }
        val maxLat = points.maxOf { it.lat }
        val dLat = MAX_DEVIATION_M / METERS_PER_DEGREE
        val dLon = dLat / cos(Math.toRadians((minLat + maxLat) / 2)).coerceAtLeast(0.01)
        return routes.overlapping(
            minLength = length * (1 - MAX_LENGTH_DIFFERENCE),
            maxLength = length / (1 - MAX_LENGTH_DIFFERENCE),
            minLat = minLat - dLat,
            minLon = points.minOf { it.lon } - dLon,
            maxLat = maxLat + dLat,
            maxLon = points.maxOf { it.lon } + dLon,
        ).map { it.toLibraryRoute() }
    }

    override suspend fun prepared(id: String): PreparedRoute {
        val route = routes.get(id) ?: throw NoSuchElementException("No route $id")
        return withContext(io) {
            val doc = gpxFile(route).inputStream().buffered().use(readGpx)
            PreparedRoute.prepare(doc.tracks.single().routePoints())
        }
    }

    override suspend fun add(route: NewRoute): String {
        val id = UUID.randomUUID().toString()
        val path = "$ROUTES_DIR/$id.gpx"
        val file = File(filesDir, path)
        withContext(io) { writeGpx(route, route.name, route.description, file) }
        try {
            routes.insert(route.toEntity(id, path, clock()), route.waypointRows(id))
        } catch (e: Throwable) {
            withContext(NonCancellable + io) { file.delete() }
            throw e
        }
        return id
    }

    override suspend fun replace(id: String, route: NewRoute) {
        val existing = routes.get(id) ?: throw NoSuchElementException("No route $id")
        // A new file name, so the old copy stays intact until the database points at the new one.
        val path = "$ROUTES_DIR/$id-${UUID.randomUUID().toString().take(8)}.gpx"
        val file = File(filesDir, path)
        withContext(io) { writeGpx(route, existing.name, existing.description, file) }
        try {
            routes.replace(route.replacing(existing, clock()).copy(gpxPath = path), route.waypointRows(id))
        } catch (e: Throwable) {
            withContext(NonCancellable + io) { file.delete() }
            throw e
        }
        withContext(io) { gpxFile(existing).delete() }
    }

    private fun writeGpx(route: NewRoute, name: String, description: String?, target: File) {
        target.parentFile?.mkdirs()
        val tmp = File(target.path + ".tmp")
        try {
            tmp.bufferedWriter(Charsets.UTF_8).use {
                GpxWriter.write(GpxDocument(name, description, null, listOf(route.track), route.waypoints), it)
            }
            if (!tmp.renameTo(target)) throw IOException("Could not move ${tmp.name} into place")
        } finally {
            tmp.delete()
        }
    }

    private fun RouteEntity.toLibraryRoute() = LibraryRoute(id, name)

    private companion object {
        const val ROUTES_DIR = "routes"

        // Same tolerances as RouteIdentity.isSimilar's defaults (spec §8.4: 10 m / 2%).
        const val MAX_DEVIATION_M = 10.0
        const val MAX_LENGTH_DIFFERENCE = 0.02
        const val METERS_PER_DEGREE = 111_195.0
    }
}
