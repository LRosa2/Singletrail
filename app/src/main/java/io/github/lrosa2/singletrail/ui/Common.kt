package io.github.lrosa2.singletrail.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.lrosa2.singletrail.R
import io.github.lrosa2.singletrail.nav.RoutePosition
import kotlin.math.cos
import kotlin.math.min

/** Read-only stars, e.g. on a library card. */
@Composable
fun StarRow(rating: Int, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    val description = pluralStringResource(R.plurals.stars_rated, rating, rating)
    Row(modifier.semantics(mergeDescendants = true) { contentDescription = description }) {
        repeat(5) { i ->
            Icon(
                Icons.Filled.Star,
                contentDescription = null,
                tint = if (i < rating) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.size(size),
            )
        }
    }
}

/** Tappable stars; tapping the current rating again clears it. */
@Composable
fun StarRating(rating: Int?, onRate: (Int?) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier) {
        for (n in 1..5) {
            IconButton(onClick = { onRate(if (rating == n) null else n) }) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = pluralStringResource(R.plurals.rate_stars, n, n),
                    tint = if (rating != null && n <= rating) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.size(36.dp),
                )
            }
        }
    }
}

/**
 * The route's shape drawn in its own box, north up: a stand-in for the map until offline maps
 * arrive (M7). Start is a filled dot, finish a ring, waypoints small dots. With [arrows], arrowheads
 * along the line show which way it is ridden (D53).
 */
@Composable
fun RouteOutline(
    outline: List<RoutePosition>,
    modifier: Modifier = Modifier,
    waypoints: List<Pair<Double, Double>> = emptyList(),
    arrows: Boolean = false,
) {
    val lineColor = MaterialTheme.colorScheme.secondary
    val startColor = MaterialTheme.colorScheme.primary
    val endColor = MaterialTheme.colorScheme.onSurface
    val waypointColor = MaterialTheme.colorScheme.tertiary
    val arrowEdge = MaterialTheme.colorScheme.surface
    Canvas(modifier) {
        if (outline.size < 2) return@Canvas
        val minLat = outline.minOf { it.lat }
        val maxLat = outline.maxOf { it.lat }
        val minLon = outline.minOf { it.lon }
        val maxLon = outline.maxOf { it.lon }
        val cosLat = cos(Math.toRadians((minLat + maxLat) / 2))
        val spanX = ((maxLon - minLon) * cosLat).coerceAtLeast(1e-9)
        val spanY = (maxLat - minLat).coerceAtLeast(1e-9)
        val pad = 12.dp.toPx()
        val width = size.width - 2 * pad
        val height = size.height - 2 * pad
        val scale = min(width / spanX, height / spanY)
        val left = pad + (width - spanX * scale) / 2
        val top = pad + (height - spanY * scale) / 2
        fun at(lat: Double, lon: Double) =
            Offset((left + (lon - minLon) * cosLat * scale).toFloat(), (top + (maxLat - lat) * scale).toFloat())

        val path = Path()
        outline.forEachIndexed { i, p ->
            val o = at(p.lat, p.lon)
            if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
        }
        drawPath(path, lineColor, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        if (arrows) {
            val points = outline.map { p -> at(p.lat, p.lon).let { ScreenPoint(it.x, it.y) } }
            // An arrowhead pointing right, centred on the line; turned to each arrow's angle.
            val head = Path().apply {
                moveTo(6.dp.toPx(), 0f)
                lineTo(-5.dp.toPx(), -6.dp.toPx())
                lineTo(-2.dp.toPx(), 0f)
                lineTo(-5.dp.toPx(), 6.dp.toPx())
                close()
            }
            directionArrows(points, spacing = 60.dp.toPx(), reach = 5.dp.toPx()).forEach { arrow ->
                translate(arrow.x, arrow.y) {
                    rotate(Math.toDegrees(arrow.angle).toFloat(), pivot = Offset.Zero) {
                        drawPath(head, startColor)
                        drawPath(head, arrowEdge, style = Stroke(width = 1.dp.toPx(), join = StrokeJoin.Round))
                    }
                }
            }
        }
        waypoints.forEach { (lat, lon) -> drawCircle(waypointColor, 4.dp.toPx(), at(lat, lon)) }
        drawCircle(startColor, 6.dp.toPx(), at(outline.first().lat, outline.first().lon))
        drawCircle(endColor, 5.dp.toPx(), at(outline.last().lat, outline.last().lon), style = Stroke(2.dp.toPx()))
    }
}
