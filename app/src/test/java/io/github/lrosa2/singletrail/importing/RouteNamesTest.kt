package io.github.lrosa2.singletrail.importing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RouteNamesTest {

    @Test
    fun `komoot's GPX Download prefix is dropped`() {
        assertEquals(
            "Hohe Buche Summit – Gäbris loop from Vögelinsegg",
            RouteNames.clean("GPX Download: Hohe Buche Summit – Gäbris loop from Vögelinsegg"),
        )
    }

    @Test
    fun `the prefix is found whatever its case and spacing`() {
        assertEquals("Waldegg loop", RouteNames.clean("gpx download:Waldegg loop"))
        assertEquals("Waldegg loop", RouteNames.clean("  GPX Download :  Waldegg loop"))
    }

    @Test
    fun `a name that is only the prefix counts as no name`() {
        assertNull(RouteNames.clean("GPX Download:"))
        assertNull(RouteNames.clean("GPX Download: "))
    }

    @Test
    fun `other names stay as they are`() {
        assertEquals("Waldegg loop", RouteNames.clean("Waldegg loop"))
        assertEquals("Notes on GPX Download: why", RouteNames.clean("Notes on GPX Download: why"))
        assertEquals("GPX Downloads", RouteNames.clean("GPX Downloads"))
        assertNull(RouteNames.clean(null))
    }
}
