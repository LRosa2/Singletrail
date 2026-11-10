package io.github.lrosa2.singletrail.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lrosa2.singletrail.data.db.RouteEntity
import io.github.lrosa2.singletrail.data.routes.RouteRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class LibraryState(
    val routes: List<RouteEntity> = emptyList(),
    val sort: LibrarySort = LibrarySort.NAME,
    val minStars: Int? = null,
    /** Routes in the library before filtering. */
    val total: Int = 0,
    val loaded: Boolean = false,
)

class LibraryViewModel(repository: RouteRepository) : ViewModel() {
    private val sort = MutableStateFlow(LibrarySort.NAME)
    private val minStars = MutableStateFlow<Int?>(null)

    val state: StateFlow<LibraryState> =
        combine(repository.observeRoutes(), sort, minStars) { routes, sort, minStars ->
            LibraryState(arrange(routes, sort, minStars), sort, minStars, routes.size, loaded = true)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryState())

    fun setSort(value: LibrarySort) {
        sort.value = value
    }

    fun setMinStars(value: Int?) {
        minStars.value = value
    }
}
