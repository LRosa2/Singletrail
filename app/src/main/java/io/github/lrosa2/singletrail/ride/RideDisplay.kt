package io.github.lrosa2.singletrail.ride

import android.view.KeyEvent
import io.github.lrosa2.singletrail.nav.engine.GpsStatus
import io.github.lrosa2.singletrail.nav.engine.HeadingSource
import io.github.lrosa2.singletrail.nav.engine.NavState
import io.github.lrosa2.singletrail.nav.engine.RidePhase
import kotlin.math.max

/**
 * The dimming policy (spec §4.4, D33): the screen dims between turns and comes back to full about
 * 15 s before a maneuver, and whenever something needs the rider's attention.
 */
object ScreenPolicy {
    const val LOW_POWER_BRIGHT = 0.5f
    private const val TURN_SOON_SECONDS = 15.0
    private const val TURN_SOON_MIN_METERS = 50.0
    private const val AFTER_TOUCH_SECONDS = 10.0

    /** Window brightness 0–1, or null for the phone's own brightness. */
    fun brightness(state: NavState, secondsSinceTouch: Double, dimBetweenTurns: Boolean, lowPower: Boolean, dimLevel: Float): Float? {
        val attention = needsAttention(state, secondsSinceTouch)
        return when {
            lowPower -> if (attention) LOW_POWER_BRIGHT else (dimLevel / 2).coerceAtLeast(0.01f)
            !dimBetweenTurns || attention -> null
            else -> dimLevel
        }
    }

    private fun needsAttention(state: NavState, secondsSinceTouch: Double): Boolean {
        if (secondsSinceTouch < AFTER_TOUCH_SECONDS) return true
        if (state.gps == GpsStatus.LOST) return true
        if (state.phase != RidePhase.ON_ROUTE) return true
        val next = state.next ?: return false
        return next.distanceM <= max(TURN_SOON_MIN_METERS, TURN_SOON_SECONDS * state.speedMps)
    }
}

enum class BatteryAdvice {
    NONE,

    /** 10 points above the low-power threshold (30% by default): a warning (spec §4.4). */
    LOW,

    /** At the low-power threshold (20% by default): offer low-power mode (arrow view only, no map, dimmer screen). */
    VERY_LOW,
}

object BatteryPolicy {
    const val DEFAULT_LOW_POWER_AT = 20
    private const val WARNING_POINTS = 10

    fun advice(percent: Int?, charging: Boolean, lowPowerAt: Int = DEFAULT_LOW_POWER_AT): BatteryAdvice = when {
        percent == null || charging -> BatteryAdvice.NONE
        percent <= lowPowerAt -> BatteryAdvice.VERY_LOW
        percent <= lowPowerAt + WARNING_POINTS -> BatteryAdvice.LOW
        else -> BatteryAdvice.NONE
    }
}

/** Volume keys as accept/undo for the reverse prompt while the app is in front (spec §4.5, D35). */
enum class PromptKey {
    ACCEPT,
    UNDO,
    ;

    companion object {
        /** The prompt answer for a key, or null to let the key do what it normally does. */
        fun of(keyCode: Int, phase: RidePhase): PromptKey? {
            if (phase != RidePhase.REVERSE_PENDING) return null
            return when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> ACCEPT
                KeyEvent.KEYCODE_VOLUME_DOWN -> UNDO
                else -> null
            }
        }
    }
}

enum class ArrowTone { NORMAL, WARNING, INACTIVE }

/** How the big arrow is drawn (spec §4.2). */
data class ArrowLook(val tone: ArrowTone, val compass: Boolean) {
    companion object {
        /** Null when there's nothing to point at: waiting for GPS, finished, or no heading yet. */
        fun of(state: NavState): ArrowLook? {
            if (state.phase == RidePhase.WAITING_GPS || state.phase == RidePhase.FINISHED) return null
            if (state.arrowDeg == null) return null
            val source = state.heading?.source
            val tone = when {
                // Stale data must not look trustworthy (D31): grey when GPS is lost or the heading is frozen.
                state.gps == GpsStatus.LOST || source == HeadingSource.FROZEN -> ArrowTone.INACTIVE
                state.phase == RidePhase.OFF_ROUTE -> ArrowTone.WARNING
                else -> ArrowTone.NORMAL
            }
            return ArrowLook(tone, compass = source == HeadingSource.COMPASS)
        }
    }
}
