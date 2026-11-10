package io.github.lrosa2.singletrail.nav.engine

/**
 * One GPS fix (spec §5.1). [elapsedNanos] is the fix's `elapsedRealtimeNanos`, so fix age is the
 * difference to the current elapsed-realtime clock.
 */
data class Fix(
    val elapsedNanos: Long,
    val lat: Double,
    val lon: Double,
    val accuracyM: Float? = null,
    val speedMps: Float? = null,
    val bearingDeg: Float? = null,
    val bearingAccDeg: Float? = null,
    val altitudeM: Double? = null,
    val satellites: Int? = null,
)

/** A rotation-vector compass reading, already remapped for a phone upright on the bars. */
data class HeadingSample(val elapsedNanos: Long, val azimuthDeg: Float, val reliable: Boolean)

/** How the ride starts; starting from the nearest route point is always on (spec §5.8). */
data class RideMode(val reversed: Boolean = false)

internal const val NANOS_PER_SECOND = 1_000_000_000L

internal fun Long.secondsSince(earlier: Long): Double = (this - earlier).toDouble() / NANOS_PER_SECOND
