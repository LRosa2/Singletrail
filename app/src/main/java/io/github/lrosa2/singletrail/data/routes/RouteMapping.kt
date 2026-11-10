package io.github.lrosa2.singletrail.data.routes

import io.github.lrosa2.singletrail.data.db.RouteEntity
import io.github.lrosa2.singletrail.data.db.WaypointEntity
import io.github.lrosa2.singletrail.gpx.GpxTrack
import io.github.lrosa2.singletrail.importing.NewRoute
import io.github.lrosa2.singletrail.nav.RoutePoint

/** The track's points as navigation input. */
fun GpxTrack.routePoints(): List<RoutePoint> = points.map { RoutePoint(it.lat, it.lon, it.elevation) }

fun NewRoute.waypointRows(routeId: String): List<WaypointEntity> =
    waypoints.mapIndexed { i, w -> WaypointEntity(routeId, i, w.name, w.description, w.lat, w.lon, w.elevation, w.symbol) }

/** The library row for a newly imported route; stats come from the prepared route. */
fun NewRoute.toEntity(id: String, gpxPath: String, now: Long): RouteEntity {
    val points = prepared.points
    val profile = prepared.elevation
    return RouteEntity(
        id = id,
        name = name,
        description = description,
        sourceKind = source,
        sourceRef = sourceRef,
        geometryHash = hashes.forward,
        geometryHashReversed = hashes.reversed,
        gpxPath = gpxPath,
        pointCount = track.points.size,
        distanceM = prepared.lengthMeters,
        ascentM = profile?.totalAscent,
        descentM = profile?.totalDescent,
        minEleM = profile?.minElevation,
        maxEleM = profile?.maxElevation,
        hasElevation = profile != null,
        startLat = points.first().lat,
        startLon = points.first().lon,
        endLat = points.last().lat,
        endLon = points.last().lon,
        minLat = points.minOf { it.lat },
        minLon = points.minOf { it.lon },
        maxLat = points.maxOf { it.lat },
        maxLon = points.maxOf { it.lon },
        isLoop = prepared.isLoop,
        rating = null,
        importedAt = now,
        updatedAt = now,
        lastRiddenAt = null,
        turnsAlgoVersion = 0,
    )
}

/**
 * [existing] with this route's geometry and stats ("Update existing route", spec §8.4). The local
 * name, description, stars and history win; maneuvers are marked for recomputation.
 */
fun NewRoute.replacing(existing: RouteEntity, now: Long): RouteEntity =
    toEntity(existing.id, existing.gpxPath, now).copy(
        name = existing.name,
        description = existing.description,
        rating = existing.rating,
        importedAt = existing.importedAt,
        lastRiddenAt = existing.lastRiddenAt,
    )
