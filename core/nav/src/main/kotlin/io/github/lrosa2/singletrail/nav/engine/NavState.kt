package io.github.lrosa2.singletrail.nav.engine

import io.github.lrosa2.singletrail.nav.Maneuver
import kotlinx.serialization.Serializable

/** Ride states (spec §5.6). "No GPS" is not a phase but [GpsStatus.LOST]: the phase underneath is kept. */
enum class RidePhase { WAITING_GPS, ACQUIRING, ON_ROUTE, OFF_ROUTE, REVERSE_PENDING, FINISHED }

enum class GpsStatus {
    /** No fix yet (also right after restoring a checkpoint). */
    WAITING,
    OK,

    /** The latest fix is less accurate than [NavConfig.maxAccuracyMeters]: alerts and off-route are paused. */
    POOR,

    /** No fix for [NavConfig.noGpsSeconds]: grey arrow, alerts paused. */
    LOST,
}

enum class HeadingSource {
    /** GPS course while riding. */
    GPS,

    /** Rotation-vector compass when slow or stopped (shown with a compass marker). */
    COMPASS,

    /** Nothing current: the last known heading, greyed out. */
    FROZEN,
}

data class Heading(val degrees: Double, val source: HeadingSource, val needsCalibration: Boolean = false)

enum class TargetKind {
    /** On route: a point 30–60 m ahead. */
    LOOK_AHEAD,

    /** Acquiring: the nearest point of the route. */
    NEAREST_POINT,

    /** Off route after a short detour: where the rider left the route. */
    EXIT_POINT,

    /** Off route: the nearest route point ahead of the exit point. */
    AHEAD,
}

/** Where the arrow points: a route point [s] metres along the current route. */
data class NavTarget(
    val kind: TargetKind,
    val s: Double,
    val lat: Double,
    val lon: Double,
    val distanceM: Double,
    /** Bearing from the rider to the target, degrees clockwise from north. */
    val bearingDeg: Double,
)

data class UpcomingManeuver(
    /** Index into [Navigator.maneuvers]. */
    val index: Int,
    val maneuver: Maneuver,
    /** Distance from the rider's progress to the maneuver; 0 inside a switchback group. */
    val distanceM: Double,
)

/** A stretch of the current route, from [from] to [to] metres. */
@Serializable
data class RouteRange(val from: Double, val to: Double)

data class NavState(
    val phase: RidePhase,
    val gps: GpsStatus,
    val satellites: Int?,
    val lastFixNanos: Long?,
    /** Riding the route from its finish to its start. */
    val reversed: Boolean,
    /** Metres along the current route (see [Navigator.route]). */
    val progressM: Double,
    val lengthM: Double,
    val distanceLeftM: Double,
    /** Smoothed climb from the rider's progress to the finish; null without elevation data. */
    val climbLeftM: Double?,
    /** Distance from the latest good fix to the route; null before the first one. */
    val distanceToRouteM: Double?,
    val next: UpcomingManeuver?,
    /** The maneuver after [next], when it follows closely ("then ↱ 40 m"). */
    val then: UpcomingManeuver?,
    val target: NavTarget?,
    val heading: Heading?,
    /** Target bearing relative to [heading]: 0 straight ahead, positive to the right. */
    val arrowDeg: Double?,
    /** When the reverse prompt accepts itself, on the fix clock. */
    val reverseDeadlineNanos: Long?,
    /** Parts of the route left out by rejoining further on, on the current route. */
    val skipped: List<RouteRange>,
    val rideDistanceM: Double,
    val movingSeconds: Double,
)

/** Things that happened on one input; the ride service turns these into tones (spec §4.3). */
sealed interface NavEvent {
    /** First match with the route (no tone). */
    data object Joined : NavEvent

    data class TurnApproaching(val index: Int, val maneuver: Maneuver) : NavEvent

    data class TurnNow(val index: Int, val maneuver: Maneuver) : NavEvent

    data object OffRoute : NavEvent

    data object BackOnRoute : NavEvent

    data object SignalLost : NavEvent

    data object SignalBack : NavEvent

    /** "Riding reversed? Switching in 10 s" with Undo. */
    data object ReversePrompt : NavEvent

    data object Reversed : NavEvent

    data object ReverseUndone : NavEvent

    data object Finished : NavEvent
}

data class NavUpdate(val state: NavState, val events: List<NavEvent>)
