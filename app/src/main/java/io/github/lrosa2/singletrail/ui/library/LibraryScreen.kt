package io.github.lrosa2.singletrail.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import io.github.lrosa2.singletrail.ui.collections.CollectionChecklistDialog
import io.github.lrosa2.singletrail.util.Sharing
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onOpenRoute: (String) -> Unit,
    onImport: () -> Unit,
    onManageCollections: () -> Unit,
    /** Overflow menu entries: label to action. */
    menu: List<Pair<String, () -> Unit>>,
    onBackUp: () -> Unit,
    /** "Near me" was picked and the position is unknown: find it and call [LibraryViewModel.setHere]. */
    onNeedLocation: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }
    var filtersOpen by rememberSaveable { mutableStateOf(false) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var selectionMenuOpen by remember { mutableStateOf(false) }
    val selecting = state.selected.isNotEmpty()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val shareTitle = stringResource(R.string.share_routes_title)
    val listState = rememberLazyListState()
    // A new order starts at the top. Otherwise the list keeps its first route in view wherever that
    // route moved to: picking "near me" showed routes 40 km away instead of the nearest ones.
    val order = listOf(state.sort, state.here, state.collection, state.filters)
    var shownOrder by remember { mutableStateOf(order) }
    LaunchedEffect(order) {
        if (order != shownOrder) {
            shownOrder = order
            listState.scrollToItem(0)
        }
    }
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
                        Box {
                            IconButton(onClick = { selectionMenuOpen = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
                            }
                            DropdownMenu(expanded = selectionMenuOpen, onDismissRequest = { selectionMenuOpen = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.action_add_to_collection)) }, onClick = {
                                    selectionMenuOpen = false
                                    adding = true
                                })
                                state.collectionName?.let { name ->
                                    DropdownMenuItem(text = { Text(stringResource(R.string.action_remove_from_collection, name)) }, onClick = {
                                        selectionMenuOpen = false
                                        viewModel.removeSelectedFromChosen()
                                    })
                                }
                            }
                        }
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
                    ChipRow(
                        state,
                        onSort = { sort ->
                            viewModel.setSort(sort)
                            if (sort == LibrarySort.NEAR_ME && state.here == null) onNeedLocation()
                        },
                        onCollection = viewModel::setCollection,
                        onOpenFilters = { filtersOpen = true },
                        onManageCollections = onManageCollections,
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
                        NoMatches(state.query, viewModel::showAll)
                    } else {
                        LazyColumn(
                            state = listState,
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
    if (filtersOpen) {
        FiltersSheet(state.filters, onChange = viewModel::setFilters, onDone = { filtersOpen = false })
    }
    if (adding) {
        CollectionChecklistDialog(
            title = stringResource(R.string.action_add_to_collection),
            collections = state.collections,
            initiallyChecked = emptySet(),
            onSave = { ids, newName ->
                adding = false
                viewModel.addSelected(ids, newName)
            },
            onDismiss = { adding = false },
        )
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
private fun ChipRow(
    state: LibraryState,
    onSort: (LibrarySort) -> Unit,
    onCollection: (CollectionChoice) -> Unit,
    onOpenFilters: () -> Unit,
    onManageCollections: () -> Unit,
) {
    val sortNames = mapOf(
        LibrarySort.NEAR_ME to stringResource(R.string.sort_near_me),
        LibrarySort.NAME to stringResource(R.string.sort_name),
        LibrarySort.STARS to stringResource(R.string.sort_stars),
        LibrarySort.LENGTH to stringResource(R.string.sort_length),
        LibrarySort.LAST_RIDDEN to stringResource(R.string.sort_last_ridden),
    )
    val all = stringResource(R.string.collection_all)
    val none = stringResource(R.string.collection_none)
    val manage = stringResource(R.string.action_manage_collections)
    val option = { name: String, count: Int -> "$name ($count)" }
    val collectionLabel = when (state.collection) {
        CollectionChoice.All -> all
        CollectionChoice.NotInAny -> none
        is CollectionChoice.One -> state.collectionName ?: all
    }
    val collectionOptions = buildList<Pair<String, () -> Unit>> {
        add(option(all, state.total) to { onCollection(CollectionChoice.All) })
        state.collections.forEach { c -> add(option(c.name, c.routes) to { onCollection(CollectionChoice.One(c.id)) }) }
        add(option(none, state.notInAny) to { onCollection(CollectionChoice.NotInAny) })
        add(manage to onManageCollections)
    }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ChipMenu(
            label = stringResource(R.string.sort_label, sortNames.getValue(state.sort)),
            options = LibrarySort.entries.map { sortNames.getValue(it) to { onSort(it) } },
        )
        ChipMenu(label = collectionLabel, options = collectionOptions)
        AssistChip(
            onClick = onOpenFilters,
            label = {
                Text(
                    if (state.filters.count == 0) stringResource(R.string.filters_chip)
                    else stringResource(R.string.filters_chip_count, state.filters.count),
                )
            },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
        )
    }
}

/** The Filters sheet (spec §8.1, D49): changes apply at once; Done closes it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FiltersSheet(filters: LibraryFilters, onChange: (LibraryFilters) -> Unit, onDone: () -> Unit) {
    val any = stringResource(R.string.filters_any)
    ModalBottomSheet(onDismissRequest = onDone) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.filters_title), style = MaterialTheme.typography.titleLarge)
            ChoiceRow(
                stringResource(R.string.filters_stars),
                listOf<Pair<Int?, String>>(null to any) + (1..5).map { it to stringResource(R.string.filters_stars_min, it) },
                filters.minStars,
            ) { onChange(filters.copy(minStars = it)) }
            ChoiceRow(
                stringResource(R.string.filters_length),
                listOf(
                    null to any,
                    LengthBand.UP_TO_20_KM to stringResource(R.string.filters_length_short),
                    LengthBand.KM_20_TO_40 to stringResource(R.string.filters_length_medium),
                    LengthBand.OVER_40_KM to stringResource(R.string.filters_length_long),
                ),
                filters.length,
            ) { onChange(filters.copy(length = it)) }
            ChoiceRow(
                stringResource(R.string.filters_climb),
                listOf(
                    null to any,
                    ClimbBand.UP_TO_500_M to stringResource(R.string.filters_climb_low),
                    ClimbBand.M_500_TO_1000 to stringResource(R.string.filters_climb_medium),
                    ClimbBand.OVER_1000_M to stringResource(R.string.filters_climb_high),
                ),
                filters.climb,
            ) { onChange(filters.copy(climb = it)) }
            CheckRow(stringResource(R.string.filters_not_ridden), filters.notRidden) { onChange(filters.copy(notRidden = it)) }
            CheckRow(stringResource(R.string.filters_loops), filters.loopsOnly) { onChange(filters.copy(loopsOnly = it)) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = { onChange(LibraryFilters()) }, enabled = filters.count > 0) {
                    Text(stringResource(R.string.action_clear))
                }
                Button(onClick = onDone) { Text(stringResource(R.string.action_done)) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceRow(title: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (value, label) ->
                FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(label) })
            }
        }
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(label, Modifier.padding(start = 8.dp))
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
private fun NoMatches(query: String, onShowAll: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (query.isNotBlank()) stringResource(R.string.library_no_search_matches, query.trim())
            else stringResource(R.string.library_no_matches_filters),
            style = MaterialTheme.typography.bodyLarge,
        )
        TextButton(onClick = onShowAll) { Text(stringResource(R.string.action_show_all)) }
    }
}
