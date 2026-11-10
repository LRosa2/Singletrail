package io.github.lrosa2.singletrail.ui.ride

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lrosa2.singletrail.R
import io.github.lrosa2.singletrail.nav.engine.GpsStatus
import io.github.lrosa2.singletrail.nav.engine.HeadingSource
import io.github.lrosa2.singletrail.nav.engine.NavState
import io.github.lrosa2.singletrail.nav.engine.RidePhase
import io.github.lrosa2.singletrail.ride.RideController
import io.github.lrosa2.singletrail.ride.RideFailure
import io.github.lrosa2.singletrail.ride.RideMessage
import io.github.lrosa2.singletrail.ride.RideTexts
import io.github.lrosa2.singletrail.ride.RideUi
import io.github.lrosa2.singletrail.ride.RideWarning
import io.github.lrosa2.singletrail.ui.Formats
import kotlinx.coroutines.delay

/**
 * A plain ride screen for M6: the guidance line, the arrow, stats, warnings, the reverse prompt and
 * Stop. The real ride UI (map, swipeable views, ride lock, dimming) comes in M8.
 */
@Composable
fun RideScreen(controller: RideController, onClose: () -> Unit) {
    val ui by controller.ride.collectAsStateWithLifecycle()
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true // Spec §4.4: the screen stays on during a ride.
        onDispose { view.keepScreenOn = false }
    }
    val current = ui
    if (current == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val now by produceState(SystemClock.elapsedRealtimeNanos()) {
        while (true) {
            delay(500)
            value = SystemClock.elapsedRealtimeNanos()
        }
    }
    var confirmingStop by remember { mutableStateOf(false) }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Header(current)
            val failure = current.failure
            val nav = current.nav
            when {
                failure != null -> FailureCard(failure) {
                    controller.dismiss()
                    onClose()
                }
                nav == null -> Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                else -> {
                    Guidance(current, nav, now)
                    if (nav.phase == RidePhase.REVERSE_PENDING) {
                        ReversePrompt(onUndo = controller::undoReverse, onAccept = controller::acceptReverse)
                    }
                    if (nav.phase == RidePhase.FINISHED) {
                        Card { Text(stringResource(R.string.ride_finished_body), Modifier.padding(16.dp)) }
                    }
                    Stats(nav)
                }
            }
            current.warnings.sortedBy { it.ordinal }.forEach { WarningCard(it) }
            if (failure == null) {
                Button(
                    onClick = { confirmingStop = true },
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text(stringResource(R.string.action_stop_ride), fontSize = 18.sp) }
            }
        }
    }

    if (confirmingStop) {
        AlertDialog(
            onDismissRequest = { confirmingStop = false },
            title = { Text(stringResource(R.string.ride_stop_title)) },
            text = { Text(stringResource(R.string.ride_stop_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingStop = false
                    controller.stop()
                }) { Text(stringResource(R.string.action_stop_ride)) }
            },
            dismissButton = { TextButton(onClick = { confirmingStop = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun Header(ui: RideUi) {
    Column {
        Text(ui.routeName, style = MaterialTheme.typography.titleLarge, maxLines = 2)
        if (ui.simulated) {
            Text(stringResource(R.string.ride_simulated), color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun Guidance(ui: RideUi, nav: NavState, now: Long) {
    val resources = LocalResources.current
    val message = RideMessage.of(nav, now, ui.satellitesInView)
    val warning = nav.phase == RidePhase.OFF_ROUTE
    val grey = nav.gps == GpsStatus.LOST || nav.heading?.source == HeadingSource.FROZEN
    val container = when {
        nav.gps == GpsStatus.LOST -> MaterialTheme.colorScheme.surfaceVariant
        warning -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    Card(colors = CardDefaults.cardColors(containerColor = container), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(RideTexts.message(resources, message), fontSize = 26.sp, fontWeight = FontWeight.SemiBold, lineHeight = 32.sp)
            nav.then?.let { then ->
                Text(
                    stringResource(R.string.ride_then, RideTexts.glyph(then.maneuver), Formats.distance(RideTexts.roundForDisplay(then.distanceM))),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
    val arrowColor = when {
        grey -> MaterialTheme.colorScheme.outline
        warning -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    // At the finish there is nothing left to point at.
    if (nav.phase == RidePhase.FINISHED) return
    val description = stringResource(R.string.ride_arrow)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Arrow(nav.arrowDeg ?: 0.0, arrowColor, Modifier.fillMaxWidth(0.6f).aspectRatio(1f).semantics { contentDescription = description })
        if (nav.heading?.source == HeadingSource.COMPASS) {
            Text("N", Modifier.align(Alignment.TopEnd), color = MaterialTheme.colorScheme.outline, fontWeight = FontWeight.Bold)
        }
    }
}

/** A big arrow pointing [degrees] clockwise from straight up. */
@Composable
private fun Arrow(degrees: Double, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.5f, h * 0.06f)
            lineTo(w * 0.86f, h * 0.52f)
            lineTo(w * 0.62f, h * 0.52f)
            lineTo(w * 0.62f, h * 0.94f)
            lineTo(w * 0.38f, h * 0.94f)
            lineTo(w * 0.38f, h * 0.52f)
            lineTo(w * 0.14f, h * 0.52f)
            close()
        }
        rotate(degrees.toFloat()) { drawPath(path, color) }
    }
}

@Composable
private fun ReversePrompt(onUndo: () -> Unit, onAccept: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onUndo, modifier = Modifier.weight(1f).height(64.dp)) { Text(stringResource(R.string.action_undo), fontSize = 18.sp) }
        Button(onClick = onAccept, modifier = Modifier.weight(1f).height(64.dp)) { Text(stringResource(R.string.action_accept), fontSize = 18.sp) }
    }
}

@Composable
private fun Stats(nav: NavState) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Stat(stringResource(R.string.ride_left), Formats.distance(nav.distanceLeftM))
        nav.climbLeftM?.let { Stat(stringResource(R.string.ride_climb_left), Formats.elevation(it)) }
        Stat(stringResource(R.string.ride_ridden), Formats.distance(nav.rideDistanceM))
        Stat(stringResource(R.string.ride_moving_time), duration(nav.movingSeconds))
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun duration(seconds: Double): String {
    val s = seconds.toLong()
    return "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
}

@Composable
private fun WarningCard(warning: RideWarning) {
    val text = when (warning) {
        RideWarning.LOCATION_OFF -> R.string.warning_location_off
        RideWarning.ALARM_VOLUME_OFF -> R.string.warning_alarm_volume_off
        RideWarning.DND_BLOCKS_ALARMS -> R.string.warning_dnd_blocks_alarms
        RideWarning.BLUETOOTH_AUDIO -> R.string.warning_bluetooth_audio
        RideWarning.BATTERY_SAVER_GPS -> R.string.warning_battery_saver_gps
        RideWarning.NOTIFICATIONS_OFF -> R.string.warning_notifications_off
        RideWarning.RECORDING_STOPPED -> R.string.warning_recording_stopped
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer), modifier = Modifier.fillMaxWidth()) {
        Text("⚠ " + stringResource(text), Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onTertiaryContainer)
    }
}

@Composable
private fun FailureCard(failure: RideFailure, onClose: () -> Unit) {
    val text = when (failure) {
        RideFailure.ROUTE_MISSING -> R.string.ride_failed_route_missing
        RideFailure.ROUTE_UNREADABLE -> R.string.ride_failed_route_unreadable
        RideFailure.NO_PERMISSION -> R.string.ride_failed_no_permission
        RideFailure.START_FAILED -> R.string.ride_failed_start
    }
    val context = LocalContext.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(text), color = MaterialTheme.colorScheme.onErrorContainer)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (failure == RideFailure.NO_PERMISSION) {
                    Button(onClick = { openAppSettings(context) }) { Text(stringResource(R.string.action_open_settings)) }
                }
                OutlinedButton(onClick = onClose) { Text(stringResource(R.string.action_ok)) }
            }
        }
    }
}
