package io.github.lrosa2.singletrail.gpx

data class GpxPoint(
    val lat: Double,
    val lon: Double,
    val elevation: Double? = null,
    val timeEpochMs: Long? = null,
)

data class GpxWaypoint(
    val lat: Double,
    val lon: Double,
    val elevation: Double? = null,
    val name: String? = null,
    val description: String? = null,
    val symbol: String? = null,
)

/** TRACK = recorded or drawn line (`trk`); ROUTE = planned route points (`rte`). Both are followed the same way. */
enum class GpxTrackKind { TRACK, ROUTE }

data class GpxTrack(
    val name: String?,
    val description: String?,
    val kind: GpxTrackKind,
    /** Never empty; each segment has at least one point. */
    val segments: List<List<GpxPoint>>,
) {
    val points: List<GpxPoint> get() = segments.flatten()
}

data class GpxDocument(
    val name: String?,
    val description: String?,
    val creator: String?,
    /** Tracks and routes in file order. Tracks without points are dropped. */
    val tracks: List<GpxTrack>,
    val waypoints: List<GpxWaypoint>,
    /** Points dropped because their coordinates were missing or invalid. */
    val skippedPoints: Int = 0,
) {
    /** Name to show for the file: its own name, else the first track's. */
    val displayName: String? get() = name ?: tracks.firstOrNull()?.name

    /** All tracks and routes as one track, in file order (the "join" choice for multi-track files). */
    fun joinedTrack(): GpxTrack? {
        if (tracks.isEmpty()) return null
        return GpxTrack(
            name = displayName,
            description = description ?: tracks.first().description,
            kind = if (tracks.all { it.kind == GpxTrackKind.ROUTE }) GpxTrackKind.ROUTE else GpxTrackKind.TRACK,
            segments = tracks.flatMap { it.segments },
        )
    }
}
