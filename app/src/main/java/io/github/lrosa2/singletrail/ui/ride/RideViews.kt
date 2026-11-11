package io.github.lrosa2.singletrail.ui.ride

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lrosa2.singletrail.R
import io.github.lrosa2.singletrail.nav.engine.GpsStatus
import io.github.lrosa2.singletrail.nav.engine.NavState
import io.github.lrosa2.singletrail.nav.engine.RidePhase
import io.github.lrosa2.singletrail.ride.ArrowLook
import io.github.lrosa2.singletrail.ride.ArrowTone
import io.github.lrosa2.singletrail.ride.RideMessage
import io.github.lrosa2.singletrail.ride.RideTexts
import io.github.lrosa2.singletrail.ui.Formats
import io.github.lrosa2.singletrail.ui.StarRating

/** Route name, simulated tag, GPS and battery, in one quiet line. */
@Composable
fun StatusStrip(routeName: String, simulated: Boolean, nav: NavState?, satellitesInView: Int?, battery: Int?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            routeName + if (simulated) " · " + stringResource(R.string.ride_simulated) else "",
            Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val gps = when (nav?.gps) {
            null, GpsStatus.WAITING -> stringResource(R.string.ride_gps_waiting)
            GpsStatus.LOST -> stringResource(R.string.ride_gps_none)
            GpsStatus.POOR -> stringResource(R.string.ride_gps_poor)
            GpsStatus.OK -> stringResource(R.string.ride_gps_satellites, (nav.satellites ?: satellitesInView)?.toString() ?: "✓")
        }
        Text(gps, style = MaterialTheme.typography.titleSmall, color = if (nav?.gps == GpsStatus.LOST) MaterialTheme.colorScheme.error else Color.Unspecified)
        battery?.let { Text(stringResource(R.string.ride_battery, it), style = MaterialTheme.typography.titleSmall) }
    }
}

/** The one line that matters right now (spec §4.2), large, coloured by state. */
@Composable
fun Banner(nav: NavState, message: RideMessage) {
    val resources = LocalResources.current
    val colors = when {
        nav.gps == GpsStatus.LOST -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        nav.phase == RidePhase.OFF_ROUTE || nav.phase == RidePhase.REVERSE_PENDING ->
            MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
    }
    Surface(color = colors.first, contentColor = colors.second, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(RideTexts.message(resources, message), fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold)
            nav.then?.let { then ->
                Text(
                    stringResource(R.string.ride_then, RideTexts.glyph(then.maneuver), Formats.distance(RideTexts.roundForDisplay(then.distanceM))),
                    fontSize = 20.sp,
                )
            }
        }
    }
}

/** Arrow view (spec §4.1): one large arrow to the look-ahead point, relative to the rider's heading. */
@Composable
fun ArrowView(nav: NavState, satellitesInView: Int?, modifier: Modifier = Modifier) {
    val look = ArrowLook.of(nav)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        if (look != null) {
            val color = when (look.tone) {
                ArrowTone.NORMAL -> MaterialTheme.colorScheme.primary
                ArrowTone.WARNING -> MaterialTheme.colorScheme.tertiary
                ArrowTone.INACTIVE -> MaterialTheme.colorScheme.outline
            }
            val description = stringResource(R.string.ride_arrow)
            Box(Modifier.weight(1f, fill = false).fillMaxWidth(0.8f).aspectRatio(1f), contentAlignment = Alignment.Center) {
                BigArrow(nav.arrowDeg ?: 0.0, color, Modifier.fillMaxSize().semantics { contentDescription = description })
                if (look.compass) {
                    Text("N", Modifier.align(Alignment.TopEnd), color = MaterialTheme.colorScheme.outline, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                }
            }
        } else if (nav.phase == RidePhase.WAITING_GPS) {
            Text((nav.satellites ?: satellitesInView)?.toString() ?: "…", fontSize = 96.sp, fontWeight = FontWeight.Bold)
        }
        caption(nav)?.let { Text(it, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center) }
        if (nav.heading?.needsCalibration == true) {
            Text(stringResource(R.string.ride_calibrate), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun caption(nav: NavState): String? {
    val target = nav.target
    return when (nav.phase) {
        RidePhase.ON_ROUTE -> nav.next?.let {
            stringResource(R.string.ride_next_in, RideTexts.glyph(it.maneuver), Formats.distance(RideTexts.roundForDisplay(it.distanceM)))
        }
        RidePhase.ACQUIRING -> target?.let { stringResource(R.string.ride_to_route, Formats.distance(it.distanceM)) }
        RidePhase.OFF_ROUTE -> target?.let { stringResource(R.string.ride_back_to_route, Formats.distance(it.distanceM)) }
        else -> null
    }
}

/** A big arrow pointing [degrees] clockwise from straight up. */
@Composable
private fun BigArrow(degrees: Double, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.5f, h * 0.06f)
            lineTo(w * 0.88f, h * 0.5f)
            lineTo(w * 0.63f, h * 0.5f)
            lineTo(w * 0.63f, h * 0.94f)
            lineTo(w * 0.37f, h * 0.94f)
            lineTo(w * 0.37f, h * 0.5f)
            lineTo(w * 0.12f, h * 0.5f)
            close()
        }
        rotate(degrees.toFloat()) { drawPath(path, color) }
    }
}

/** Distance left, climb left, ridden, moving time. */
@Composable
fun StatsBar(nav: NavState) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Stat(stringResource(R.string.ride_left), Formats.distance(nav.distanceLeftM))
        nav.climbLeftM?.let { Stat(stringResource(R.string.ride_climb_left), Formats.elevation(it)) }
        Stat(stringResource(R.string.ride_ridden), Formats.distance(nav.rideDistanceM))
        Stat(stringResource(R.string.ride_moving_time), Formats.duration(nav.movingSeconds))
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Finish (spec §3, journey 5): the ride in numbers, and stars for the route. */
@Composable
fun FinishCard(nav: NavState, rating: Int?, onRate: ((Int?) -> Unit)?) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.finish_title), fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.finish_summary, Formats.distance(nav.rideDistanceM), Formats.duration(nav.movingSeconds)),
                fontSize = 20.sp,
            )
            if (onRate != null) {
                Text(stringResource(R.string.finish_rate), style = MaterialTheme.typography.titleMedium)
                StarRating(rating, onRate)
            }
        }
    }
}

/**
 * A button that acts on a deliberate long-press only (spec §4.5): rain, mud and gloves cause
 * phantom taps. A short tap shows [hint] instead.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HoldButton(
    label: String,
    container: Color,
    content: Color,
    onHold: () -> Unit,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(32.dp), modifier = modifier.heightIn(min = 64.dp)) {
        Box(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).combinedClickable(onClick = onTap, onLongClick = onHold).padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        }
    }
}
