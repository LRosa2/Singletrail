package io.github.lrosa2.singletrail.nav.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TraceTest {

    private val route = navRoute(0 to 0, 0 to 400, 400 to 400)

    @Test
    fun `every record type survives a round trip`() {
        val records = listOf(
            TraceRecord.Header(reversed = true, routeHash = "abc", note = "test"),
            TraceRecord.FixIn(Fix(1, 46.5, 7.0, 4.5f, 3.2f, 181f, 12f, 812.5, 9), now = 2),
            TraceRecord.FixIn(Fix(3, 46.5, 7.0)),
            TraceRecord.HeadingIn(4, 270.5f, reliable = false),
            TraceRecord.Tick(5),
            TraceRecord.Action(6, TraceRecord.ActionKind.UNDO_REVERSE),
            TraceRecord.Event(7, "TURN_NOW", maneuver = 2, s = 812.25, phase = RidePhase.ON_ROUTE),
        )
        for (r in records) assertEquals(r, NavTrace.decode(NavTrace.encode(r)))
    }

    @Test
    fun `lines are compact JSON with a type`() {
        val line = NavTrace.encode(TraceRecord.Tick(5))
        assertEquals("""{"type":"tick","t":5}""", line)
    }

    @Test
    fun `blank lines are skipped and unknown fields ignored`() {
        assertNull(NavTrace.decode("   "))
        assertEquals(TraceRecord.Tick(9), NavTrace.decode("""{"type":"tick","t":9,"extra":true}"""))
    }

    @Test
    fun `a malformed line is an error`() {
        assertThrows<IllegalArgumentException> { NavTrace.decode("{not json") }
    }

    @Test
    fun `replaying a recorded ride gives the same events`() {
        val lines = ArrayList<String>()
        val recorder = TraceRecorder(Navigator(route)) { lines += it }
        recorder.start(reversed = false, routeHash = null)
        // Fixes from the simulator (its own navigator is thrown away), fed through the recorder.
        val sim = Ride(Navigator(route))
        sim.ride(0 to 0, 0 to 400, 120 to 400, 400 to 400, noiseM = 4.0)
        sim.log.mapNotNull { it.first }.forEach { recorder.onFix(it) }
        recorder.onTick(sim.nanos + 20 * NANOS_PER_SECOND)
        assertTrue(lines.size > 100)

        val recorded = lines.map { NavTrace.decode(it)!! }.filterIsInstance<TraceRecord.Event>()
        val replayed = NavTrace.replay(Navigator(route), lines.asSequence().mapNotNull { NavTrace.decode(it) })
        assertEquals(recorded, replayed)
        assertTrue(recorded.any { it.name == "TURN_NOW" } && recorded.any { it.name == "FINISHED" })
    }
}
