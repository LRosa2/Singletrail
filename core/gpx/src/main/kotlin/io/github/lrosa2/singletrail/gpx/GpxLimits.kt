package io.github.lrosa2.singletrail.gpx

/** Hard limits for untrusted GPX input (spec §9, S2). The reader itself arrives in milestone M1. */
object GpxLimits {
    const val MAX_FILE_BYTES: Long = 20L * 1024 * 1024
    const val MAX_POINTS: Int = 200_000
    const val WARN_POINTS: Int = 50_000
    const val MAX_WAYPOINTS: Int = 2_000
    const val MAX_TRACKS: Int = 10
}
