package io.github.lrosa2.singletrail.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lrosa2.singletrail.R
import io.github.lrosa2.singletrail.data.settings.RideSettings
import io.github.lrosa2.singletrail.data.settings.SettingsStore
import io.github.lrosa2.singletrail.ride.BeepPlayer
import io.github.lrosa2.singletrail.ride.Tone
import io.github.lrosa2.singletrail.ui.ride.openAppSettings
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Ride settings (spec §8.1); the remaining settings arrive with their milestones. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(store: SettingsStore, onBack: () -> Unit) {
    val settings by store.settings.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    val update: ((RideSettings) -> RideSettings) -> Unit = { change -> scope.launch { store.update(change) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        val s = settings ?: return@Scaffold
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OffRoute(s.offRouteMeters) { meters -> update { it.copy(offRouteMeters = meters) } }
            HorizontalDivider()
            BeepVolume(s.beepVolume) { volume -> update { it.copy(beepVolume = volume) } }
            HorizontalDivider()
            Toggle(stringResource(R.string.settings_compass), stringResource(R.string.settings_compass_body), !s.compassOff) { on ->
                update { it.copy(compassOff = !on) }
            }
            HorizontalDivider()
            Toggle(stringResource(R.string.settings_debug_trace), stringResource(R.string.settings_debug_trace_body), s.debugTrace) { on ->
                update { it.copy(debugTrace = on) }
            }
            HorizontalDivider()
            val context = LocalContext.current
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.settings_battery), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.settings_battery_body), style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { openAppSettings(context) }) { Text(stringResource(R.string.action_open_settings)) }
            }
        }
    }
}

@Composable
private fun OffRoute(meters: Int, onChange: (Int) -> Unit) {
    var value by remember(meters) { mutableFloatStateOf(meters.toFloat()) }
    val range = RideSettings.OFF_ROUTE_RANGE
    Column {
        Text(stringResource(R.string.settings_off_route), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.settings_off_route_value, value.roundToInt()), style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = value,
            onValueChange = { value = it },
            onValueChangeFinished = { onChange(value.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first) / 5 - 1,
        )
    }
}

@Composable
private fun BeepVolume(volume: Float, onChange: (Float) -> Unit) {
    var value by remember(volume) { mutableFloatStateOf(volume) }
    val scope = rememberCoroutineScope()
    val player = remember { BeepPlayer(scope) }
    DisposableEffect(player) { onDispose { player.close() } }
    Column {
        Text(stringResource(R.string.settings_beep_volume), style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Slider(
                value = value,
                onValueChange = { value = it },
                onValueChangeFinished = { onChange(value) },
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = {
                player.volume = value
                player.beep(Tone.APPROACHING)
            }) { Text(stringResource(R.string.settings_test_beep)) }
        }
    }
}

@Composable
private fun Toggle(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
