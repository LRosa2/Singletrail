package io.github.lrosa2.singletrail.ui.ride

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lrosa2.singletrail.R
import io.github.lrosa2.singletrail.data.settings.RideSettings
import io.github.lrosa2.singletrail.data.settings.SettingsStore
import io.github.lrosa2.singletrail.nav.engine.NavState
import io.github.lrosa2.singletrail.nav.engine.RidePhase
import io.github.lrosa2.singletrail.ride.BatteryAdvice
import io.github.lrosa2.singletrail.ride.BatteryPolicy
import io.github.lrosa2.singletrail.ride.RideController
import io.github.lrosa2.singletrail.ride.RideFailure
import io.github.lrosa2.singletrail.ride.RideMessage
import io.github.lrosa2.singletrail.ride.RideUi
import io.github.lrosa2.singletrail.ride.RideWarning
import io.github.lrosa2.singletrail.ride.ScreenPolicy
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val RELOCK_MS = 15_000L
private const val HINT_MS = 3_000L

/**
 * The ride screen (spec §4): the guidance banner, two swipeable views (arrow, and a route overview
 * until the map arrives in M7), the ride lock, dimming between turns and the battery policy.
 */
@Composable
fun RideScreen(controller: RideController, settings: SettingsStore, onClose: () -> Unit) {
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
    RideTheme { RideContent(current, controller, settings, onClose) }
}

@Composable
private fun RideContent(ui: RideUi, controller: RideController, settings: SettingsStore, onClose: () -> Unit) {
    val rideSettings by settings.settings.collectAsStateWithLifecycle(initialValue = RideSettings())
    val now by produceState(SystemClock.elapsedRealtimeNanos()) {
        while (true) {
            delay(500)
            value = SystemClock.elapsedRealtimeNanos()
        }
    }
    var lastTouch by remember { mutableLongStateOf(SystemClock.elapsedRealtimeNanos()) }
    var locked by rememberSaveable { mutableStateOf(true) }
    var hintUntil by remember { mutableLongStateOf(0L) }
    var confirmingStop by remember { mutableStateOf(false) }
    val battery = rememberBattery()
    val nav = ui.nav

    // Spec §4.5: the lock comes back by itself a while after the last touch; at the finish it lifts for good.
    val finished = nav?.phase == RidePhase.FINISHED
    LaunchedEffect(locked, lastTouch, finished) {
        if (!locked && !finished) {
            delay(RELOCK_MS)
            locked = true
        }
    }
    LaunchedEffect(finished) { if (finished) locked = false }

    val secondsSinceTouch = (now - lastTouch) / 1e9
    WindowBrightness(nav?.let { ScreenPolicy.brightness(it, secondsSinceTouch, rideSettings.dimBetweenTurns, ui.lowPower, rideSettings.dimLevel) })

    Surface(
        Modifier.fillMaxSize().pointerInput(Unit) {
            // Any touch counts as interaction (brightens the screen) without being consumed here.
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent(PointerEventPass.Initial)
                    lastTouch = SystemClock.elapsedRealtimeNanos()
                }
            }
        },
    ) {
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(
                Modifier.fillMaxSize().padding(12.dp).then(
                    if (locked) {
                        Modifier.pointerInput(Unit) {
                            // Locked: swallow every touch on the content and say how to unlock.
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    event.changes.forEach { it.consume() }
                                    hintUntil = SystemClock.elapsedRealtimeNanos() + HINT_MS * 1_000_000
                                }
                            }
                        }
                    } else {
                        Modifier
                    },
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StatusStrip(ui.routeName, ui.simulated, nav, ui.satellitesInView, battery?.percent)
                val failure = ui.failure
                when {
                    failure != null -> FailureCard(failure) {
                        controller.dismiss()
                        onClose()
                    }
                    nav == null -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    else -> RideBody(ui, nav, now, settings, controller, locked, battery, rideSettings.lowPowerAt)
                }
                // Room for the controls that stay usable while locked.
                Spacer(Modifier.height(if (nav?.phase == RidePhase.REVERSE_PENDING) 152.dp else 76.dp))
            }

            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (now < hintUntil && locked) {
                    // Drawn over the stats for a moment, so it needs its own background.
                    Surface(
                        Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.inverseSurface,
                        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    ) {
                        Text(stringResource(R.string.ride_locked_hint), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (nav?.phase == RidePhase.REVERSE_PENDING) {
                    ReversePrompt(locked, onUndo = controller::undoReverse, onAccept = controller::acceptReverse) {
                        hintUntil = SystemClock.elapsedRealtimeNanos() + HINT_MS * 1_000_000
                    }
                }
                if (ui.failure == null) {
                    val hint = { hintUntil = SystemClock.elapsedRealtimeNanos() + HINT_MS * 1_000_000 }
                    if (locked) {
                        HoldButton(
                            "🔒  " + stringResource(R.string.ride_unlock),
                            MaterialTheme.colorScheme.inverseSurface,
                            MaterialTheme.colorScheme.inverseOnSurface,
                            onHold = { locked = false },
                            onTap = hint,
                        )
                    } else {
                        HoldButton(
                            stringResource(R.string.action_hold_stop),
                            MaterialTheme.colorScheme.error,
                            MaterialTheme.colorScheme.onError,
                            onHold = { confirmingStop = true },
                            onTap = hint,
                        )
                    }
                }
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
private fun androidx.compose.foundation.layout.ColumnScope.RideBody(
    ui: RideUi,
    nav: NavState,
    now: Long,
    settings: SettingsStore,
    controller: RideController,
    locked: Boolean,
    battery: Battery?,
    lowPowerAt: Int,
) {
    if (nav.phase == RidePhase.FINISHED) {
        // The finish card takes the banner's place.
        FinishCard(nav, ui.rating, onRate = if (ui.routeId != null) controller::rate else null)
    } else {
        Banner(nav, RideMessage.of(nav, now, ui.satellitesInView))
    }
    val savedView by settings.rideView.collectAsStateWithLifecycle(initialValue = null)
    val start = savedView
    if (start == null) {
        Spacer(Modifier.weight(1f))
    } else {
        // Low-power mode shows the arrow only (spec §4.4).
        val pages = if (ui.lowPower) 1 else 2
        val pager = rememberPagerState(initialPage = start.coerceIn(0, pages - 1)) { pages }
        val scope = rememberCoroutineScope()
        LaunchedEffect(pager.currentPage) { if (pages > 1) settings.setRideView(pager.currentPage) }
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), userScrollEnabled = !locked) { page ->
            if (page == 0) {
                ArrowView(nav, ui.satellitesInView, Modifier.fillMaxSize())
            } else {
                RouteView(nav, ui.outline, ui.maneuvers, Modifier.fillMaxSize())
            }
        }
        if (pages > 1) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                listOf(R.string.ride_view_arrow, R.string.ride_view_route).forEachIndexed { i, label ->
                    TextButton(onClick = { scope.launch { pager.animateScrollToPage(i) } }) {
                        Text(
                            stringResource(label),
                            fontWeight = if (pager.currentPage == i) FontWeight.Bold else FontWeight.Normal,
                            color = if (pager.currentPage == i) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        }
    }
    StatsBar(nav)
    BatteryNotice(battery, lowPowerAt, ui.lowPower, controller::setLowPower)
    ui.warnings.sortedBy { it.ordinal }.take(2).forEach { WarningLine(it) }
}

@Composable
private fun ReversePrompt(locked: Boolean, onUndo: () -> Unit, onAccept: () -> Unit, onTapWhileLocked: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val undo = stringResource(R.string.action_undo)
        val accept = stringResource(R.string.action_accept)
        if (locked) {
            // Through the lock, but only on a deliberate hold; the volume keys work too.
            HoldButton(
                stringResource(R.string.action_hold, undo),
                MaterialTheme.colorScheme.surfaceVariant,
                MaterialTheme.colorScheme.onSurfaceVariant,
                onHold = onUndo,
                onTap = onTapWhileLocked,
                modifier = Modifier.weight(1f),
            )
            HoldButton(
                stringResource(R.string.action_hold, accept),
                MaterialTheme.colorScheme.primary,
                MaterialTheme.colorScheme.onPrimary,
                onHold = onAccept,
                onTap = onTapWhileLocked,
                modifier = Modifier.weight(1f),
            )
        } else {
            OutlinedButton(onClick = onUndo, modifier = Modifier.weight(1f).height(64.dp)) { Text(undo, fontSize = 18.sp) }
            Button(onClick = onAccept, modifier = Modifier.weight(1f).height(64.dp)) { Text(accept, fontSize = 18.sp) }
        }
    }
}

@Composable
private fun BatteryNotice(battery: Battery?, lowPowerAt: Int, lowPower: Boolean, setLowPower: (Boolean) -> Unit) {
    if (lowPower) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.ride_low_power_on), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { setLowPower(false) }) { Text(stringResource(R.string.action_turn_off)) }
        }
        return
    }
    val percent = battery?.percent ?: return
    when (BatteryPolicy.advice(percent, battery.charging, lowPowerAt)) {
        BatteryAdvice.NONE -> Unit
        BatteryAdvice.LOW -> Text(
            stringResource(R.string.ride_battery_low, percent),
            color = MaterialTheme.colorScheme.tertiary,
            style = MaterialTheme.typography.titleSmall,
        )
        BatteryAdvice.VERY_LOW -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
            Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ride_battery_very_low, percent), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { setLowPower(true) }) { Text(stringResource(R.string.action_low_power)) }
            }
        }
    }
}

