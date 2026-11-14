package io.github.lrosa2.singletrail.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.lrosa2.singletrail.nav.TrackOutline

// Schema v3 (spec §8.2). Every change needs a migration tested against the exported schemas in
// app/schemas (v2 added routes.outline, v3 the collections tables).

/** Where a route came from. */
enum class SourceKind { SHARE, PICKER, FOLDER, URL, BACKUP }

/** Which way a route is ridden: as stored, or from the finish back to the start. */
enum class Direction { FORWARD, REVERSE }

enum class ManeuverKind { SLIGHT, TURN, SHARP, U_TURN, SWITCHBACKS }

enum class ManeuverSource { GEOMETRY, JUNCTION }

enum class StartMode { FROM_START, NEAREST_POINT }

enum class RideStatus { IN_PROGRESS, FINISHED, ABANDONED }

enum class WatchedFileStatus { IMPORTED, IGNORED, ERROR }

@Entity(
    tableName = "routes",
    indices = [Index(value = ["geometryHash"], unique = true), Index("geometryHashReversed")],
)
data class RouteEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String?,
    val sourceKind: SourceKind,
    /** File name or URL the route was imported from, for display only. */
    val sourceRef: String?,
    val geometryHash: String,
    val geometryHashReversed: String,
    /** Normalised GPX copy, relative to filesDir (`routes/<id>.gpx`). */
    val gpxPath: String,
    val pointCount: Int,
    val distanceM: Double,
    val ascentM: Double?,
    val descentM: Double?,
    val minEleM: Double?,
    val maxEleM: Double?,
    val hasElevation: Boolean,
    val startLat: Double,
    val startLon: Double,
    val endLat: Double,
    val endLon: Double,
    val minLat: Double,
    val minLon: Double,
    val maxLat: Double,
    val maxLon: Double,
    val isLoop: Boolean,
    /** Personal stars, 1–5; null when not rated. */
    val rating: Int?,
    val importedAt: Long,
    val updatedAt: Long,
    val lastRiddenAt: Long?,
    /** Version of the maneuver algorithm that produced the stored maneuvers; 0 = not computed yet. */
    val turnsAlgoVersion: Int,
    /** The track thinned for "near me" (§8.1); null until filled in for routes imported before v2. */
    val outline: TrackOutline?,
)

@Entity(
    tableName = "maneuvers",
    primaryKeys = ["routeId", "direction", "seq"],
    foreignKeys = [
        ForeignKey(entity = RouteEntity::class, parentColumns = ["id"], childColumns = ["routeId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class ManeuverEntity(
    val routeId: String,
    val direction: Direction,
    val seq: Int,
    val distanceAlongM: Double,
    val lat: Double,
    val lon: Double,
    val kind: ManeuverKind,
    /** Signed turn angle: positive to the right, negative to the left. */
    val angleDeg: Double,
    /** Number of turns grouped into this maneuver (switchbacks); 1 otherwise. */
    val groupCount: Int,
    val source: ManeuverSource,
)

@Entity(
    tableName = "waypoints",
    primaryKeys = ["routeId", "seq"],
    foreignKeys = [
        ForeignKey(entity = RouteEntity::class, parentColumns = ["id"], childColumns = ["routeId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class WaypointEntity(
    val routeId: String,
    val seq: Int,
    val name: String?,
    val description: String?,
    val lat: Double,
    val lon: Double,
    val ele: Double?,
    val symbol: String?,
)

@Entity(
    tableName = "rides",
    indices = [Index("routeId")],
    foreignKeys = [
        ForeignKey(entity = RouteEntity::class, parentColumns = ["id"], childColumns = ["routeId"], onDelete = ForeignKey.SET_NULL),
    ],
)
data class RideEntity(
    @PrimaryKey val id: String,
    val routeId: String?,
    /** Route name at ride time, kept when the route is deleted. */
    val routeNameSnapshot: String,
    val direction: Direction,
    val startMode: StartMode,
    val startedAt: Long,
    val endedAt: Long?,
    val status: RideStatus,
    val distanceM: Double,
    val movingTimeS: Long,
    val ascentM: Double?,
    /** Append-only track file, relative to filesDir (`rides/<id>.track`). */
    val trackPath: String,
    val segmentCount: Int,
)

@Entity(
    tableName = "watched_files",
    indices = [Index("routeId")],
    foreignKeys = [
        ForeignKey(entity = RouteEntity::class, parentColumns = ["id"], childColumns = ["routeId"], onDelete = ForeignKey.SET_NULL),
    ],
)
data class WatchedFileEntity(
    @PrimaryKey val uri: String,
    val lastHash: String?,
    val routeId: String?,
    val status: WatchedFileStatus,
    val lastSeenAt: Long,
)

/** Stops a route deleted by the user from coming back from the watched folder. */
@Entity(tableName = "route_tombstones")
data class RouteTombstoneEntity(
    @PrimaryKey val geometryHash: String,
    val deletedAt: Long,
)

@Entity(tableName = "map_files")
data class MapFileEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    /** Relative to the app's external files dir (`maps/<name>.map`). */
    val path: String,
    val sizeBytes: Long,
    val minLat: Double,
    val minLon: Double,
    val maxLat: Double,
    val maxLon: Double,
    val mapDate: Long?,
    val sourceUrl: String?,
    val installedAt: Long,
)

/** A named group of routes (spec §8.2, D48). The name is unique ignoring case (NOCASE column + unique index). */
@Entity(tableName = "collections", indices = [Index(value = ["name"], unique = true)])
data class CollectionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val name: String,
    val createdAt: Long,
)

/** A route in a collection; a route can be in several. Deleting either side removes only the row. */
@Entity(
    tableName = "route_collections",
    primaryKeys = ["routeId", "collectionId"],
    indices = [Index("collectionId")],
    foreignKeys = [
        ForeignKey(entity = RouteEntity::class, parentColumns = ["id"], childColumns = ["routeId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = CollectionEntity::class, parentColumns = ["id"], childColumns = ["collectionId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class RouteCollectionEntity(
    val routeId: String,
    val collectionId: String,
    val addedAt: Long,
)
