package io.github.lrosa2.singletrail.backup

import io.github.lrosa2.singletrail.data.settings.RideSettings
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Zips made by Singletrail (spec §8.5). A full backup holds `manifest.json`, `routes.json`,
 * `rides.json`, `settings.json`, and a GPX per route under `routes/` and per ride under `rides/`;
 * a routes zip (sharing several routes) holds `manifest.json` and one GPX per route.
 */
@Serializable
data class BackupManifest(
    val format: Int = FORMAT,
    val kind: String,
    val app: String,
    val createdAt: Long,
    /** Random id of the installation that made a full backup, to tell "mine" from someone else's. */
    val owner: String? = null,
    val routes: Int = 0,
    val rides: Int = 0,
) {
    companion object {
        const val FORMAT = 1
        const val KIND_FULL = "full-backup"
        const val KIND_ROUTES = "routes"
    }
}

@Serializable
data class BackupRoute(
    val id: String,
    val name: String,
    val description: String? = null,
    val rating: Int? = null,
    val sourceKind: String? = null,
    val sourceRef: String? = null,
    val importedAt: Long,
    val updatedAt: Long? = null,
    val lastRiddenAt: Long? = null,
    val geometryHash: String,
    /** Entry path of the route's GPX in the zip. */
    val file: String,
)

@Serializable
data class BackupRide(
    val id: String,
    val routeId: String? = null,
    val routeGeometryHash: String? = null,
    val routeName: String,
    val direction: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val status: String,
    val distanceM: Double,
    val movingTimeS: Long,
    val ascentM: Double? = null,
    val segmentCount: Int,
    /** Entry path of the ride's track as GPX in the zip. */
    val file: String,
)

@Serializable
data class BackupSettings(
    val offRouteMeters: Int,
    val beepVolume: Float,
    val compassOff: Boolean,
    val debugTrace: Boolean,
    /** Added with the ride screen (M8); absent in older backups, which then keep the phone's own value. */
    val dimBetweenTurns: Boolean? = null,
    val dimLevel: Float? = null,
    val lowPowerAt: Int? = null,
) {
    fun applyTo(current: RideSettings): RideSettings = current.copy(
        offRouteMeters = offRouteMeters,
        beepVolume = beepVolume,
        compassOff = compassOff,
        debugTrace = debugTrace,
        dimBetweenTurns = dimBetweenTurns ?: current.dimBetweenTurns,
        dimLevel = dimLevel ?: current.dimLevel,
        lowPowerAt = lowPowerAt ?: current.lowPowerAt,
    )

    companion object {
        fun of(settings: RideSettings) = BackupSettings(
            offRouteMeters = settings.offRouteMeters,
            beepVolume = settings.beepVolume,
            compassOff = settings.compassOff,
            debugTrace = settings.debugTrace,
            dimBetweenTurns = settings.dimBetweenTurns,
            dimLevel = settings.dimLevel,
            lowPowerAt = settings.lowPowerAt,
        )
    }
}

enum class BackupKind { FULL, ROUTES }

internal val BackupJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
