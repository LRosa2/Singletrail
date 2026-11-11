package io.github.lrosa2.singletrail.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lrosa2.singletrail.backup.RouteSharing
import io.github.lrosa2.singletrail.data.db.RouteEntity
import io.github.lrosa2.singletrail.data.rides.RideRepository
import io.github.lrosa2.singletrail.data.routes.RouteRepository
import io.github.lrosa2.singletrail.data.settings.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class LibraryState(
    val routes: List<RouteEntity> = emptyList(),
    val sort: LibrarySort = LibrarySort.NAME,
    val minStars: Int? = null,
    /** Words to find in names and descriptions; not remembered across restarts. */
    val query: String = "",
    /** Routes in the library before filtering. */
    val total: Int = 0,
    val loaded: Boolean = false,
    /** Routes picked for sharing; selection mode while not empty. */
    val selected: Set<String> = emptySet(),
    /** Where the rider is, once "near me" has found out. */
    val here: Here? = null,
    val location: LocationLookup = LocationLookup.IDLE,
    val backupDue: BackupDue? = null,
)

enum class LocationLookup { IDLE, LOCATING, FAILED }

private data class View(
    val sort: LibrarySort,
    val minStars: Int?,
    val here: Here?,
    val selected: Set<String>,
    val location: LocationLookup = LocationLookup.IDLE,
    val query: String = "",
)

class LibraryViewModel(
    private val repository: RouteRepository,
    rides: RideRepository,
    private val settings: SettingsStore,
    private val appVersion: String,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val view = MutableStateFlow(View(LibrarySort.NAME, null, null, emptySet()))

    init {
        // Sort and filter are remembered across restarts (spec M4 follow-up).
        viewModelScope.launch {
            val (sort, minStars) = settings.libraryView()
            val saved = LibrarySort.entries.firstOrNull { it.name == sort } ?: LibrarySort.NAME
            // "Near me" needs a fresh location, so it doesn't survive a restart.
            view.update { it.copy(sort = if (saved == LibrarySort.NEAR_ME) LibrarySort.NAME else saved, minStars = minStars) }
        }
    }

    val state: StateFlow<LibraryState> =
        combine(repository.observeRoutes(), rides.observeRides(), settings.lastBackupAt, view) { routes, rideList, lastBackup, v ->
            LibraryState(
                routes = arrange(routes, v.sort, v.minStars, v.here, v.query),
                sort = v.sort,
                minStars = v.minStars,
                query = v.query,
                total = routes.size,
                loaded = true,
                selected = v.selected.intersect(routes.map { it.id }.toSet()),
                here = v.here,
                location = v.location,
                backupDue = BackupReminder.due(lastBackup, clock(), routes.size, rideList.size),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryState())

    fun setSort(value: LibrarySort) {
        view.update { it.copy(sort = value) }
        save()
    }

    fun setMinStars(value: Int?) {
        view.update { it.copy(minStars = value) }
        save()
    }

    fun setQuery(value: String) {
        view.update { it.copy(query = value) }
    }

    /** "Show all routes": no star filter and no search. */
    fun showAll() {
        view.update { it.copy(minStars = null, query = "") }
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

    /** Files for the selected routes, written into [dir]. */
    suspend fun shareSelected(dir: File): RouteSharing.Prepared? {
        val selected = state.value.routes.filter { it.id in state.value.selected }
        if (selected.isEmpty()) return null
        return RouteSharing.prepare(selected, repository, dir, appVersion, clock())
    }

    private fun save() {
        val v = view.value
        viewModelScope.launch { settings.saveLibraryView(v.sort.name, v.minStars) }
    }
}
