package io.github.lrosa2.singletrail.ui.route

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lrosa2.singletrail.R
import io.github.lrosa2.singletrail.data.db.RouteEntity
import io.github.lrosa2.singletrail.ui.Formats
import io.github.lrosa2.singletrail.ui.RouteOutline
import io.github.lrosa2.singletrail.ui.StarRating
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteDetailScreen(viewModel: RouteDetailViewModel, onBack: () -> Unit) {
    val load by viewModel.route.collectAsStateWithLifecycle()
    val geometry by viewModel.geometry.collectAsStateWithLifecycle()
    val route = (load as? RouteLoad.Loaded)?.route
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(route?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (route != null) {
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.action_edit)) }, onClick = {
                                    menuOpen = false
                                    editing = true
                                })
                                DropdownMenuItem(text = { Text(stringResource(R.string.action_delete)) }, onClick = {
                                    menuOpen = false
                                    confirmingDelete = true
                                })
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val current = load) {
                RouteLoad.Loading -> Unit
                RouteLoad.Missing -> Text(
                    stringResource(R.string.detail_missing),
                    Modifier.padding(24.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
                is RouteLoad.Loaded -> RouteDetails(current.route, geometry, viewModel::setRating)
            }
        }
    }

    if (editing && route != null) {
        EditRouteDialog(route, onDismiss = { editing = false }, onSave = { name, description ->
            editing = false
            viewModel.edit(name, description)
        })
    }
    if (confirmingDelete && route != null) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(R.string.delete_title)) },
            text = { Text(stringResource(R.string.delete_body, route.name)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    viewModel.delete(onDeleted = onBack)
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun RouteDetails(route: RouteEntity, geometry: GeometryLoad, onRate: (Int?) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val outlineDescription = stringResource(R.string.detail_outline)
        Card(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                when (geometry) {
                    GeometryLoad.Loading -> CircularProgressIndicator()
                    GeometryLoad.Failed -> Text(stringResource(R.string.detail_geometry_failed), Modifier.padding(16.dp))
                    is GeometryLoad.Loaded -> RouteOutline(
                        geometry.outline,
                        Modifier.fillMaxSize().semantics { contentDescription = outlineDescription },
                        waypoints = geometry.waypoints.map { it.lat to it.lon },
                    )
                }
            }
        }

        Stats(route, (geometry as? GeometryLoad.Loaded)?.waypoints?.size)

        Section(stringResource(R.string.detail_rating)) {
            StarRating(route.rating, onRate)
            Text(
                stringResource(R.string.detail_rating_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Section(stringResource(R.string.detail_profile)) {
            val profile = (geometry as? GeometryLoad.Loaded)?.profile
            when {
                profile != null -> ElevationChart(profile, route.distanceM, Modifier.fillMaxWidth())
                geometry is GeometryLoad.Loaded -> Text(stringResource(R.string.detail_no_elevation))
            }
        }

        Section(stringResource(R.string.detail_description)) {
            Text(route.description ?: stringResource(R.string.detail_no_description))
        }

        Section(stringResource(R.string.detail_rides)) {
            Text(stringResource(R.string.detail_no_rides))
        }

        val imported = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(route.importedAt))
        Text(
            if (route.sourceRef != null) {
                stringResource(R.string.detail_source, route.sourceRef, imported)
            } else {
                stringResource(R.string.detail_source_no_name, imported)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Stats(route: RouteEntity, waypointCount: Int?) {
    val rows = buildList {
        add(stringResource(R.string.detail_distance) to Formats.distance(route.distanceM))
        route.ascentM?.let { add(stringResource(R.string.detail_climb) to stringResource(R.string.climb_value, Formats.elevation(it))) }
        route.descentM?.let { add(stringResource(R.string.detail_descent) to stringResource(R.string.descent_value, Formats.elevation(it))) }
        route.maxEleM?.let { add(stringResource(R.string.detail_highest) to Formats.elevation(it)) }
        route.minEleM?.let { add(stringResource(R.string.detail_lowest) to Formats.elevation(it)) }
        add(
            stringResource(R.string.detail_shape) to
                stringResource(if (route.isLoop) R.string.detail_loop else R.string.detail_point_to_point),
        )
        if (waypointCount != null && waypointCount > 0) add(stringResource(R.string.detail_waypoints) to waypointCount.toString())
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            rows.forEach { (label, value) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        content()
    }
}

@Composable
private fun EditRouteDialog(route: RouteEntity, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(route.name) }
    var description by rememberSaveable { mutableStateOf(route.description.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.edit_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.edit_description)) },
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, description) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
