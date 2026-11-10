package io.github.lrosa2.singletrail.ui.route

import io.github.lrosa2.singletrail.line
import io.github.lrosa2.singletrail.newRoute
import io.github.lrosa2.singletrail.track
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test

class ProfileSamplesTest {

    @Test
    fun `samples are spread evenly from start to finish`() {
        // 2 km climbing steadily from 400 m to 600 m.
        val route = newRoute(track("Climb", line(0 to 0, 2000 to 0, ele = { 400 + it / 10 }))).prepared
        val samples = profileSamples(route.elevation!!, route.lengthMeters, count = 5)
        assertArrayEquals(doubleArrayOf(400.0, 450.0, 500.0, 550.0, 600.0), samples, 0.5)
    }
}
