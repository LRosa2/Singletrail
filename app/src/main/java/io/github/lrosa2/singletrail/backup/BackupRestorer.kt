package io.github.lrosa2.singletrail.backup

import io.github.lrosa2.singletrail.data.db.SourceKind
import io.github.lrosa2.singletrail.data.rides.RideGpx
import io.github.lrosa2.singletrail.data.rides.TrackPoint
import io.github.lrosa2.singletrail.data.routes.routePoints
import io.github.lrosa2.singletrail.importing.NewRoute
import io.github.lrosa2.singletrail.nav.PreparedRoute
import io.github.lrosa2.singletrail.nav.RouteIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What restoring a backup needs from the app's data. */
interface RestoreTarget {
    /** The local route with this geometry, stored either way round, or null. */
    suspend fun routeWithGeometry(hashes: RouteIdentity.GeometryHashes): String?

    /** Adds a route from a backup with its stars and dates; returns its new id. */
    suspend fun addRoute(route: NewRoute, rating: Int?, importedAt: Long, lastRiddenAt: Long?): String

    suspend fun hasRide(id: String): Boolean

    /** Adds a ride with its recorded track, attached to [routeId] (null when its route isn't here). */
    suspend fun addRide(ride: BackupRide, routeId: String?, points: List<TrackPoint>)

    /** Takes over the backup's settings; only when everything is restored. */
    suspend fun applySettings(settings: BackupSettings) {}

    /** Puts routes in the collection named [name], creating it even for no routes (spec §8.5 merge rules). */
    suspend fun collect(name: String, routeIds: List<String>) {}
}

data class RestoreSummary(
    val routesAdded: Int,
    val routesKept: Int,
    val ridesAdded: Int,
    val ridesSkipped: Int,
    val failed: Int,
)

/**
 * Restores a full backup with the merge rules of spec §8.5: routes are matched by geometry and the
 * local name, description and stars win; rides attach to the matched route and are skipped when a
 * ride with the same id is already here. One bad entry never stops the rest.
 */
class BackupRestorer(
    private val target: RestoreTarget,
    private val work: CoroutineDispatcher = Dispatchers.Default,
) {
    suspend fun restore(archive: BackupArchive, withRides: Boolean): RestoreSummary {
        val localIds = HashMap<String, String>() // Backup route id → route id here.
        var added = 0
        var kept = 0
        var failed = 0
        for (r in archive.routes()) {
            try {
                val doc = archive.gpx(r.file)
                val track = doc.joinedTrack() ?: throw IllegalArgumentException("No track in ${r.file}")
                val points = track.routePoints()
                val hashes = withContext(work) { RouteIdentity.hashes(points) }
                val local = target.routeWithGeometry(hashes)
                if (local != null) {
                    localIds[r.id] = local
                    kept++
                    continue
                }
                val prepared = withContext(work) { PreparedRoute.prepare(points).also { it.elevation } }
                val route = NewRoute(r.name, r.description, SourceKind.BACKUP, r.sourceRef, track, doc.waypoints, prepared, hashes)
                localIds[r.id] = target.addRoute(route, r.rating?.takeIf { it in 1..5 }, r.importedAt, r.lastRiddenAt)
                added++
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failed++
            }
        }

        // Collections are matched by name; memberships are added, never removed (spec §8.5).
        val members = LinkedHashMap<String, MutableList<String>>()
        archive.collections().forEach { members.getOrPut(it.name) { ArrayList() } }
        for (r in archive.routes()) {
            val local = localIds[r.id] ?: continue
            r.collections.forEach { members.getOrPut(it) { ArrayList() } += local }
        }
        for ((name, ids) in members) {
            try {
                target.collect(name, ids)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failed++
            }
        }

        var ridesAdded = 0
        var ridesSkipped = 0
        if (withRides) {
            archive.settings()?.let { target.applySettings(it) }
            for (ride in archive.rides()) {
                if (ride.status == "IN_PROGRESS") continue
                try {
                    if (target.hasRide(ride.id)) {
                        ridesSkipped++
                        continue
                    }
                    val points = if (archive.has(ride.file)) RideGpx.trackPoints(archive.gpx(ride.file)) else emptyList()
                    target.addRide(ride, ride.routeId?.let { localIds[it] }, points)
                    ridesAdded++
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    failed++
                }
            }
        }
        return RestoreSummary(added, kept, ridesAdded, ridesSkipped, failed)
    }
}
