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
interface RideDao {
    @Query("SELECT COUNT(*) FROM rides WHERE routeId = :routeId")
    suspend fun countForRoute(routeId: String): Int
}
