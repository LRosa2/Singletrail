package io.github.lrosa2.singletrail.gpx

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GpxLimitsTest {
    @Test
    fun `warning threshold sits below the hard point limit`() {
        assertTrue(GpxLimits.WARN_POINTS < GpxLimits.MAX_POINTS)
    }
}
