package io.github.lrosa2.singletrail.ui.route

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lrosa2.singletrail.data.db.RouteEntity
import io.github.lrosa2.singletrail.data.db.WaypointEntity
import io.github.lrosa2.singletrail.data.routes.RouteRepository
import io.github.lrosa2.singletrail.nav.RoutePosition
import io.github.lrosa2.singletrail.util.thinTo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface RouteLoad {
    data object Loading : RouteLoad
    data object Missing : RouteLoad
    data class Loaded(val route: RouteEntity) : RouteLoad
}

/** What is drawn from the stored GPX copy: the outline, the elevation profile and the waypoints. */
sealed interface GeometryLoad {
    data object Loading : GeometryLoad
    data object Failed : GeometryLoad
    data class Loaded(
        val outline: List<RoutePosition>,
        /** Elevations evenly spaced from start to finish, or null without elevation data. */
        val profile: DoubleArray?,
        val waypoints: List<WaypointEntity>,
    ) : GeometryLoad
}

class RouteDetailViewModel(private val repository: RouteRepository, private val id: String) : ViewModel() {

    val route: StateFlow<RouteLoad> = repository.observeRoute(id)
        .map { if (it == null) RouteLoad.Missing else RouteLoad.Loaded(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RouteLoad.Loading)

    /** Reloaded only when the geometry changes (an "Update existing route" import), not on a rename or new stars. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val geometry: StateFlow<GeometryLoad> = repository.observeRoute(id)
        .map { it?.geometryHash }
        .distinctUntilChanged()
        .mapLatest { hash -> if (hash == null) GeometryLoad.Failed else loadGeometry() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GeometryLoad.Loading)

    fun setRating(rating: Int?) {
        viewModelScope.launch { repository.setRating(id, rating) }
    }

    fun edit(name: String, description: String) {
        viewModelScope.launch { repository.edit(id, name, description) }
    }

    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            repository.delete(id)
            onDeleted()
        }
    }

    private suspend fun loadGeometry(): GeometryLoad =
        try {
            val prepared = repository.prepared(id)
            val waypoints = repository.waypoints(id)
            withContext(Dispatchers.Default) {
                GeometryLoad.Loaded(
                    outline = prepared.samples.thinTo(OUTLINE_POINTS),
                    profile = prepared.elevation?.let { profileSamples(it, prepared.lengthMeters, PROFILE_POINTS) },
                    waypoints = waypoints,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            GeometryLoad.Failed
        }

    private companion object {
        const val OUTLINE_POINTS = 500
        const val PROFILE_POINTS = 200
    }
}
