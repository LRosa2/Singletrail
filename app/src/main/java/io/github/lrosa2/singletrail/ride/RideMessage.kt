package io.github.lrosa2.singletrail.ride

import android.content.res.Resources
import io.github.lrosa2.singletrail.R
import io.github.lrosa2.singletrail.nav.Maneuver
import io.github.lrosa2.singletrail.nav.ManeuverKind
import io.github.lrosa2.singletrail.nav.engine.GpsStatus
import io.github.lrosa2.singletrail.nav.engine.NavState
import io.github.lrosa2.singletrail.nav.engine.RidePhase
import io.github.lrosa2.singletrail.ui.Formats
import kotlin.math.ceil
import kotlin.math.max

private const val NANOS = 1_000_000_000L

/** The one line that matters right now, for the ride banner and the notification (spec §4.2, §4.6). */
sealed interface RideMessage {
    data class WaitingForGps(val satellites: Int?) : RideMessage
    data class FindingRoute(val distanceM: Double?) : RideMessage
    data class Next(val maneuver: Maneuver, val distanceM: Double) : RideMessage
    data class FollowRoute(val distanceLeftM: Double) : RideMessage
    data class OffRoute(val distanceM: Double?) : RideMessage
    data class GpsLost(val secondsSinceFix: Long) : RideMessage
    data class ReversePrompt(val secondsLeft: Long) : RideMessage
    data object Finished : RideMessage

    companion object {
        fun of(state: NavState, nowNanos: Long, satellitesInView: Int?): RideMessage {
            val lastFix = state.lastFixNanos
            return when {
                state.phase == RidePhase.FINISHED -> Finished
                state.gps == GpsStatus.LOST && lastFix != null -> GpsLost((nowNanos - lastFix) / NANOS)
                state.phase == RidePhase.REVERSE_PENDING ->
                    ReversePrompt(max(0L, ceil(((state.reverseDeadlineNanos ?: nowNanos) - nowNanos).toDouble() / NANOS).toLong()))
                state.phase == RidePhase.OFF_ROUTE -> OffRoute(state.distanceToRouteM)
                state.phase == RidePhase.WAITING_GPS -> WaitingForGps(satellitesInView ?: state.satellites)
                state.phase == RidePhase.ACQUIRING -> FindingRoute(state.target?.distanceM)
                else -> state.next?.let { Next(it.maneuver, it.distanceM) } ?: FollowRoute(state.distanceLeftM)
            }
        }
    }
}

/** Words for maneuvers and [RideMessage]s. */
object RideTexts {
    fun maneuver(res: Resources, m: Maneuver): String {
        val right = m.isRight
        return when (m.kind) {
            ManeuverKind.SLIGHT -> res.getString(if (right) R.string.maneuver_slight_right else R.string.maneuver_slight_left)
            ManeuverKind.TURN -> res.getString(if (right) R.string.maneuver_right else R.string.maneuver_left)
            ManeuverKind.SHARP -> res.getString(if (right) R.string.maneuver_sharp_right else R.string.maneuver_sharp_left)
            ManeuverKind.U_TURN -> res.getString(if (right) R.string.maneuver_u_turn_right else R.string.maneuver_u_turn_left)
            ManeuverKind.SWITCHBACKS -> res.getQuantityString(R.plurals.maneuver_switchbacks, m.count, m.count)
        }
    }

    /** A small arrow glyph for a maneuver, for the banner and the notification. */
    fun glyph(m: Maneuver): String = when (m.kind) {
        ManeuverKind.SLIGHT -> if (m.isRight) "↗" else "↖"
        ManeuverKind.TURN -> if (m.isRight) "↱" else "↰"
        ManeuverKind.SHARP -> if (m.isRight) "↘" else "↙"
        ManeuverKind.U_TURN -> if (m.isRight) "↷" else "↶"
        ManeuverKind.SWITCHBACKS -> "⇅"
    }

    fun message(res: Resources, message: RideMessage): String = when (message) {
        is RideMessage.WaitingForGps -> message.satellites?.let { res.getQuantityString(R.plurals.ride_waiting_gps_satellites, it, it) }
            ?: res.getString(R.string.ride_waiting_gps)
        is RideMessage.FindingRoute -> message.distanceM?.let { res.getString(R.string.ride_route_away, Formats.distance(it)) }
            ?: res.getString(R.string.ride_finding_route)
        is RideMessage.Next -> res.getString(
            R.string.ride_next,
            glyph(message.maneuver),
            maneuver(res, message.maneuver),
            Formats.distance(roundForDisplay(message.distanceM)),
        )
        is RideMessage.FollowRoute -> res.getString(R.string.ride_follow, Formats.distance(message.distanceLeftM))
        is RideMessage.OffRoute -> message.distanceM?.let { res.getString(R.string.ride_off_route, Formats.distance(it)) }
            ?: res.getString(R.string.ride_off_route_unknown)
        is RideMessage.GpsLost -> res.getString(R.string.ride_gps_lost, message.secondsSinceFix)
        is RideMessage.ReversePrompt -> res.getString(R.string.ride_reverse_prompt, message.secondsLeft)
        RideMessage.Finished -> res.getString(R.string.ride_finished)
    }

    /** Distances to a turn in steps that don't flicker every metre: 10 m below 1 km, 100 m above. */
    fun roundForDisplay(meters: Double): Double = when {
        meters < 1_000 -> Math.round(meters / 10) * 10.0
        else -> Math.round(meters / 100) * 100.0
    }
}
