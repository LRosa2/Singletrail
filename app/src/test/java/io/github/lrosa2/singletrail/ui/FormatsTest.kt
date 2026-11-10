package io.github.lrosa2.singletrail.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FormatsTest {

    @Test
    fun `short distances are in metres`() {
        assertEquals("0 m", Formats.distance(0.0))
        assertEquals("850 m", Formats.distance(849.6))
    }

    @Test
    fun `distances from 1 km have one decimal`() {
        assertEquals("1.0 km", Formats.distance(999.6))
        assertEquals("12.3 km", Formats.distance(12_345.0))
    }

    @Test
    fun `distances from 100 km are whole kilometres`() {
        assertEquals("150 km", Formats.distance(150_400.0))
    }

    @Test
    fun `durations are hours, minutes and seconds`() {
        assertEquals("0:00:00", Formats.duration(0.0))
        assertEquals("0:04:41", Formats.duration(281.6))
        assertEquals("12:03:09", Formats.duration(12 * 3600 + 3 * 60 + 9.0))
    }

    @Test
    fun `elevations are whole metres with thousands separators`() {
        assertEquals("1,234 m", Formats.elevation(1234.4))
        assertEquals("-12 m", Formats.elevation(-12.0))
    }
}
