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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lrosa2.singletrail.R
import android.net.Uri
import io.github.lrosa2.singletrail.data.importing.WatchedFolder
import io.github.lrosa2.singletrail.diagnostics.DiagnosticsZip
import io.github.lrosa2.singletrail.util.Sharing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import io.github.lrosa2.singletrail.data.rides.RideRepository
import io.github.lrosa2.singletrail.data.settings.RideSettings
import io.github.lrosa2.singletrail.data.settings.SettingsStore
import io.github.lrosa2.singletrail.ride.BeepPlayer
import io.github.lrosa2.singletrail.ride.Tone
import io.github.lrosa2.singletrail.ui.ride.openAppSettings
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Ride settings (spec §8.1); the remaining settings arrive with their milestones. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    store: SettingsStore,
    rides: RideRepository,
    watched: WatchedFolder,
    onBack: () -> Unit,
    onRestore: () -> Unit,
    onChooseFolder: () -> Unit,
    onShowIgnored: () -> Unit,
) {
    val folder by watched.folder.collectAsStateWithLifecycle(initialValue = null)
    val folderName by produceState<String?>(null, folder) { value = folder?.let { watched.displayName(it) } }
    val settings by store.settings.collectAsStateWithLifecycle(initialValue = null)
    val rideCount by remember(rides) { rides.observeRides().map { it.size } }.collectAsStateWithLifecycle(initialValue = 0)
    var confirmingDeleteRides by remember { mutableStateOf(false) }
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
            Toggle(stringResource(R.string.settings_dim), stringResource(R.string.settings_dim_body), s.dimBetweenTurns) { on ->
                update { it.copy(dimBetweenTurns = on) }
            }
            if (s.dimBetweenTurns) DimLevel(s.dimLevel) { level -> update { it.copy(dimLevel = level) } }
            HorizontalDivider()
            Toggle(stringResource(R.string.settings_debug_trace),stringResource(R.string.settings_debug_trace_body), s.debugTrace) { on ->
                update { it.copy(debugTrace = on) }
            }
            HorizontalDivider()
            LowPowerAt(s.lowPowerAt) { percent -> update { it.copy(lowPowerAt = percent) } }
            HorizontalDivider()
            val context = LocalContext.current
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.settings_battery), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.settings_battery_body), style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { openAppSettings(context) }) { Text(stringResource(R.string.action_open_settings)) }
            }
            HorizontalDivider()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.settings_watched), style = MaterialTheme.typography.titleMedium)
                Text(
                    when {
                        folder == null -> stringResource(R.string.settings_watched_none)
                        !watched.canRead(folder!!) -> stringResource(R.string.settings_watched_unknown)
                        else -> stringResource(
                            R.string.settings_watched_current,
                            folderName ?: Uri.decode(folder!!.substringAfterLast('/')).substringAfterLast(':'),
                        )
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onChooseFolder) { Text(stringResource(R.string.action_choose_folder)) }
                    if (folder != null) {
                        OutlinedButton(onClick = { scope.launch { watched.stop() } }) { Text(stringResource(R.string.action_stop_watching)) }
                    }
                }
                if (folder != null) {
                    TextButton(onClick = onShowIgnored) { Text(stringResource(R.string.action_ignored_files)) }
                }
            }
            HorizontalDivider()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.settings_restore), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.settings_restore_body), style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = onRestore) { Text(stringResource(R.string.settings_restore)) }
            }
            HorizontalDivider()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.settings_diagnostics), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.settings_diagnostics_body), style = MaterialTheme.typography.bodyMedium)
                val diagnosticsTitle = stringResource(R.string.diagnostics_title)
                val shareDiagnostics: (Boolean) -> Unit = { traces ->
                    scope.launch {
                        val file = withContext(Dispatchers.IO) {
                            val day = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
                            File(Sharing.freshDir(context), "singletrail-diagnostics-$day.zip").apply {
                                outputStream().use { DiagnosticsZip.write(context.filesDir, it, includeTraces = traces) }
                            }
                        }
                        Sharing.share(context, listOf(file), "application/zip", diagnosticsTitle)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { shareDiagnostics(false) }) { Text(stringResource(R.string.action_share_logs)) }
                    if (DiagnosticsZip.hasTraces(context.filesDir)) {
                        OutlinedButton(onClick = { shareDiagnostics(true) }) { Text(stringResource(R.string.action_share_logs_traces)) }
                    }
                }
            }
            HorizontalDivider()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.settings_delete_rides), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.settings_delete_rides_body), style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { confirmingDeleteRides = true }, enabled = rideCount > 0) {
                    Text(stringResource(R.string.settings_delete_rides))
                }
            }
        }
    }

    if (confirmingDeleteRides) {
        AlertDialog(
            onDismissRequest = { confirmingDeleteRides = false },
            title = { Text(stringResource(R.string.delete_rides_title)) },
            text = { Text(pluralStringResource(R.plurals.delete_rides_body, rideCount, rideCount)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDeleteRides = false
                    scope.launch { rides.deleteAll() }
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmingDeleteRides = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
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
private fun LowPowerAt(percent: Int, onChange: (Int) -> Unit) {
    var value by remember(percent) { mutableFloatStateOf(percent.toFloat()) }
    val range = RideSettings.LOW_POWER_RANGE
    Column {
        Text(stringResource(R.string.settings_low_power), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.settings_low_power_value, value.roundToInt(), value.roundToInt() + 10),
            style = MaterialTheme.typography.bodyMedium,
        )
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
private fun DimLevel(level: Float, onChange: (Float) -> Unit) {
    var value by remember(level) { mutableFloatStateOf(level) }
    val range = RideSettings.DIM_RANGE
    Column {
        Text(stringResource(R.string.settings_dim_level, (value * 100).roundToInt()), style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = value,
            onValueChange = { value = it },
            onValueChangeFinished = { onChange(value) },
            valueRange = range,
        )
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
