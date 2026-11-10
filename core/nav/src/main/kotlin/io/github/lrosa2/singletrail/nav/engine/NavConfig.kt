package io.github.lrosa2.singletrail.nav.engine

import io.github.lrosa2.singletrail.nav.ManeuverKind

/** Navigation engine thresholds (spec §5.5–§5.10). Field tuning (M10) adjusts these from replayed rides. */
data class NavConfig(
    // Off route (§5.6)
    /** Off-route threshold T; the user can set 15–80 m. */
    val offRouteMeters: Double = 30.0,
    /** Back on route needs the rider within T minus this. */
    val backOnRouteMarginMeters: Double = 10.0,
    /** Off route needs this many good fixes in a row beyond T... */
    val offRouteMinFixes: Int = 3,
    /** ...spanning at least this long. */
    val offRouteMinSeconds: Double = 5.0,
    /** Back on route needs this many good fixes in a row within T minus the margin. */
    val backOnRouteFixes: Int = 2,
    /** Rejoining needs the rider's course within this of the route, when moving fast enough to tell. */
    val rejoinMaxCourseDeg: Double = 60.0,

    // Matching (§5.5)
    /** Fixes less accurate than this only update the GPS indicators. */
    val maxAccuracyMeters: Double = 25.0,
    val windowBehindMeters: Double = 150.0,
    val windowAheadMinMeters: Double = 400.0,
    val windowAheadSeconds: Double = 60.0,
    /** Progress may move back by this much (GPS jitter); otherwise it only moves forward. */
    val jitterBackMeters: Double = 30.0,
    /** Extra cost, in metres, of a route segment pointing opposite to the rider's course. */
    val headingPenaltyMeters: Double = 25.0,
    /** Extra cost per metre of progress jump beyond [jumpSlackMeters] plus the distance ridden. */
    val jumpPenaltyPerMeter: Double = 0.1,
    val jumpSlackMeters: Double = 30.0,

    // Heading (§5.9)
    /** GPS course is trusted from this speed (6 km/h)... */
    val minCourseSpeedMps: Double = 6.0 / 3.6,
    /** ...when its bearing accuracy is no worse than this. */
    val maxCourseAccuracyDeg: Double = 30.0,
    /** Compass samples older than this are ignored. */
    val compassMaxAgeSeconds: Double = 2.0,
    /** "Compass off" setting for magnetic mounts: when slow, freeze the last heading instead. */
    val compassOff: Boolean = false,

    // GPS lost, finish (§5.6)
    val noGpsSeconds: Double = 10.0,
    val finishRadiusMeters: Double = 30.0,
    val finishMinFraction: Double = 0.9,

    // Reverse (§5.8)
    val reverseCourseDeg: Double = 135.0,
    val reverseMinMeters: Double = 100.0,
    val reverseMinSpeedMps: Double = 2.0,
    val reversePromptSeconds: Double = 10.0,
    val reverseUndoSuppressMeters: Double = 500.0,

    // Return target (§5.7, D29)
    val shortDetourMeters: Double = 300.0,
    /** A point ahead replaces the exit point only when it is at most this fraction of the exit point's distance... */
    val aheadMaxFraction: Double = 0.5,
    /** ...and the rider has been getting closer to it for this long. */
    val aheadApproachSeconds: Double = 20.0,
    val longDetourWindowMeters: Double = 2000.0,
    /** Turns that progress and return targets never skip until ridden (switchbacks count turn by turn). */
    val barrierKinds: Set<ManeuverKind> = setOf(ManeuverKind.SHARP, ManeuverKind.U_TURN),
    /** Matching may run this far past an unridden barrier, so the rider can ride through it. */
    val barrierPassMeters: Double = 20.0,
    /** A barrier counts as ridden once progress is this far past it with the course along the route. */
    val barrierRiddenMeters: Double = 10.0,

    // Alerts (§5.10, D34)
    val approachMinMeters: Double = 80.0,
    val approachSeconds: Double = 12.0,
    val nowMinMeters: Double = 15.0,
    val nowSeconds: Double = 3.0,
    val speedWindowSeconds: Double = 5.0,
    /** Slight bends show an icon only unless this is on. */
    val beepForSlight: Boolean = false,
    /** Position is extrapolated along the route by fix age up to this long. */
    val maxExtrapolationSeconds: Double = 5.0,

    // Display
    val lookAheadMinMeters: Double = 30.0,
    val lookAheadMaxMeters: Double = 60.0,
    val lookAheadSeconds: Double = 6.0,
    /** The banner's "then" line shows the following maneuver when it is this close to the next one. */
    val thenMaxGapMeters: Double = 100.0,
    /** Moving time and ride distance count fixes at or above this speed. */
    val movingMinSpeedMps: Double = 1.0,
) {
    init {
        require(offRouteMeters in 15.0..80.0) { "offRouteMeters must be 15-80 m" }
        require(backOnRouteMarginMeters in 0.0..<offRouteMeters) { "backOnRouteMarginMeters must be below offRouteMeters" }
        require(offRouteMinFixes >= 1 && backOnRouteFixes >= 1) { "Fix counts must be >= 1" }
        require(aheadMaxFraction in 0.0..1.0) { "aheadMaxFraction must be 0-1" }
        require(finishMinFraction in 0.0..1.0) { "finishMinFraction must be 0-1" }
        require(lookAheadMinMeters <= lookAheadMaxMeters) { "lookAheadMinMeters must be <= lookAheadMaxMeters" }
    }

    companion object {
        val DEFAULT = NavConfig()
    }
}
