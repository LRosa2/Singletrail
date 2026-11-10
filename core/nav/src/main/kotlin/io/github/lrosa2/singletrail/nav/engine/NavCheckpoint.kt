package io.github.lrosa2.singletrail.nav.engine

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What the ride service saves every few seconds so a killed or rebooted ride carries on (D28).
 * Only distances and counts: clock-based state (timers, the reverse prompt) does not survive a reboot.
 */
@Serializable
data class NavCheckpoint(
    val version: Int = VERSION,
    val phase: RidePhase,
    val reversed: Boolean,
    /** Where a loop was joined, on the unrotated route of the riding direction. */
    val loopStartM: Double? = null,
    val progressM: Double,
    val highWaterM: Double,
    val nextBarrier: Int = 0,
    val exitM: Double? = null,
    val offRouteDistanceM: Double = 0.0,
    val approached: List<Int> = emptyList(),
    val announced: List<Int> = emptyList(),
    val skipped: List<RouteRange> = emptyList(),
    val rideDistanceM: Double = 0.0,
    val movingSeconds: Double = 0.0,
    val reverseSuppressUntilM: Double = 0.0,
) {
    fun toJson(): String = NavJson.encodeToString(serializer(), this)

    companion object {
        const val VERSION = 1

        /** @throws IllegalArgumentException when the text is not a checkpoint. */
        fun fromJson(json: String): NavCheckpoint = NavJson.decodeFromString(serializer(), json)
    }
}

@OptIn(ExperimentalSerializationApi::class)
internal val NavJson = Json {
    classDiscriminator = "type"
    ignoreUnknownKeys = true
    explicitNulls = false
}
