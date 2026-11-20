package io.github.lrosa2.singletrail.ui

import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max

/** A point on the screen in pixels; y grows down the screen. */
data class ScreenPoint(val x: Float, val y: Float)

/** An arrow on a route line: where it sits, and which way it points in radians clockwise from rightwards. */
data class DirectionArrow(val x: Float, val y: Float, val angle: Double)

/**
 * Arrows showing which way a line will be ridden (spec §8.1, D53). One per [spacing] pixels of line, spread
 * evenly and kept away from both ends, where the start and finish markers sit; a line shorter than [spacing]
 * gets one in its middle. Each arrow follows the line over [reach] pixels either side: about the arrowhead's
 * own size, so it lies along the line under it even next to a hairpin.
 */
fun directionArrows(points: List<ScreenPoint>, spacing: Float, reach: Float): List<DirectionArrow> {
    if (points.size < 2) return emptyList()
    val along = FloatArray(points.size)
    for (i in 1..points.lastIndex) {
        along[i] = along[i - 1] + hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y)
    }
    val length = along.last()
    if (length <= 0f) return emptyList()

    fun at(distance: Float): ScreenPoint {
        val d = distance.coerceIn(0f, length)
        var i = 1
        while (i < points.lastIndex && along[i] < d) i++
        val from = points[i - 1]
        val to = points[i]
        val segment = along[i] - along[i - 1]
        val t = if (segment > 0f) (d - along[i - 1]) / segment else 0f
        return ScreenPoint(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t)
    }

    val count = max(1, floor(length / spacing).toInt())
    return List(count) { k ->
        val d = (k + 0.5f) * length / count
        val here = at(d)
        val back = at(d - reach)
        val ahead = at(d + reach)
        DirectionArrow(here.x, here.y, atan2((ahead.y - back.y).toDouble(), (ahead.x - back.x).toDouble()))
    }
}
