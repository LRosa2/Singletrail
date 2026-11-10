package io.github.lrosa2.singletrail.ui.route

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import io.github.lrosa2.singletrail.ui.Formats

/**
 * Elevation profile drawn on a Canvas (spec §8.1: no chart library). Highest and lowest point on the
 * left, distance along the bottom.
 */
@Composable
fun ElevationChart(samples: DoubleArray, lengthMeters: Double, modifier: Modifier = Modifier) {
    val line = MaterialTheme.colorScheme.primary
    val fill = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    val labelStyle = MaterialTheme.typography.labelMedium
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val highest = samples.max()
    val lowest = samples.min()
    Column(modifier) {
        Text(Formats.elevation(highest), style = labelStyle, color = labelColor)
        Canvas(Modifier.fillMaxWidth().height(140.dp)) {
            // Keep at least 20 m of vertical range so flat routes look flat.
            var low = lowest
            var high = highest
            if (high - low < 20) {
                val mid = (high + low) / 2
                low = mid - 10
                high = mid + 10
            }
            val dx = size.width / (samples.size - 1)
            fun y(e: Double) = (size.height * (1 - (e - low) / (high - low))).toFloat()
            val profile = Path()
            samples.forEachIndexed { i, e -> if (i == 0) profile.moveTo(0f, y(e)) else profile.lineTo(i * dx, y(e)) }
            val area = Path().apply {
                addPath(profile)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
            drawPath(area, fill)
            drawPath(profile, line, style = Stroke(width = 2.dp.toPx(), join = StrokeJoin.Round))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(Formats.elevation(lowest), style = labelStyle, color = labelColor)
            Text(Formats.distance(lengthMeters), style = labelStyle, color = labelColor)
        }
    }
}
