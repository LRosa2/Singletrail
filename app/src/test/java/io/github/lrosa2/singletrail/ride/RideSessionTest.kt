package io.github.lrosa2.singletrail.ride

import io.github.lrosa2.singletrail.Plane
import io.github.lrosa2.singletrail.nav.Geo
import io.github.lrosa2.singletrail.nav.NavRoute
import io.github.lrosa2.singletrail.nav.PreparedRoute
import io.github.lrosa2.singletrail.nav.RoutePoint
import io.github.lrosa2.singletrail.nav.engine.NavCheckpoint
import io.github.lrosa2.singletrail.nav.engine.NavTrace
import io.github.lrosa2.singletrail.nav.engine.Navigator
import io.github.lrosa2.singletrail.nav.engine.RidePhase
import io.github.lrosa2.singletrail.nav.engine.TraceRecord
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

@OptIn(ExperimentalCoroutinesApi::class)
class RideSessionTest {

    private fun pt(x: Number, y: Number) = RoutePoint(Plane.lat(y.toDouble()), Plane.lon(x.toDouble()), 600.0 + y.toDouble() / 10)

    /** North 300 m, then a right turn, then east 300 m. */
    private val corner = PreparedRoute.prepare(listOf(pt(0, 0), pt(0, 300), pt(300, 300)))
    private val route = NavRoute.of(corner)

    private class FakeOutput : RideOutput {
        val tones = ArrayList<Tone>()
        val recorded = ArrayList<GpsReading>()
        val checkpoints = ArrayList<NavCheckpoint>()
        val trace = ArrayList<String>()
        override fun beep(tone: Tone) {
            tones += tone
        }
        override fun record(reading: GpsReading) {
            recorded += reading
        }
        override fun checkpoint(nav: NavCheckpoint) {
            checkpoints += nav
        }
        override fun trace(line: String) {
            trace += line
        }
    }

    private fun TestScope.clock(): () -> Long = { testScheduler.currentTime * 1_000_000L }

    private fun TestScope.simulated(speed: Double = 5.0): Flow<GpsReading> =
        RouteSimulator(corner, speedMps = speed, noiseM = 0.0).readings(0.0, clock(), wallClock = { 1_700_000_000_000L + testScheduler.currentTime })

    @Test
    fun `a ride round a corner beeps for the turn and the finish`() = runTest {
        val out = FakeOutput()
        val session = RideSession(Navigator(route), out, clock())
        val job = launch { session.run(simulated(), emptyFlow()) }
        advanceTimeBy(130_000)
        job.cancelAndJoin()
        assertEquals(listOf(Tone.APPROACHING, Tone.NOW, Tone.FINISH), out.tones)
        assertEquals(RidePhase.FINISHED, session.state.value.phase)
    }

    @Test
    fun `the time the engine takes per fix is measured for the log`() = runTest {
        // Read before and after each fix: the first takes 1 ms, the other three 3 ms each.
        val cpu = ArrayDeque(listOf(0, 1, 1, 4, 4, 7, 7, 10).map { it * 1_000_000L })
        val session = RideSession(Navigator(route), FakeOutput(), clock(), cpuClock = { cpu.removeFirst() })
        assertEquals("Engine: no fixes", session.engineTimes())
        val job = launch { session.run(simulated(), emptyFlow()) }
        advanceTimeBy(3_500) // fixes at 0, 1, 2 and 3 s
        job.cancelAndJoin()
        assertEquals("Engine: 4 fixes, mean 2.50 ms, max 3.0 ms", session.engineTimes())
    }

    @Test
    fun `every reading is recorded, also after the finish`() = runTest {
        val out = FakeOutput()
        val session = RideSession(Navigator(route), out, clock())
        val job = launch { session.run(simulated(speed = 10.0), emptyFlow()) }
        advanceTimeBy(80_500)
        job.cancelAndJoin()
        // One a second from t = 0: 81 readings, the last 20 or so standing at the finish.
        assertEquals(81, out.recorded.size)
        assertEquals(out.recorded.map { it.fix.elapsedNanos }.sorted(), out.recorded.map { it.fix.elapsedNanos })
    }

