package io.github.lrosa2.singletrail.ride

import android.content.Context
import androidx.core.content.ContextCompat
import io.github.lrosa2.singletrail.data.rides.RideRepository
import io.github.lrosa2.singletrail.data.routes.RouteRepository
import io.github.lrosa2.singletrail.nav.Maneuver
import io.github.lrosa2.singletrail.nav.RoutePosition
import io.github.lrosa2.singletrail.nav.engine.NavState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class RideFailure { ROUTE_MISSING, ROUTE_UNREADABLE, NO_PERMISSION, START_FAILED }

/** What the ride screen shows. */
data class RideUi(
    val rideId: String?,
    val routeName: String,
    val simulated: Boolean,
    /** Null while the route is being prepared. */
    val nav: NavState? = null,
    val maneuvers: List<Maneuver> = emptyList(),
    val warnings: Set<RideWarning> = emptySet(),
    val satellitesInView: Int? = null,
    val failure: RideFailure? = null,
    val routeId: String? = null,
    /** The route's stars, to rate it at the finish (spec §3, journey 5). */
    val rating: Int? = null,
    /** The course being ridden, thinned, for the route view (the map arrives with M7). */
    val outline: List<RoutePosition> = emptyList(),
    /** Low-power mode (spec §4.4): arrow view only, dimmer screen. */
    val lowPower: Boolean = false,
)

/**
 * The link between the screens and [RideService] (single process, spec §6): starts, resumes and
 * stops rides, and holds what the service publishes.
 */
class RideController(
    private val app: Context,
    private val rides: RideRepository,
    private val routes: RouteRepository,
    private val scope: CoroutineScope,
) {
    private val mutable = MutableStateFlow<RideUi?>(null)

    /** The current ride, or null when there is none. */
    val ride: StateFlow<RideUi?> = mutable.asStateFlow()

    @Volatile
    internal var session: RideSession? = null

    /** Call from a visible activity, with location permission granted (Android 14+ FGS rules). */
    fun start(routeId: String, routeName: String, reversed: Boolean, simulate: Boolean) {
        if (mutable.value != null) return
        mutable.value = RideUi(rideId = null, routeName = routeName, simulated = simulate, routeId = routeId)
        ContextCompat.startForegroundService(app, RideService.startIntent(app, routeId, reversed, simulate))
    }

    /**
     * Opening the app with a ride still in progress (after a crash, kill or reboot) goes straight back
     * to it (spec §6). Call from a visible activity. Returns whether a ride is in progress.
     */
    suspend fun resumeIfNeeded(hasLocationPermission: Boolean): Boolean {
        if (mutable.value?.failure == null && mutable.value != null) return true
        val ride = rides.inProgress() ?: return false
        if (!hasLocationPermission) {
            mutable.value = RideUi(ride.id, ride.routeNameSnapshot, simulated = false, failure = RideFailure.NO_PERMISSION)
            return true
        }
        mutable.value = RideUi(ride.id, ride.routeNameSnapshot, simulated = false, routeId = ride.routeId)
        ContextCompat.startForegroundService(app, RideService.resumeIntent(app))
        return true
    }

    fun stop() {
        app.startService(RideService.stopIntent(app))
    }

    fun acceptReverse() {
        session?.acceptReverse()
    }

    fun undoReverse() {
        session?.undoReverse()
    }

    fun setLowPower(on: Boolean) {
        update { it.copy(lowPower = on) }
    }

    /** Stars for the route being ridden; null clears them. */
    fun rate(stars: Int?) {
        val routeId = mutable.value?.routeId ?: return
        update { it.copy(rating = stars) }
        scope.launch { routes.setRating(routeId, stars) }
    }

    /** Clears a failed ride from the screen. */
    fun dismiss() {
        if (mutable.value?.failure != null) mutable.value = null
    }

    internal fun publish(ui: RideUi?) {
        mutable.value = ui
    }

    internal fun update(transform: (RideUi) -> RideUi) {
        mutable.update { it?.let(transform) }
    }

    internal fun fail(failure: RideFailure) {
        mutable.update { (it ?: RideUi(null, "", simulated = false)).copy(failure = failure) }
    }
}
