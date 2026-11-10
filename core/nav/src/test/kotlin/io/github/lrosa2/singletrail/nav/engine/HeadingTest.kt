package io.github.lrosa2.singletrail.nav.engine

import io.github.lrosa2.singletrail.nav.at
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HeadingTest {

    private val line = navRoute(0 to 0, 0 to 1000)
    private val s = NANOS_PER_SECOND

    private fun fix(t: Long, y: Double, speed: Float, bearing: Float?, bearingAcc: Float? = 10f): Fix {
        val p = at(0, y)
        return Fix(t, p.lat, p.lon, 5f, speed, bearing, bearingAcc)
    }

    @Test
    fun `GPS course is the heading when riding`() {
        val nav = Navigator(line)
        val state = nav.onFix(fix(0, 100.0, speed = 3f, bearing = 10f)).state
        assertEquals(Heading(10.0, HeadingSource.GPS), state.heading)
    }

    @Test
    fun `an inaccurate GPS course is not used`() {
        val nav = Navigator(line)
        nav.onHeading(HeadingSample(0, 50f, reliable = true))
        val state = nav.onFix(fix(0, 100.0, speed = 3f, bearing = 10f, bearingAcc = 45f)).state
        assertEquals(HeadingSource.COMPASS, state.heading!!.source)
    }

    @Test
    fun `the compass takes over when slow or stopped`() {
        val nav = Navigator(line)
        nav.onFix(fix(0, 100.0, speed = 3f, bearing = 10f))
        nav.onFix(fix(1 * s, 101.0, speed = 0.5f, bearing = 10f))
        val state = nav.onHeading(HeadingSample(1 * s, 95f, reliable = true)).state
        assertEquals(Heading(95.0, HeadingSource.COMPASS), state.heading)
    }

    @Test
    fun `an unreliable compass asks for calibration`() {
        val nav = Navigator(line)
        nav.onFix(fix(0, 100.0, speed = 0f, bearing = null))
        val state = nav.onHeading(HeadingSample(0, 95f, reliable = false)).state
        assertTrue(state.heading!!.needsCalibration)
    }

    @Test
    fun `a stale compass falls back to the last heading, greyed`() {
        val nav = Navigator(line)
        nav.onFix(fix(0, 100.0, speed = 3f, bearing = 10f))
        nav.onHeading(HeadingSample(0, 95f, reliable = true))
        val state = nav.onFix(fix(5 * s, 101.0, speed = 0f, bearing = null)).state
        assertEquals(Heading(10.0, HeadingSource.FROZEN), state.heading)
    }

    @Test
    fun `with the compass off a slow rider keeps the last GPS heading, greyed`() {
        val nav = Navigator(line, NavConfig(compassOff = true))
        nav.onFix(fix(0, 100.0, speed = 3f, bearing = 10f))
        nav.onHeading(HeadingSample(1 * s, 95f, reliable = true))
        val state = nav.onFix(fix(1 * s, 101.0, speed = 0.5f, bearing = 10f)).state
        assertEquals(Heading(10.0, HeadingSource.FROZEN), state.heading)
    }

    @Test
    fun `no heading before any reading`() {
        val nav = Navigator(line)
        assertNull(nav.onFix(fix(0, 100.0, speed = 0f, bearing = null)).state.heading)
    }

    @Test
    fun `the arrow points at the target relative to the heading`() {
        val nav = Navigator(line)
        // On the route facing north: the look-ahead point is straight ahead.
        assertEquals(0.0, nav.onFix(fix(0, 100.0, speed = 3f, bearing = 0f)).state.arrowDeg!!, 1.0)
        // Stopped and facing east (compass): the route ahead is 90 degrees to the left.
        nav.onFix(fix(1 * s, 101.0, speed = 0f, bearing = null))
        val state = nav.onHeading(HeadingSample(1 * s, 90f, reliable = true)).state
        assertEquals(-90.0, state.arrowDeg!!, 1.0)
        assertFalse(state.heading!!.needsCalibration)
    }

    @Test
    fun `the look-ahead point moves further ahead with speed`() {
        val slow = Ride(Navigator(line))
        slow.ride(0 to 0, 0 to 200, speedMps = 3.0)
        assertEquals(30.0, slow.state.target!!.s - slow.state.progressM, 1.0)
        val fast = Ride(Navigator(line))
        fast.ride(0 to 0, 0 to 200, speedMps = 12.0)
        assertEquals(60.0, fast.state.target!!.s - fast.state.progressM, 1.0)
    }
}