    @Test
    fun `a checkpoint is saved every five seconds`() = runTest {
        val out = FakeOutput()
        val session = RideSession(Navigator(route), out, clock())
        val job = launch { session.run(simulated(), emptyFlow()) }
        advanceTimeBy(26_000)
        job.cancelAndJoin()
        assertEquals(5, out.checkpoints.size)
        assertTrue(out.checkpoints.last().progressM > 100.0)
    }

    @Test
    fun `losing GPS beeps once the fixes have stopped for ten seconds`() = runTest {
        val out = FakeOutput()
        val session = RideSession(Navigator(route), out, clock())
        val fiveThenNothing = flow {
            simulated().take(5).collect { emit(it) }
            awaitCancellation()
        }
        val job = launch { session.run(fiveThenNothing, emptyFlow()) }
        advanceTimeBy(13_500)
        assertEquals(emptyList<Tone>(), out.tones)
        advanceTimeBy(1_000)
        assertEquals(listOf(Tone.SIGNAL_LOST), out.tones)
        job.cancelAndJoin()
    }

    @Test
    fun `tracing writes the inputs and events, and replays the same`() = runTest {
        val out = FakeOutput()
        val header = TraceRecord.Header(routeHash = "abc", note = "ride r1, segment 1")
        val session = RideSession(Navigator(route), out, clock(), traceHeader = header)
        val job = launch { session.run(simulated(), emptyFlow()) }
        advanceTimeBy(130_000)
        job.cancelAndJoin()
        val records = out.trace.map { NavTrace.decode(it)!! }
        assertEquals(header, records.first())
        assertTrue(records.count { it is TraceRecord.FixIn } >= 120)
        assertTrue(records.any { it is TraceRecord.Tick })
        val events = records.filterIsInstance<TraceRecord.Event>()
        assertEquals(listOf("JOINED", "TURN_APPROACHING", "TURN_NOW", "FINISHED"), events.map { it.name })
        assertEquals(events, NavTrace.replay(Navigator(route), records.asSequence()))
    }

    @Test
    fun `without tracing nothing is traced`() = runTest {
        val out = FakeOutput()
        val session = RideSession(Navigator(route), out, clock())
        val job = launch { session.run(simulated(), emptyFlow()) }
        advanceTimeBy(10_000)
        job.cancelAndJoin()
        assertEquals(emptyList<String>(), out.trace)
    }

    @Test
    fun `the reverse prompt can be undone from the ride screen`() = runTest {
        val out = FakeOutput()
        val session = RideSession(Navigator(route), out, clock())
        // Join halfway up the first leg and ride back towards the start.
        val backwards = RouteSimulator(corner.reversed(), speedMps = 5.0, noiseM = 0.0)
            .readings(450.0, clock(), wallClock = { 0L })
        val job = launch { session.run(backwards, emptyFlow()) }
        advanceTimeBy(24_000)
        assertEquals(RidePhase.REVERSE_PENDING, session.state.value.phase)
        assertEquals(listOf(Tone.REVERSE_PROMPT), out.tones)
        session.undoReverse()
        runCurrent()
        assertEquals(RidePhase.ON_ROUTE, session.state.value.phase)
        job.cancelAndJoin()
    }

    @Test
    fun `the simulator follows the route at its speed`() = runTest {
        val readings = ArrayList<GpsReading>()
        val job = launch { simulated(speed = 6.0).collect { readings += it } }
        advanceTimeBy(10_500)
        job.cancelAndJoin()
        assertEquals(11, readings.size)
        val last = readings.last().fix
        // 60 m up the first leg, heading north at 6 m/s, with the route's elevation.
        assertTrue(abs(last.lat - Plane.lat(60.0)) < 1e-6)
        assertEquals(6f, last.speedMps)
        assertTrue(abs(Geo.turnDeg(0.0, last.bearingDeg!!.toDouble())) < 0.5, "bearing ${last.bearingDeg}")
        assertEquals(606.0, last.altitudeM!!, 0.5)
        assertEquals(10_000_000_000L, last.elapsedNanos)
        assertEquals(1_700_000_010_000L, readings.last().utcMillis)
    }
}
