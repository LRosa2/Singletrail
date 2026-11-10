package io.github.lrosa2.singletrail.nav.engine

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One line of a debug trace (spec §11): the engine's inputs, so a ride can be replayed in a unit
 * test, and its events, so the replay can be checked against what happened on the bike. Traces hold
 * raw positions: opt-in per ride, exported only by the user.
 */
@Serializable
sealed interface TraceRecord {

    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    @SerialName("header")
    data class Header(
        @EncodeDefault val version: Int = NavTrace.VERSION,
        val reversed: Boolean = false,
        val routeHash: String? = null,
        val note: String? = null,
    ) : TraceRecord

    /** A fix; [now] is when it was processed, when that differs from its own time [t]. */
    @Serializable
    @SerialName("fix")
    data class FixIn(
        val t: Long,
        val lat: Double,
        val lon: Double,
        val acc: Float? = null,
        val spd: Float? = null,
        val brg: Float? = null,
        val brgAcc: Float? = null,
        val alt: Double? = null,
        val sats: Int? = null,
        val now: Long? = null,
    ) : TraceRecord {
        constructor(fix: Fix, now: Long? = null) : this(
            fix.elapsedNanos, fix.lat, fix.lon, fix.accuracyM, fix.speedMps, fix.bearingDeg,
            fix.bearingAccDeg, fix.altitudeM, fix.satellites, now,
        )

        fun toFix() = Fix(t, lat, lon, acc, spd, brg, brgAcc, alt, sats)
    }

    @Serializable
    @SerialName("heading")
    data class HeadingIn(val t: Long, val az: Float, val reliable: Boolean) : TraceRecord

    @Serializable
    @SerialName("tick")
    data class Tick(val t: Long) : TraceRecord

    enum class ActionKind { ACCEPT_REVERSE, UNDO_REVERSE }

    @Serializable
    @SerialName("action")
    data class Action(val t: Long, val action: ActionKind) : TraceRecord

    /** An engine event, with the maneuver index for turn events and progress and phase after it. */
    @Serializable
    @SerialName("event")
    data class Event(
        val t: Long,
        val name: String,
        val maneuver: Int? = null,
        val s: Double? = null,
        val phase: RidePhase? = null,
    ) : TraceRecord
}

object NavTrace {
    const val VERSION = 1

    fun encode(record: TraceRecord): String = NavJson.encodeToString(TraceRecord.serializer(), record)

    /** Null for a blank line. @throws IllegalArgumentException for a malformed one. */
    fun decode(line: String): TraceRecord? =
        if (line.isBlank()) null else NavJson.decodeFromString(TraceRecord.serializer(), line)

    /** Event records for an update, stamped [t]. */
    fun events(t: Long, update: NavUpdate): List<TraceRecord.Event> = update.events.map { e ->
        TraceRecord.Event(t, e.traceName, e.maneuverIndex, update.state.progressM, update.state.phase)
    }

    /** Feeds a trace's inputs to [navigator] and returns the events it produces, as recorded. */
    fun replay(navigator: Navigator, records: Sequence<TraceRecord>): List<TraceRecord.Event> {
        val out = ArrayList<TraceRecord.Event>()
        for (r in records) {
            when (r) {
                is TraceRecord.FixIn -> {
                    val now = r.now ?: r.t
                    out += events(now, navigator.onFix(r.toFix(), now))
                }
                is TraceRecord.HeadingIn -> out += events(r.t, navigator.onHeading(HeadingSample(r.t, r.az, r.reliable)))
                is TraceRecord.Tick -> out += events(r.t, navigator.onTick(r.t))
                is TraceRecord.Action -> out += events(
                    r.t,
                    when (r.action) {
                        TraceRecord.ActionKind.ACCEPT_REVERSE -> navigator.acceptReverse(r.t)
                        TraceRecord.ActionKind.UNDO_REVERSE -> navigator.undoReverse(r.t)
                    },
                )
                is TraceRecord.Header, is TraceRecord.Event -> Unit
            }
        }
        return out
    }
}

/** Feeds a [Navigator] and writes every input and event to [sink] as trace lines. */
class TraceRecorder(val navigator: Navigator, private val sink: (String) -> Unit) {

    fun start(reversed: Boolean, routeHash: String?, note: String? = null) =
        write(TraceRecord.Header(reversed = reversed, routeHash = routeHash, note = note))

    fun onFix(fix: Fix, nowNanos: Long = fix.elapsedNanos): NavUpdate {
        write(TraceRecord.FixIn(fix, nowNanos.takeIf { it != fix.elapsedNanos }))
        return navigator.onFix(fix, nowNanos).also { log(nowNanos, it) }
    }

    fun onHeading(sample: HeadingSample): NavUpdate {
        write(TraceRecord.HeadingIn(sample.elapsedNanos, sample.azimuthDeg, sample.reliable))
        return navigator.onHeading(sample).also { log(sample.elapsedNanos, it) }
    }

    fun onTick(nowNanos: Long): NavUpdate {
        write(TraceRecord.Tick(nowNanos))
        return navigator.onTick(nowNanos).also { log(nowNanos, it) }
    }

    fun acceptReverse(nowNanos: Long): NavUpdate {
        write(TraceRecord.Action(nowNanos, TraceRecord.ActionKind.ACCEPT_REVERSE))
        return navigator.acceptReverse(nowNanos).also { log(nowNanos, it) }
    }

    fun undoReverse(nowNanos: Long): NavUpdate {
        write(TraceRecord.Action(nowNanos, TraceRecord.ActionKind.UNDO_REVERSE))
        return navigator.undoReverse(nowNanos).also { log(nowNanos, it) }
    }

    private fun log(t: Long, update: NavUpdate) = NavTrace.events(t, update).forEach(::write)

    private fun write(record: TraceRecord) = sink(NavTrace.encode(record))
}

private val NavEvent.traceName: String
    get() = when (this) {
        NavEvent.Joined -> "JOINED"
        is NavEvent.TurnApproaching -> "TURN_APPROACHING"
        is NavEvent.TurnNow -> "TURN_NOW"
        NavEvent.OffRoute -> "OFF_ROUTE"
        NavEvent.BackOnRoute -> "BACK_ON_ROUTE"
        NavEvent.SignalLost -> "SIGNAL_LOST"
        NavEvent.SignalBack -> "SIGNAL_BACK"
        NavEvent.ReversePrompt -> "REVERSE_PROMPT"
        NavEvent.Reversed -> "REVERSED"
        NavEvent.ReverseUndone -> "REVERSE_UNDONE"
        NavEvent.Finished -> "FINISHED"
    }

private val NavEvent.maneuverIndex: Int?
    get() = when (this) {
        is NavEvent.TurnApproaching -> index
        is NavEvent.TurnNow -> index
        else -> null
    }
