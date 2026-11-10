package io.github.lrosa2.singletrail.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ThinToTest {

    @Test
    fun `a short list is kept as is`() {
        val list = listOf(1, 2, 3)
        assertSame(list, list.thinTo(5))
    }

    @Test
    fun `a long list keeps at most max items including both ends`() {
        val thinned = (0..999).toList().thinTo(100)
        assertTrue(thinned.size <= 100, "size ${thinned.size}")
        assertEquals(0, thinned.first())
        assertEquals(999, thinned.last())
    }
}
