package io.github.lrosa2.singletrail.backup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RouteSharingTest {

    @Test
    fun `a zip shared from a collection is named after it`() {
        assertEquals("Waldegg loops.zip", RouteSharing.zipName("Waldegg loops"))
        assertEquals("Singletrail routes.zip", RouteSharing.zipName(null))
    }
}
