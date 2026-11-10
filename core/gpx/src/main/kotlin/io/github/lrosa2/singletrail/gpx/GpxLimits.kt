package io.github.lrosa2.singletrail.gpx

/** Hard limits for untrusted GPX input (spec §9, S2). Files beyond them are rejected, not truncated. */
data class GpxLimits(
    val maxFileBytes: Long = 20L * 1024 * 1024,
    val maxPoints: Int = 200_000,
    /** Above this many points the import UI warns (spec §5.2); the reader itself does not. */
    val warnPoints: Int = 50_000,
    val maxWaypoints: Int = 2_000,
    /** Tracks and routes together. */
    val maxTracks: Int = 10,
) {
    companion object {
        val DEFAULT = GpxLimits()
    }
}
