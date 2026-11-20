package io.github.lrosa2.singletrail.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI

class DirectionArrowsTest {

    @Test
    fun `arrows are spread evenly along the line, away from both ends`() {
        val arrows = directionArrows(listOf(ScreenPoint(0f, 0f), ScreenPoint(300f, 0f)), spacing = 100f, reach = 10f)
        assertEquals(3, arrows.size)
        listOf(50f, 150f, 250f).zip(arrows).forEach { (x, arrow) ->
            assertEquals(x, arrow.x, 0.01f)
            assertEquals(0f, arrow.y, 0.01f)
        }
    }

    @Test
    fun `each arrow points the way the line goes`() {
        // 200 px to the right, then 200 px down the screen.
        val arrows = directionArrows(
            listOf(ScreenPoint(0f, 0f), ScreenPoint(200f, 0f), ScreenPoint(200f, 200f)),
            spacing = 100f,
            reach = 10f,
        )
        assertEquals(4, arrows.size)
        listOf(0.0, 0.0, PI / 2, PI / 2).zip(arrows).forEach { (expected, arrow) ->
            assertEquals(expected, arrow.angle, 0.001, "arrow at (${arrow.x}, ${arrow.y})")
        }
    }

    @Test
    fun `an arrow just before a hairpin lies along its own leg, not across the turn`() {
        // 100 px to the right, a 10 px hairpin down, then 40 px back: the one arrow sits 25 px before the turn.
        val arrow = directionArrows(
            listOf(ScreenPoint(0f, 0f), ScreenPoint(100f, 0f), ScreenPoint(100f, 10f), ScreenPoint(60f, 10f)),
            spacing = 250f,
            reach = 10f,
        ).single()
        assertEquals(75f, arrow.x, 0.01f)
        assertEquals(0.0, arrow.angle, 0.001)
    }

    @Test
    fun `a line shorter than the spacing still gets one arrow, in its middle`() {
        val arrow = directionArrows(listOf(ScreenPoint(0f, 0f), ScreenPoint(0f, -40f)), spacing = 100f, reach = 10f).single()
        assertEquals(-20f, arrow.y, 0.01f)
        assertEquals(-PI / 2, arrow.angle, 0.001) // up the screen
    }

    @Test
    fun `no arrows without a line to put them on`() {
        assertTrue(directionArrows(listOf(ScreenPoint(5f, 5f)), spacing = 100f, reach = 10f).isEmpty())
        assertTrue(directionArrows(listOf(ScreenPoint(5f, 5f), ScreenPoint(5f, 5f)), spacing = 100f, reach = 10f).isEmpty())
    }
}
