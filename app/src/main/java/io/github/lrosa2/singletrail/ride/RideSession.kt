package io.github.lrosa2.singletrail.ride

import io.github.lrosa2.singletrail.nav.Geo
import io.github.lrosa2.singletrail.nav.PreparedRoute
import io.github.lrosa2.singletrail.nav.engine.Fix
import io.github.lrosa2.singletrail.nav.engine.HeadingSample
import io.github.lrosa2.singletrail.nav.engine.NavCheckpoint
import io.github.lrosa2.singletrail.nav.engine.NavEvent
import io.github.lrosa2.singletrail.nav.engine.NavState
import io.github.lrosa2.singletrail.nav.engine.NavUpdate
import io.github.lrosa2.singletrail.nav.engine.NavTrace
import io.github.lrosa2.singletrail.nav.engine.Navigator
import io.github.lrosa2.singletrail.nav.engine.TraceRecord
import io.github.lrosa2.singletrail.nav.engine.TraceRecorder
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/** A fix plus its GPS UTC time, which the engine doesn't need but the recorded track does. */
data class GpsReading(val fix: Fix, val utcMillis: Long)

/** Where a ride's results go: the beeper, the track file, the checkpoint file and the debug trace. */
interface RideOutput {
    fun beep(tone: Tone)
    fun record(reading: GpsReading)
    fun checkpoint(nav: NavCheckpoint)
    fun trace(line: String)
}

/**
 * Runs a ride (spec §13.2): feeds fixes, compass readings and a once-a-second clock to the
 * [Navigator], turns its events into beeps, and records, checkpoints and (optionally) traces.
 *
 * Everything runs in the coroutine that calls [run], which must be on a single thread: the
 * navigator is not thread-safe. Read [snapshot] only after that coroutine has finished.
 */
class RideSession(
    private val navigator: Navigator,
    private val output: RideOutput,
    private val clock: () -> Long,
    /** With a header the session writes a debug trace (spec §11), starting with that header. */
    private val traceHeader: TraceRecord.Header? = null,
    private val tickMillis: Long = 1_000,
    private val checkpointMillis: Long = 5_000,
    /** Nanoseconds, to time the engine per fix for the log (spec §10: < 5 ms). */
    private val cpuClock: () -> Long = System::nanoTime,
) {
    private val recorder = if (traceHeader != null) TraceRecorder(navigator, output::trace) else null
    private var fixes = 0
    private var fixNanos = 0L
    private var slowestFixNanos = 0L
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val mutableState = MutableStateFlow(navigator.state)

    val state: StateFlow<NavState> = mutableState.asStateFlow()

    private enum class Command { ACCEPT_REVERSE, UNDO_REVERSE }

    suspend fun run(readings: Flow<GpsReading>, headings: Flow<HeadingSample>): Nothing = coroutineScope {
        traceHeader?.let { output.trace(NavTrace.encode(it)) }
        launch {
            readings.collect { r ->
                output.record(r)
                val now = max(clock(), r.fix.elapsedNanos)
                val started = cpuClock()
                val update = recorder?.onFix(r.fix, now) ?: navigator.onFix(r.fix, now)
                val took = cpuClock() - started
                fixes++
                fixNanos += took
                slowestFixNanos = max(slowestFixNanos, took)
                handle(update)
            }
        }
        launch {
            headings.collect { h -> handle(recorder?.onHeading(h) ?: navigator.onHeading(h)) }
        }
        launch {
            while (true) {
                delay(tickMillis)
                val now = clock()
                handle(recorder?.onTick(now) ?: navigator.onTick(now))
            }
        }
        launch {
            while (true) {
                delay(checkpointMillis)
                output.checkpoint(navigator.snapshot())
            }
        }
        launch {
            for (command in commands) {
                val now = clock()
                handle(
                    when (command) {
                        Command.ACCEPT_REVERSE -> recorder?.acceptReverse(now) ?: navigator.acceptReverse(now)
                        Command.UNDO_REVERSE -> recorder?.undoReverse(now) ?: navigator.undoReverse(now)
                    },
                )
            }
        }
        awaitCancellation()
    }

    fun acceptReverse() {
        commands.trySend(Command.ACCEPT_REVERSE)
    }

    fun undoReverse() {
        commands.trySend(Command.UNDO_REVERSE)
    }

    fun snapshot(): NavCheckpoint = navigator.snapshot()

    /** How long the engine took per fix, for the log; like [snapshot], read it after [run] ends. */
    fun engineTimes(): String = if (fixes == 0) {
        "Engine: no fixes"
    } else {
        String.format(Locale.ROOT, "Engine: %d fixes, mean %.2f ms, max %.1f ms", fixes, fixNanos / 1e6 / fixes, slowestFixNanos / 1e6)
    }

    private fun handle(update: NavUpdate) {
        mutableState.value = update.state
        update.events.forEach { event -> toneFor(event)?.let(output::beep) }
    }

    companion object {
        fun toneFor(event: NavEvent): Tone? = when (event) {
            is NavEvent.TurnApproaching -> Tone.APPROACHING
            is NavEvent.TurnNow -> Tone.NOW
            NavEvent.OffRoute -> Tone.OFF_ROUTE
            NavEvent.BackOnRoute -> Tone.BACK_ON_ROUTE
            NavEvent.SignalLost -> Tone.SIGNAL_LOST
            NavEvent.SignalBack -> Tone.SIGNAL_BACK
            // The prompt, and its acceptance after 10 s, both sound (spec §4.5).
            NavEvent.ReversePrompt, NavEvent.Reversed -> Tone.REVERSE_PROMPT
            NavEvent.Finished -> Tone.FINISH
            NavEvent.Joined, NavEvent.ReverseUndone -> null
        }
    }
}

/**
 * Debug "Simulate ride" (spec §12.2): a fix every [intervalMillis] along [route] at [speedMps],
 * with GPS-like noise. At the finish it keeps reporting the finish, standing still.
 */
class RouteSimulator(
    private val route: PreparedRoute,
    private val speedMps: Double = 5.0,
    private val noiseM: Double = 3.0,
    private val random: Random = Random(7),
    private val intervalMillis: Long = 1_000,
) {
    fun readings(startS: Double, clock: () -> Long, wallClock: () -> Long): Flow<GpsReading> = flow {
        val length = route.lengthMeters
        var s = startS.coerceIn(0.0, length)
        while (true) {
            emit(readingAt(s, if (s < length) speedMps else 0.0, clock(), wallClock()))
            delay(intervalMillis)
            s = min(length, s + speedMps * intervalMillis / 1000.0)
        }
    }

    private fun readingAt(s: Double, speed: Double, elapsedNanos: Long, utcMillis: Long): GpsReading {
        val p = route.positionAt(s)
        val a = route.positionAt(max(0.0, s - 2))
        val b = route.positionAt(min(route.lengthMeters, s + 2))
        val metersPerDegree = Geo.EARTH_RADIUS_M * PI / 180
        val lat = p.lat + random.nextGaussian() * noiseM / metersPerDegree
        val lon = p.lon + random.nextGaussian() * noiseM / (metersPerDegree * cos(Math.toRadians(p.lat)))
        val fix = Fix(
            elapsedNanos = elapsedNanos,
            lat = lat,
            lon = lon,
            accuracyM = (noiseM + 2).toFloat(),
            speedMps = speed.toFloat(),
            bearingDeg = Geo.bearingDeg(a.lat, a.lon, b.lat, b.lon).toFloat(),
            bearingAccDeg = 5f,
            altitudeM = route.elevation?.elevationAt(s),
            satellites = 10,
        )
        return GpsReading(fix, utcMillis)
    }
}
