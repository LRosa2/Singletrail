package io.github.lrosa2.singletrail.ui.rides

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lrosa2.singletrail.R
import io.github.lrosa2.singletrail.data.db.Direction
import io.github.lrosa2.singletrail.data.db.RideEntity
import io.github.lrosa2.singletrail.data.db.RideStatus
import io.github.lrosa2.singletrail.ui.Formats
import io.github.lrosa2.singletrail.ui.RouteOutline
import io.github.lrosa2.singletrail.util.Sharing
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Ride history (spec §8.1): every recorded ride, newest first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RidesScreen(viewModel: RidesViewModel, onOpenRide: (String) -> Unit, onBack: () -> Unit) {
    val rides by viewModel.rides.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.rides_title)) },
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { padding ->
        val list = rides
        when {
            list == null -> Unit
            list.isEmpty() -> Text(
                stringResource(R.string.rides_empty),
                Modifier.padding(padding).padding(24.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
            else -> LazyColumn(
                Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(list, key = { it.id }) { ride -> RideCard(ride, showRoute = true) { onOpenRide(ride.id) } }
            }
        }
    }
}

/** One ride in a list: route (optional), when, how far, how long. */
@Composable
fun RideCard(ride: RideEntity, showRoute: Boolean, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (showRoute) {
                Text(ride.routeNameSnapshot, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(rideTime(ride), style = MaterialTheme.typography.bodyMedium)
            Text(summary(ride), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun summary(ride: RideEntity): String = buildList {
    add(Formats.distance(ride.distanceM))
    add(Formats.duration(ride.movingTimeS.toDouble()))
    ride.ascentM?.let { add(stringResource(R.string.climb_value, Formats.elevation(it))) }
    add(stringResource(if (ride.status == RideStatus.FINISHED) R.string.ride_status_finished else R.string.ride_status_stopped))
}.joinToString(stringResource(R.string.separator))

private fun rideTime(ride: RideEntity): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ride.startedAt))

/** A recorded ride (spec §8.1): track, stats, GPX export, delete. The map arrives with M7. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RideDetailScreen(viewModel: RideDetailViewModel, onOpenRoute: (String) -> Unit, onBack: () -> Unit) {
    val ride by viewModel.ride.collectAsStateWithLifecycle()
    val track by viewModel.track.collectAsStateWithLifecycle()
    val routeExists by viewModel.routeExists.collectAsStateWithLifecycle()
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exportTitle = stringResource(R.string.ride_export_title)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(ride?.routeNameSnapshot.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { padding ->
        val current = ride ?: return@Scaffold
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                    val points = track
                    when {
                        points == null -> CircularProgressIndicator()
                        points.size < 2 -> Text(stringResource(R.string.ride_detail_no_track), Modifier.padding(16.dp))
                        else -> RouteOutline(points, Modifier.fillMaxSize())
                    }
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stat(stringResource(R.string.ride_detail_date), rideTime(current))
                    Stat(
                        stringResource(R.string.ride_detail_status),
                        stringResource(if (current.status == RideStatus.FINISHED) R.string.ride_status_finished else R.string.ride_status_stopped),
                    )
                    Stat(stringResource(R.string.ride_detail_distance), Formats.distance(current.distanceM))
                    Stat(stringResource(R.string.ride_detail_moving), Formats.duration(current.movingTimeS.toDouble()))
                    current.ascentM?.let { Stat(stringResource(R.string.ride_detail_climb), Formats.elevation(it)) }
                    Stat(
                        stringResource(R.string.ride_detail_direction),
                        stringResource(if (current.direction == Direction.REVERSE) R.string.ride_detail_reversed else R.string.ride_detail_forward),
                    )
                    if (current.segmentCount > 1) Stat(stringResource(R.string.ride_detail_segments), current.segmentCount.toString())
                }
            }
            if (!routeExists) {
                Text(stringResource(R.string.ride_detail_route_deleted), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    scope.launch {
                        viewModel.exportTo(Sharing.freshDir(context))?.let {
                            Sharing.share(context, listOf(it), "application/gpx+xml", exportTitle)
                        }
                    }
                }) { Text(stringResource(R.string.action_export_gpx)) }
                val routeId = current.routeId
                if (routeExists && routeId != null) {
                    OutlinedButton(onClick = { onOpenRoute(routeId) }) { Text(stringResource(R.string.action_open_route)) }
                }
                OutlinedButton(onClick = { confirmingDelete = true }) { Text(stringResource(R.string.action_delete)) }
            }
        }
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(R.string.ride_delete_title)) },
            text = { Text(stringResource(R.string.ride_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    viewModel.delete(onDeleted = onBack)
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
    }
}
