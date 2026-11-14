package io.github.lrosa2.singletrail.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lrosa2.singletrail.backup.RouteSharing
import io.github.lrosa2.singletrail.data.collections.CollectionRepository
import io.github.lrosa2.singletrail.data.db.CollectionCount
import io.github.lrosa2.singletrail.data.db.RouteEntity
import io.github.lrosa2.singletrail.data.rides.RideRepository
import io.github.lrosa2.singletrail.data.routes.RouteRepository
import io.github.lrosa2.singletrail.data.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class LibraryState(
    val routes: List<RouteEntity> = emptyList(),
    val sort: LibrarySort = LibrarySort.NAME,
    val filters: LibraryFilters = LibraryFilters(),
    /** The Collection chip's choice; a deleted collection has already fallen back to All. */
    val collection: CollectionChoice = CollectionChoice.All,
    /** Collections A–Z with their route counts. */
    val collections: List<CollectionCount> = emptyList(),
    /** Routes in no collection, for "Not in a collection (n)". */
    val notInAny: Int = 0,
    /** Words to find in names and descriptions; not remembered across restarts. */
    val query: String = "",
    /** Routes in the library before filtering. */
    val total: Int = 0,
    val loaded: Boolean = false,
    /** Routes picked for sharing or grouping; selection mode while not empty. */
    val selected: Set<String> = emptySet(),
    /** Where the rider is, once "near me" has found out. */
    val here: Here? = null,
    val location: LocationLookup = LocationLookup.IDLE,
    val backupDue: BackupDue? = null,
) {
    /** The chosen collection's name; null for All and Not in a collection. */
    val collectionName: String?
        get() = (collection as? CollectionChoice.One)?.let { chosen -> collections.firstOrNull { it.id == chosen.id }?.name }
}

enum class LocationLookup { IDLE, LOCATING, FAILED }

private data class View(
    val sort: LibrarySort = LibrarySort.NAME,
    val filters: LibraryFilters = LibraryFilters(),
    val collection: CollectionChoice = CollectionChoice.All,
    val here: Here? = null,
    val selected: Set<String> = emptySet(),
    val location: LocationLookup = LocationLookup.IDLE,
    val query: String = "",
)

private data class Groups(val counts: List<CollectionCount>, val members: Map<String, Set<String>>)

class LibraryViewModel(
    private val repository: RouteRepository,
    rides: RideRepository,
    private val collections: CollectionRepository,
    private val settings: SettingsStore,
    private val appVersion: String,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val view = MutableStateFlow(View())

    init {
        // Sort, filters and collection are remembered across restarts (spec §8.1).
        viewModelScope.launch {
            val saved = settings.libraryView()
            val sort = LibrarySort.entries.firstOrNull { it.name == saved.sort } ?: LibrarySort.NAME
            view.update {
                it.copy(
                    // "Near me" needs a fresh location, so it doesn't survive a restart.
                    sort = if (sort == LibrarySort.NEAR_ME) LibrarySort.NAME else sort,
                    filters = LibraryViewCodec.filters(saved),
                    collection = LibraryViewCodec.collection(saved.collection),
                )
            }
        }
    }

    private val groups = combine(collections.observeCounts(), collections.observeMembers(), ::Groups)

    val state: StateFlow<LibraryState> =
        combine(repository.observeRoutes(), rides.observeRides(), settings.lastBackupAt, view, groups) { routes, rideList, lastBackup, v, g ->
            val ids = routes.map { it.id }
            val choice = v.collection.resolve(g.counts.map { it.id }.toSet())
            LibraryState(
                routes = arrange(routes, v.sort, v.filters, v.here, v.query, choice.allowed(g.members, ids)),
                sort = v.sort,
                filters = v.filters,
                collection = choice,
                collections = g.counts,
                notInAny = CollectionChoice.NotInAny.allowed(g.members, ids)?.size ?: 0,
                query = v.query,
                total = routes.size,
                loaded = true,
                selected = v.selected.intersect(ids.toSet()),
                here = v.here,
                location = v.location,
                backupDue = BackupReminder.due(lastBackup, clock(), routes.size, rideList.size),
            )
        }
            .flowOn(Dispatchers.Default) // near me measures every route's outline: not on the main thread
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryState())

    fun setSort(value: LibrarySort) {
        view.update { it.copy(sort = value) }
        save()
    }

    fun setFilters(value: LibraryFilters) {
        view.update { it.copy(filters = value) }
        save()
    }

    fun setCollection(value: CollectionChoice) {
        view.update { it.copy(collection = value) }
        save()
    }

    fun setQuery(value: String) {
        view.update { it.copy(query = value) }
    }

    /** "Show all routes": every collection, no filters, no search. */
    fun showAll() {
        view.update { it.copy(collection = CollectionChoice.All, filters = LibraryFilters(), query = "") }
        save()
    }

    fun locating() {
        view.update { it.copy(location = LocationLookup.LOCATING) }
    }

    /** The answer to a "near me" lookup; null when no position could be found. */
    fun located(here: Here?) {
        view.update {
            if (here == null) it.copy(location = LocationLookup.FAILED) else it.copy(here = here, location = LocationLookup.IDLE)
        }
    }

    fun toggleSelected(id: String) {
        view.update { it.copy(selected = if (id in it.selected) it.selected - id else it.selected + id) }
    }

    fun clearSelection() {
        view.update { it.copy(selected = emptySet()) }
    }

    /** Adds the selected routes to [collectionIds] and, when given, a collection called [newName] (spec §8.1). */
    fun addSelected(collectionIds: Set<String>, newName: String?) {
        val routeIds = state.value.selected
        if (routeIds.isEmpty()) return
        viewModelScope.launch {
            val created = newName?.let { collections.named(it)?.id }
            (collectionIds + listOfNotNull(created)).forEach { collections.add(it, routeIds) }
            clearSelection()
        }
    }

    /** Takes the selected routes out of the chosen collection. */
    fun removeSelectedFromChosen() {
        val chosen = state.value.collection as? CollectionChoice.One ?: return
        val routeIds = state.value.selected
        viewModelScope.launch {
            collections.remove(chosen.id, routeIds)
            clearSelection()
        }
    }

    /** Files for the selected routes, written into [dir]. */
    suspend fun shareSelected(dir: File): RouteSharing.Prepared? {
        val selected = state.value.routes.filter { it.id in state.value.selected }
        if (selected.isEmpty()) return null
        return RouteSharing.prepare(selected, repository, dir, appVersion, clock(), state.value.collectionName)
    }

    private fun save() {
        val v = view.value
        viewModelScope.launch { settings.saveLibraryView(LibraryViewCodec.settings(v.sort, v.filters, v.collection)) }
    }
}