@Composable
private fun WarningLine(warning: RideWarning) {
    val text = when (warning) {
        RideWarning.LOCATION_OFF -> R.string.warning_location_off
        RideWarning.ALARM_VOLUME_OFF -> R.string.warning_alarm_volume_off
        RideWarning.DND_BLOCKS_ALARMS -> R.string.warning_dnd_blocks_alarms
        RideWarning.BLUETOOTH_AUDIO -> R.string.warning_bluetooth_audio
        RideWarning.BATTERY_SAVER_GPS -> R.string.warning_battery_saver_gps
        RideWarning.NOTIFICATIONS_OFF -> R.string.warning_notifications_off
        RideWarning.RECORDING_STOPPED -> R.string.warning_recording_stopped
    }
    Text("⚠ " + stringResource(text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
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

/** Window brightness only (spec §4.4): no system setting changes, no special permission. */
@Composable
private fun WindowBrightness(level: Float?) {
    val activity = LocalActivity.current ?: return
    LaunchedEffect(level) {
        activity.window.attributes = activity.window.attributes.apply {
            screenBrightness = level ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }
    DisposableEffect(activity) {
        onDispose {
            activity.window.attributes = activity.window.attributes.apply {
                screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }
    }
}

data class Battery(val percent: Int, val charging: Boolean)

/** The battery level, from the sticky battery broadcast while the ride screen is shown. */
@Composable
private fun rememberBattery(): Battery? {
    val context = LocalContext.current
    var battery by remember { mutableStateOf<Battery?>(null) }
    DisposableEffect(context) {
        fun read(intent: Intent): Battery? {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level < 0 || scale <= 0) return null
            return Battery(level * 100 / scale, intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                battery = read(intent)
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
            ?.let { battery = read(it) }
        onDispose { context.unregisterReceiver(receiver) }
    }
    return battery
}
