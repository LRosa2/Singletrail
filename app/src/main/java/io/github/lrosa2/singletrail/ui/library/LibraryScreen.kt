package io.github.lrosa2.singletrail.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lrosa2.singletrail.R
import io.github.lrosa2.singletrail.data.db.RouteEntity
import io.github.lrosa2.singletrail.ui.Formats
import io.github.lrosa2.singletrail.ui.StarRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onOpenRoute: (String) -> Unit,
    onImport: () -> Unit,
    onSettings: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.library_title)) },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.action_settings))
                    }
                },
            )
        },
        floatingActionButton = {
            if (state.total > 0) {
                ExtendedFloatingActionButton(
                    onClick = onImport,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.action_import)) },
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when {
                !state.loaded -> Unit
                state.total == 0 -> EmptyLibrary(onImport)
                else -> {
                    FilterRow(state.sort, state.minStars, viewModel::setSort, viewModel::setMinStars)
                    if (state.routes.isEmpty()) {
                        NoMatches(state.minStars ?: 0) { viewModel.setMinStars(null) }
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(state.routes, key = { it.id }) { route ->
                                RouteCard(route) { onOpenRoute(route.id) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RouteCard(route: RouteEntity, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(route.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                routeSummary(route),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            route.rating?.let { StarRow(it) }
        }
    }
}

@Composable
private fun routeSummary(route: RouteEntity): String {
    val parts = buildList {
        add(Formats.distance(route.distanceM))
        route.ascentM?.let { add(stringResource(R.string.climb_value, Formats.elevation(it))) }
        if (route.isLoop) add(stringResource(R.string.route_loop))
    }
    return parts.joinToString(stringResource(R.string.separator))
}

@Composable
private fun FilterRow(
    sort: LibrarySort,
    minStars: Int?,
    onSort: (LibrarySort) -> Unit,
    onMinStars: (Int?) -> Unit,
) {
    val sortNames = mapOf(
        LibrarySort.NAME to stringResource(R.string.sort_name),
        LibrarySort.STARS to stringResource(R.string.sort_stars),
        LibrarySort.LENGTH to stringResource(R.string.sort_length),
        LibrarySort.LAST_RIDDEN to stringResource(R.string.sort_last_ridden),
    )
    val starOptions = listOf<Pair<String, Int?>>(stringResource(R.string.stars_option_any) to null) +
        (1..5).map { stringResource(R.string.stars_option_min, it) to it }
    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ChipMenu(
            label = stringResource(R.string.sort_label, sortNames.getValue(sort)),
            options = LibrarySort.entries.map { sortNames.getValue(it) to { onSort(it) } },
        )
        ChipMenu(
            label = if (minStars == null) stringResource(R.string.stars_any) else stringResource(R.string.stars_min, minStars),
            options = starOptions.map { (text, value) -> text to { onMinStars(value) } },
        )
    }
}

@Composable
private fun ChipMenu(label: String, options: List<Pair<String, () -> Unit>>) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { open = true },
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (text, action) ->
                DropdownMenuItem(text = { Text(text) }, onClick = {
                    open = false
                    action()
                })
            }
        }
    }
}

@Composable
private fun EmptyLibrary(onImport: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.library_empty_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.library_empty_body), style = MaterialTheme.typography.bodyLarge)
        FilledTonalButton(onClick = onImport) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text(stringResource(R.string.action_import), Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun NoMatches(minStars: Int, onShowAll: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.library_no_matches, minStars), style = MaterialTheme.typography.bodyLarge)
        TextButton(onClick = onShowAll) { Text(stringResource(R.string.action_show_all)) }
    }
}
