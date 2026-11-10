package io.github.lrosa2.singletrail.nav

/** Version of [ManeuverDetector]; maneuvers stored by an older version are recomputed (spec §5.3). */
const val MANEUVER_ALGO_VERSION = 1

enum class ManeuverKind { SLIGHT, TURN, SHARP, U_TURN, SWITCHBACKS }

/**
 * A point on the route where the rider has to change direction.
 *
 * For [ManeuverKind.SWITCHBACKS], [parts] holds the grouped turns in order; [s], [lat], [lon] and
 * [angleDeg] are those of the first turn, which is where the single *approaching* alert points.
 */
data class Maneuver(
    /** Distance along the route, in metres. */
    val s: Double,
    val lat: Double,
    val lon: Double,
    val kind: ManeuverKind,
    /** Signed direction change in degrees: positive to the right, negative to the left. */
    val angleDeg: Double,
    val parts: List<Maneuver> = emptyList(),
) {
    /** Number of turns: the size of a switchback group, otherwise 1. */
    val count: Int get() = if (parts.isEmpty()) 1 else parts.size

    /** Where the maneuver ends: the last turn of a switchback group, otherwise [s]. */
    val endS: Double get() = parts.lastOrNull()?.s ?: s

    val isRight: Boolean get() = angleDeg > 0
}

/** Turn detection thresholds (spec §5.3), tuned later with replayed rides. */
data class ManeuverConfig(
    /** Length of the incoming and outgoing arms whose bearings are compared at each point. */
    val armMeters: Double = 20.0,
    /** Smallest direction change that counts; also the lower bound of [ManeuverKind.SLIGHT]. */
    val slightDeg: Double = 40.0,
    /** Lower bound of [ManeuverKind.TURN]. */
    val turnDeg: Double = 60.0,
    /** Lower bound of [ManeuverKind.SHARP]. */
    val sharpDeg: Double = 120.0,
    /** Turns sharper than this are [ManeuverKind.U_TURN]. */
    val uTurnDeg: Double = 165.0,
    /** A turn's angle is measured out to where the change across the arms drops below this. */
    val edgeDeg: Double = 10.0,
    /** Turns in the same direction whose peaks are this close are one turn (non-maximum suppression). */
    val mergeMeters: Double = 30.0,
    /** Fewest alternating sharp turns or U-turns that form a switchback group. */
    val switchbackMinTurns: Int = 3,
    /** Longest distance from the first to the last turn of a switchback group. */
    val switchbackMaxSpanMeters: Double = 250.0,
) {
    init {
        require(armMeters > 0) { "armMeters must be > 0" }
        require(slightDeg in 0.0..turnDeg && turnDeg <= sharpDeg && sharpDeg <= uTurnDeg && uTurnDeg <= 180.0) {
            "Turn class bounds must rise from slightDeg to uTurnDeg <= 180"
        }
        require(edgeDeg in 0.0..slightDeg) { "edgeDeg must be between 0 and slightDeg" }
        require(mergeMeters >= 0) { "mergeMeters must be >= 0" }
        require(switchbackMinTurns >= 2) { "switchbackMinTurns must be >= 2" }
        require(switchbackMaxSpanMeters >= 0) { "switchbackMaxSpanMeters must be >= 0" }
    }

    companion object {
        val DEFAULT = ManeuverConfig()
    }
}
