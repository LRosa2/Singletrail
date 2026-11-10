package io.github.lrosa2.singletrail.data.rides

import io.github.lrosa2.singletrail.data.db.Direction
import io.github.lrosa2.singletrail.data.db.RideEntity
import io.github.lrosa2.singletrail.data.db.RideStatus
import io.github.lrosa2.singletrail.data.db.SingletrailDatabase
import io.github.lrosa2.singletrail.data.db.StartMode
import java.io.File
import java.util.UUID
import kotlin.math.roundToLong

/** Ride rows and their files: the track (`rides/<id>.track`) and the checkpoint (spec §6, §8.3). */
class RideRepository(
    db: SingletrailDatabase,
    private val filesDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
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
    }

    fun trackFile(ride: RideEntity): File = File(filesDir, ride.trackPath)

    fun traceFile(rideId: String): File = File(filesDir, "traces/$rideId.jsonl")
}
