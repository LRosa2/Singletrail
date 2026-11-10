package io.github.lrosa2.singletrail.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FileNamesTest {

    @Test
    fun `plain names are kept`() {
        assertEquals("Gurnigel loop.gpx", FileNames.safe("Gurnigel loop", "gpx"))
    }

    @Test
    fun `characters file systems reject become underscores`() {
        assertEquals("a_b_c_d_e_f_g_h_i.gpx", FileNames.safe("a/b\\c:d*e?f\"g<h>i", "gpx"))
    }

    @Test
    fun `control characters and surrounding dots and spaces go`() {
        assertEquals("Ride.gpx", FileNames.safe("  ..Ride\u0007.  ", "gpx"))
    }

    @Test
    fun `long names are cut`() {
        val name = FileNames.safe("x".repeat(300), "zip")
        assertEquals(80 + ".zip".length, name.length)
    }

    @Test
    fun `an empty name gets a fallback`() {
        assertEquals("route.gpx", FileNames.safe("  /  ", "gpx"))
    }

    @Test
    fun `names in one zip are made unique`() {
        val unique = FileNames.Unique()
        assertEquals(listOf("A.gpx", "A (2).gpx", "a (3).gpx", "B.gpx"), listOf("A", "A", "a", "B").map { unique.next(it, "gpx") })
    }
}
