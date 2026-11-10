package io.github.lrosa2.singletrail.data.rides

import io.github.lrosa2.singletrail.backup.BackupRide
import io.github.lrosa2.singletrail.data.db.Direction
import io.github.lrosa2.singletrail.data.db.RideEntity
import io.github.lrosa2.singletrail.data.db.RideStatus
import io.github.lrosa2.singletrail.data.db.SingletrailDatabase
import io.github.lrosa2.singletrail.data.db.StartMode
import io.github.lrosa2.singletrail.gpx.GpxDocument
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.roundToLong

/** Ride rows and their files: the track (`rides/<id>.track`) and the checkpoint (spec §6, §8.3). */
class RideRepository(
    db: SingletrailDatabase,
    private val filesDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val rides = db.rides()
    val checkpoints = CheckpointStore(filesDir)

    suspend fun start(routeId: String, routeName: String, reversed: Boolean): RideEntity {
        val id = UUID.randomUUID().toString()
        val ride = RideEntity(
            id = id,
            routeId = routeId,
            routeNameSnapshot = routeName,
            direction = if (reversed) Direction.REVERSE else Direction.FORWARD,
            startMode = StartMode.NEAREST_POINT,
            startedAt = clock(),
            endedAt = null,
            status = RideStatus.IN_PROGRESS,
            distanceM = 0.0,
            movingTimeS = 0,
            ascentM = null,
            trackPath = "rides/$id.track",
            segmentCount = 1,
        )
        rides.insert(ride)
        return ride
    }

    suspend fun get(id: String): RideEntity? = rides.get(id)

    suspend fun inProgress(): RideEntity? = rides.inProgress()

    /** Counts a new track segment for a resumed ride and returns its number. */
    suspend fun startSegment(id: String): Int {
        rides.addSegment(id)
        return requireNotNull(rides.get(id)) { "No ride $id" }.segmentCount
    }

    /** Ends the ride: [completed] when the route was finished, otherwise stopped early. */
    suspend fun finish(id: String, completed: Boolean, reversed: Boolean, distanceM: Double, movingSeconds: Double) {
        rides.end(
            id,
            if (completed) RideStatus.FINISHED else RideStatus.ABANDONED,
            clock(),
            if (reversed) Direction.REVERSE else Direction.FORWARD,
            distanceM,
            movingSeconds.roundToLong(),
        )
        checkpoints.delete(id)
        val ride = rides.get(id) ?: return
        val ascent = withContext(io) { RideGpx.ascent(TrackReader.read(trackFile(ride))) }
        rides.setAscent(id, ascent)
    }

    /** Ride history, newest first, without the ride in progress. */
    fun observeRides(): Flow<List<RideEntity>> = rides.observeEnded()

    fun observeRidesOf(routeId: String): Flow<List<RideEntity>> = rides.observeEndedForRoute(routeId)

    fun observeRide(id: String): Flow<RideEntity?> = rides.observe(id)

    suspend fun countForRoute(routeId: String): Int = rides.endedForRoute(routeId).size

    /** Every ended ride, for a backup. */
    suspend fun ended(): List<RideEntity> = rides.ended()

    /** A ride from a backup: its track file first, then its row, so a row never lacks its track. */
    suspend fun restore(ride: BackupRide, routeId: String?, points: List<TrackPoint>) {
        val entity = RideEntity(
            id = ride.id,
            routeId = routeId,
            routeNameSnapshot = ride.routeName,
            direction = enumOr(ride.direction, Direction.FORWARD),
            startMode = StartMode.NEAREST_POINT,
            startedAt = ride.startedAt,
            endedAt = ride.endedAt,
            status = enumOr(ride.status, RideStatus.FINISHED).takeIf { it != RideStatus.IN_PROGRESS } ?: RideStatus.ABANDONED,
            distanceM = ride.distanceM,
            movingTimeS = ride.movingTimeS,
            ascentM = ride.ascentM,
            trackPath = "rides/${ride.id}.track",
            segmentCount = points.maxOfOrNull { it.segment } ?: 1,
        )
        withContext(io) {
            TrackWriter(trackFile(entity), segment = 1, freeBytes = { Long.MAX_VALUE }).use { w -> points.forEach(w::append) }
        }
        try {
            rides.insert(entity)
        } catch (e: Throwable) {
            withContext(NonCancellable + io) { trackFile(entity).delete() }
            throw e
        }
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default

    suspend fun track(ride: RideEntity): List<TrackPoint> = withContext(io) { TrackReader.read(trackFile(ride)) }

    /** The ride as GPX (spec D19), named after its route and day. */
    suspend fun gpx(ride: RideEntity, name: String): GpxDocument = RideGpx.document(name, track(ride))

    /** Deletes a ride with its track, trace and checkpoint (spec §8.6). */
    suspend fun delete(id: String) {
        val ride = rides.get(id) ?: return
        rides.delete(id)
        deleteFiles(ride)
    }

    /** "Delete all ride history" (spec §8.6): every ended ride. Returns how many were deleted. */
    suspend fun deleteAll(): Int {
        val ended = rides.ended()
        ended.forEach { delete(it.id) }
        return ended.size
    }

    /** "Also delete its rides" when a route is deleted. */
    suspend fun deleteForRoute(routeId: String) {
        rides.endedForRoute(routeId).forEach { delete(it.id) }
    }

    fun trackFile(ride: RideEntity): File = File(filesDir, ride.trackPath)

    fun traceFile(rideId: String): File = File(filesDir, "traces/$rideId.jsonl")

    private suspend fun deleteFiles(ride: RideEntity) = withContext(io) {
        trackFile(ride).delete()
        traceFile(ride.id).delete()
        checkpoints.delete(ride.id)
    }
}
