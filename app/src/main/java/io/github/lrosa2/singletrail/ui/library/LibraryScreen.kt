package io.github.lrosa2.singletrail.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lrosa2.singletrail.R
import io.github.lrosa2.singletrail.data.db.RouteEntity
import io.github.lrosa2.singletrail.ui.Formats
import io.github.lrosa2.singletrail.ui.StarRow
import io.github.lrosa2.singletrail.util.Sharing
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onOpenRoute: (String) -> Unit,
    onImport: () -> Unit,
    /** Overflow menu entries: label to action. */
    menu: List<Pair<String, () -> Unit>>,
    onBackUp: () -> Unit,
    /** "Near me" was picked and the position is unknown: find it and call [LibraryViewModel.setHere]. */
    onNeedLocation: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }
    val selecting = state.selected.isNotEmpty()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val shareTitle = stringResource(R.string.share_routes_title)
    Scaffold(
        topBar = {
            if (selecting) {
                TopAppBar(
                    title = { Text(pluralStringResource(R.plurals.library_selected, state.selected.size, state.selected.size)) },
                    navigationIcon = {
                        IconButton(onClick = viewModel::clearSelection) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close))
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            scope.launch {
                                viewModel.shareSelected(Sharing.freshDir(context))?.let {
                                    Sharing.share(context, it.files, it.mimeType, shareTitle)
                                    viewModel.clearSelection()
                                }
                            }
                        }) { Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.action_share)) }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text(stringResource(R.string.library_title)) },
                    actions = {
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                menu.forEach { (label, action) ->
                                    DropdownMenuItem(text = { Text(label) }, onClick = {
                                        menuOpen = false
                                        action()
                                    })
                                }
                            }
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            if (state.total > 0 && !selecting) {
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
                    SearchField(state.query, viewModel::setQuery)
                    FilterRow(
                        state.sort,
                        state.minStars,
                        onSort = { sort ->
                            viewModel.setSort(sort)
                            if (sort == LibrarySort.NEAR_ME && state.here == null) onNeedLocation()
                        },
                        onMinStars = viewModel::setMinStars,
                    )
                    if (state.sort == LibrarySort.NEAR_ME && state.here == null) {
                        when (state.location) {
                            LocationLookup.FAILED -> Row(
                                Modifier.padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    stringResource(R.string.library_no_location),
                                    Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                TextButton(onClick = onNeedLocation) { Text(stringResource(R.string.action_try_again)) }
                            }
                            else -> Text(
                                stringResource(R.string.library_locating),
                                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    state.backupDue?.let { BackupBanner(it, onBackUp) }
                    if (state.routes.isEmpty()) {
                        NoMatches(state.query, state.minStars ?: 0, viewModel::showAll)
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(state.routes, key = { it.id }) { route ->
                                RouteCard(
                                    route,
                                    selected = route.id in state.selected,
                                    away = state.here?.takeIf { state.sort == LibrarySort.NEAR_ME }?.let { distanceTo(route, it) },
                                    onClick = { if (selecting) viewModel.toggleSelected(route.id) else onOpenRoute(route.id) },
                                    onLongClick = { viewModel.toggleSelected(route.id) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RouteCard(route: RouteEntity, selected: Boolean, away: Double?, onClick: () -> Unit, onLongClick: () -> Unit) {
    val colors = if (selected) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    } else {
        CardDefaults.cardColors()
    }
    Card(colors = colors, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(route.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    routeSummary(route),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                away?.let {
                    Text(stringResource(R.string.library_away, Formats.distance(it)), style = MaterialTheme.typography.bodyMedium)
                }
                route.rating?.let { StarRow(it) }
            }
            if (selected) Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
        }
    }
}

@Composable
private fun BackupBanner(due: BackupDue, onBackUp: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (due) {
                    BackupDue.Never -> stringResource(R.string.backup_due_never)
                    is BackupDue.DaysAgo -> pluralStringResource(R.plurals.backup_due_days, due.days.toInt(), due.days.toInt())
                },
                Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onBackUp) { Text(stringResource(R.string.action_back_up_now)) }
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
        LibrarySort.NEAR_ME to stringResource(R.string.sort_near_me),
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

/** Words in route names and descriptions: case, accents and "ae/oe/ue" don't matter ([searchKey]). */
@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        placeholder = { Text(stringResource(R.string.library_search_hint)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQuery("") }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_clear_search))
                }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
    )
}

@Composable
private fun NoMatches(query: String, minStars: Int, onShowAll: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (query.isNotBlank()) stringResource(R.string.library_no_search_matches, query.trim()) else stringResource(R.string.library_no_matches, minStars),
            style = MaterialTheme.typography.bodyLarge,
        )
        TextButton(onClick = onShowAll) { Text(stringResource(R.string.action_show_all)) }
    }
}
