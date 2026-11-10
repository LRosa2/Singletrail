package io.github.lrosa2.singletrail.backup

import io.github.lrosa2.singletrail.data.rides.RideGpx
import io.github.lrosa2.singletrail.data.rides.RideRepository
import io.github.lrosa2.singletrail.data.rides.TrackPoint
import io.github.lrosa2.singletrail.data.rides.TrackReader
import io.github.lrosa2.singletrail.data.routes.RouteRepository
import io.github.lrosa2.singletrail.data.settings.SettingsStore
import io.github.lrosa2.singletrail.importing.NewRoute
import io.github.lrosa2.singletrail.nav.RouteIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream

/** Restores into the app's own library, rides and settings. */
class LibraryRestoreTarget(
    private val routes: RouteRepository,
    private val rides: RideRepository,
    private val settings: SettingsStore,
) : RestoreTarget {
    override suspend fun routeWithGeometry(hashes: RouteIdentity.GeometryHashes): String? =
        (routes.findByGeometryHash(hashes.forward) ?: routes.findByGeometryHash(hashes.reversed))?.id

    override suspend fun addRoute(route: NewRoute, rating: Int?, importedAt: Long, lastRiddenAt: Long?): String {
        val id = routes.add(route)
        routes.restoreDetails(id, rating, importedAt, lastRiddenAt)
        return id
    }

    override suspend fun hasRide(id: String): Boolean = rides.get(id) != null

    override suspend fun addRide(ride: BackupRide, routeId: String?, points: List<TrackPoint>) = rides.restore(ride, routeId, points)

    override suspend fun applySettings(settings: BackupSettings) {
        this.settings.update { settings.applyTo(it) }
    }
}

/** Writes a full backup of the library (spec §8.5): routes with stars, rides with tracks, settings. */
class BackupExporter(
    private val routes: RouteRepository,
    private val rides: RideRepository,
    private val settings: SettingsStore,
    private val appVersion: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun write(out: OutputStream) {
        val routeRows = routes.all()
        val byId = routeRows.associateBy { it.id }
        val rideRows = rides.ended()
        val current = settings.current()
        val owner = settings.backupOwner()
        val backupRoutes = routeRows.map {
            BackupRoute(
                id = it.id, name = it.name, description = it.description, rating = it.rating,
                sourceKind = it.sourceKind.name, sourceRef = it.sourceRef, importedAt = it.importedAt,
                updatedAt = it.updatedAt, lastRiddenAt = it.lastRiddenAt, geometryHash = it.geometryHash,
                file = "routes/${it.id}.gpx",
            )
        }
        val backupRides = rideRows.map {
            BackupRide(
                id = it.id, routeId = it.routeId, routeGeometryHash = it.routeId?.let { r -> byId[r]?.geometryHash },
                routeName = it.routeNameSnapshot, direction = it.direction.name, startedAt = it.startedAt,
                endedAt = it.endedAt, status = it.status.name, distanceM = it.distanceM, movingTimeS = it.movingTimeS,
                ascentM = it.ascentM, segmentCount = it.segmentCount, file = "rides/${it.id}.gpx",
            )
        }
        val manifest = BackupManifest(
            kind = BackupManifest.KIND_FULL, app = appVersion, createdAt = clock(), owner = owner,
            routes = backupRoutes.size, rides = backupRides.size,
        )
        withContext(Dispatchers.IO) {
            BackupZip.writeFull(
                out,
                manifest,
                backupRoutes,
                { r -> routes.sharedGpxBlocking(byId.getValue(r.id)) },
                backupRides,
                { r -> RideGpx.document(r.routeName, TrackReader.read(rides.trackFile(rideRows.first { it.id == r.id }))) },
                BackupSettings.of(current),
            )
        }
        settings.markBackedUp(clock())
    }
}
