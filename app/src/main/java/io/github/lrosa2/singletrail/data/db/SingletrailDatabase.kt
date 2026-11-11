package io.github.lrosa2.singletrail.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Database(
    entities = [
        RouteEntity::class,
        ManeuverEntity::class,
        WaypointEntity::class,
        RideEntity::class,
        WatchedFileEntity::class,
        RouteTombstoneEntity::class,
        MapFileEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class SingletrailDatabase : RoomDatabase() {
    abstract fun routes(): RouteDao
    abstract fun rides(): RideDao
    abstract fun watchedFiles(): WatchedFileDao
    abstract fun maps(): MapDao

    companion object {
        fun open(context: Context): SingletrailDatabase =
            Room.databaseBuilder(context, SingletrailDatabase::class.java, "singletrail.db").build()
    }
}

@Dao
abstract class RouteDao {
    @Query("SELECT * FROM routes")
    abstract fun observeAll(): Flow<List<RouteEntity>>

    @Query("SELECT * FROM routes WHERE id = :id")
    abstract fun observe(id: String): Flow<RouteEntity?>

    @Query("SELECT * FROM routes WHERE id = :id")
    abstract suspend fun get(id: String): RouteEntity?

    @Query("SELECT * FROM routes WHERE geometryHash = :hash")
    abstract suspend fun findByGeometryHash(hash: String): RouteEntity?

    /** Routes with a length in [minLength, maxLength] whose bounding box overlaps the given one. */
    @Query(
        """SELECT * FROM routes
           WHERE distanceM BETWEEN :minLength AND :maxLength
             AND maxLat >= :minLat AND minLat <= :maxLat
             AND maxLon >= :minLon AND minLon <= :maxLon""",
    )
    abstract suspend fun overlapping(
        minLength: Double,
        maxLength: Double,
        minLat: Double,
        minLon: Double,
        maxLat: Double,
        maxLon: Double,
    ): List<RouteEntity>

    @Query("SELECT * FROM waypoints WHERE routeId = :routeId ORDER BY seq")
    abstract suspend fun waypoints(routeId: String): List<WaypointEntity>

    @Query("UPDATE routes SET rating = :rating, updatedAt = :now WHERE id = :id")
    abstract suspend fun setRating(id: String, rating: Int?, now: Long)

    @Query("UPDATE routes SET name = :name, description = :description, updatedAt = :now WHERE id = :id")
    abstract suspend fun edit(id: String, name: String, description: String?, now: Long)

    @Query("SELECT * FROM routes ORDER BY importedAt")
    abstract suspend fun all(): List<RouteEntity>

    @Query("SELECT COUNT(*) FROM route_tombstones WHERE geometryHash IN (:hashes)")
    abstract suspend fun tombstoneCount(hashes: List<String>): Int

    @Query("DELETE FROM route_tombstones WHERE geometryHash IN (:hashes)")
    abstract suspend fun deleteTombstones(hashes: List<String>)

    /** Stars and dates of a route restored from a backup. */
    @Query("UPDATE routes SET rating = :rating, importedAt = :importedAt, lastRiddenAt = :lastRiddenAt WHERE id = :id")
    abstract suspend fun restoreDetails(id: String, rating: Int?, importedAt: Long, lastRiddenAt: Long?)

    @Transaction
    open suspend fun insert(route: RouteEntity, waypoints: List<WaypointEntity>) {
        insertRoute(route)
        insertWaypoints(waypoints)
    }

    /** New geometry for an existing route: row, waypoints, and no maneuvers until they are recomputed. */
    @Transaction
    open suspend fun replace(route: RouteEntity, waypoints: List<WaypointEntity>) {
        updateRoute(route)
        deleteWaypoints(route.id)
        insertWaypoints(waypoints)
        deleteManeuvers(route.id)
    }

    /** Deletes the route (waypoints and maneuvers cascade) and, when given, records its tombstone. */
    @Transaction
    open suspend fun delete(id: String, tombstone: RouteTombstoneEntity?) {
        deleteRoute(id)
        if (tombstone != null) insertTombstone(tombstone)
    }

    @Insert
    protected abstract suspend fun insertRoute(route: RouteEntity)

    @Update
    protected abstract suspend fun updateRoute(route: RouteEntity)

    @Insert
    protected abstract suspend fun insertWaypoints(waypoints: List<WaypointEntity>)

    @Query("DELETE FROM waypoints WHERE routeId = :routeId")
    protected abstract suspend fun deleteWaypoints(routeId: String)

    @Query("DELETE FROM maneuvers WHERE routeId = :routeId")
    protected abstract suspend fun deleteManeuvers(routeId: String)

    @Query("DELETE FROM routes WHERE id = :id")
    protected abstract suspend fun deleteRoute(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertTombstone(tombstone: RouteTombstoneEntity)
}

@Dao
abstract class WatchedFileDao {
    @Query("SELECT * FROM watched_files WHERE uri = :uri")
    abstract suspend fun get(uri: String): WatchedFileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun put(file: WatchedFileEntity)

    /** "Show ignored files" (spec §8.4): files skipped because their route was deleted, and broken ones. */
    @Query("SELECT * FROM watched_files WHERE status != 'IMPORTED' ORDER BY lastSeenAt DESC")
    abstract fun observeNotImported(): Flow<List<WatchedFileEntity>>
}

/** Installed offline maps (spec §7.3). */
@Dao
abstract class MapDao {
    @Query("SELECT * FROM map_files ORDER BY displayName COLLATE NOCASE")
    abstract fun observeAll(): Flow<List<MapFileEntity>>

    @Query("SELECT * FROM map_files")
    abstract suspend fun all(): List<MapFileEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun put(map: MapFileEntity)

    @Query("DELETE FROM map_files WHERE id = :id")
    abstract suspend fun remove(id: String)
}

@Dao
abstract class RideDao {
    @Query("SELECT COUNT(*) FROM rides WHERE routeId = :routeId")
    abstract suspend fun countForRoute(routeId: String): Int

    @Insert
    abstract suspend fun insert(ride: RideEntity)

    @Query("SELECT * FROM rides WHERE id = :id")
    abstract suspend fun get(id: String): RideEntity?

    @Query("SELECT * FROM rides WHERE id = :id")
    abstract fun observe(id: String): Flow<RideEntity?>

    /** The ride to resume when the app opens (spec §6); there is at most one. */
    @Query("SELECT * FROM rides WHERE status = 'IN_PROGRESS' ORDER BY startedAt DESC LIMIT 1")
    abstract suspend fun inProgress(): RideEntity?

    @Query("UPDATE rides SET segmentCount = segmentCount + 1 WHERE id = :id")
    abstract suspend fun addSegment(id: String)

    /** Ride history, newest first; the ride in progress isn't history yet. */
    @Query("SELECT * FROM rides WHERE status != 'IN_PROGRESS' ORDER BY startedAt DESC")
    abstract fun observeEnded(): Flow<List<RideEntity>>

    @Query("SELECT * FROM rides WHERE routeId = :routeId AND status != 'IN_PROGRESS' ORDER BY startedAt DESC")
    abstract fun observeEndedForRoute(routeId: String): Flow<List<RideEntity>>

    @Query("SELECT * FROM rides WHERE status != 'IN_PROGRESS'")
    abstract suspend fun ended(): List<RideEntity>

    @Query("SELECT * FROM rides WHERE routeId = :routeId AND status != 'IN_PROGRESS'")
    abstract suspend fun endedForRoute(routeId: String): List<RideEntity>

    @Query("UPDATE rides SET ascentM = :ascentM WHERE id = :id")
    abstract suspend fun setAscent(id: String, ascentM: Double?)

    @Query("DELETE FROM rides WHERE id = :id")
    abstract suspend fun delete(id: String)

    /** Ends the ride and, when its route still exists, stamps the route as ridden. */
    @Transaction
    open suspend fun end(
        id: String,
        status: RideStatus,
        endedAt: Long,
        direction: Direction,
        distanceM: Double,
        movingTimeS: Long,
    ) {
        endRide(id, status, endedAt, direction, distanceM, movingTimeS)
        get(id)?.routeId?.let { markRidden(it, endedAt) }
    }

    @Query(
        """UPDATE rides SET status = :status, endedAt = :endedAt, direction = :direction,
               distanceM = :distanceM, movingTimeS = :movingTimeS WHERE id = :id""",
    )
    protected abstract suspend fun endRide(
        id: String,
        status: RideStatus,
        endedAt: Long,
        direction: Direction,
        distanceM: Double,
        movingTimeS: Long,
    )

    @Query("UPDATE routes SET lastRiddenAt = :at WHERE id = :routeId")
    protected abstract suspend fun markRidden(routeId: String, at: Long)
}
