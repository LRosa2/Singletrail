package io.github.lrosa2.singletrail.ui.rides

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lrosa2.singletrail.data.db.RideEntity
import io.github.lrosa2.singletrail.data.rides.RideRepository
import io.github.lrosa2.singletrail.data.routes.RouteRepository
import io.github.lrosa2.singletrail.gpx.GpxWriter
import io.github.lrosa2.singletrail.nav.RoutePosition
import io.github.lrosa2.singletrail.util.FileNames
import io.github.lrosa2.singletrail.util.thinTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RidesViewModel(rides: RideRepository) : ViewModel() {
    /** Null while loading. */
    val rides: StateFlow<List<RideEntity>?> = rides.observeRides()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

class RideDetailViewModel(
    private val rides: RideRepository,
    private val routes: RouteRepository,
    private val id: String,
) : ViewModel() {

    val ride: StateFlow<RideEntity?> = rides.observeRide(id)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val mutableTrack = MutableStateFlow<List<RoutePosition>?>(null)

    /** The recorded track, thinned for drawing; empty when nothing was recorded. */
    val track: StateFlow<List<RoutePosition>?> = mutableTrack.asStateFlow()

    private val mutableRouteExists = MutableStateFlow(false)
    val routeExists: StateFlow<Boolean> = mutableRouteExists.asStateFlow()

    init {
        viewModelScope.launch {
            val ride = rides.get(id) ?: return@launch
            mutableRouteExists.value = ride.routeId?.let { routes.route(it) } != null
            val points = rides.track(ride)
            mutableTrack.value = withContext(Dispatchers.Default) {
                points.map { RoutePosition(0.0, it.lat, it.lon) }.thinTo(OUTLINE_POINTS)
            }
        }
    }

    /** Writes the ride as GPX into [dir] for sharing; named after the route and the day. */
    suspend fun exportTo(dir: File): File? {
        val ride = rides.get(id) ?: return null
        val name = "${ride.routeNameSnapshot} ${day.format(Date(ride.startedAt))}"
        val doc = rides.gpx(ride, name)
        return withContext(Dispatchers.IO) {
            File(dir, FileNames.safe(name, "gpx", fallback = "ride")).apply {
                bufferedWriter().use { GpxWriter.write(doc, it) }
            }
        }
    }

    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            rides.delete(id)
            onDeleted()
        }
    }

    private companion object {
        const val OUTLINE_POINTS = 500
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
    }
}
