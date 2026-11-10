package io.github.lrosa2.singletrail.nav

/** A route ready to ride in either direction: geometry and maneuvers for both (spec §5.3, §5.8). */
class NavRoute(
    val forward: PreparedRoute,
    val forwardManeuvers: List<Maneuver>,
    val reverse: PreparedRoute,
    val reverseManeuvers: List<Maneuver>,
    /** Used again when a loop is rotated to start where the rider joined it. */
    val maneuverConfig: ManeuverConfig = ManeuverConfig.DEFAULT,
) {
    init {
        require(forward.points.size == reverse.points.size) { "reverse must be forward reversed" }
    }

    companion object {
        /** Detects the maneuvers for both directions. */
        fun of(route: PreparedRoute, maneuverConfig: ManeuverConfig = ManeuverConfig.DEFAULT): NavRoute {
            val reverse = route.reversed()
            return NavRoute(
                route,
                ManeuverDetector.detect(route, maneuverConfig),
                reverse,
                ManeuverDetector.detect(reverse, maneuverConfig),
                maneuverConfig,
            )
        }
    }
}
