package io.github.lrosa2.singletrail.ui.library

import io.github.lrosa2.singletrail.data.db.RouteEntity
import io.github.lrosa2.singletrail.nav.Geo
import java.text.Collator
import java.util.Locale
import kotlin.math.min

/** Library sort orders (spec §8.1). */
enum class LibrarySort { NEAR_ME, NAME, STARS, LENGTH, LAST_RIDDEN }

/** Where the rider is, from one location request when "near me" is chosen. */
data class Here(val lat: Double, val lon: Double)

// Root collation sorts "Älggi" next to "alp" and ignores case, unlike plain string comparison.
private val collator: Collator = Collator.getInstance(Locale.ROOT)
private val byName = Comparator<RouteEntity> { a, b -> collator.compare(a.name, b.name) }

/**
 * The routes to show: those with at least [minStars] (when set), in [sort] order, ties by name.
 * "Near me" needs [here]; without it the routes stay in name order.
 */
fun arrange(routes: List<RouteEntity>, sort: LibrarySort, minStars: Int?, here: Here? = null): List<RouteEntity> {
    val visible = if (minStars == null) routes else routes.filter { (it.rating ?: 0) >= minStars }
    val order = when (sort) {
        LibrarySort.NEAR_ME -> if (here == null) byName else compareBy<RouteEntity> { distanceTo(it, here) }.then(byName)
        LibrarySort.NAME -> byName
        LibrarySort.STARS -> compareByDescending<RouteEntity> { it.rating ?: 0 }.then(byName)
        LibrarySort.LENGTH -> compareBy<RouteEntity> { it.distanceM }.then(byName)
        LibrarySort.LAST_RIDDEN -> compareByDescending<RouteEntity> { it.lastRiddenAt ?: Long.MIN_VALUE }.then(byName)
    }
    return visible.sortedWith(order)
}

/** Metres from [here] to the nearer end of the route: rides start from the nearest point anyway (§5.8). */
fun distanceTo(route: RouteEntity, here: Here): Double = min(
    Geo.distanceMeters(here.lat, here.lon, route.startLat, route.startLon),
    Geo.distanceMeters(here.lat, here.lon, route.endLat, route.endLon),
)

sealed interface BackupDue {
    data object Never : BackupDue
    data class DaysAgo(val days: Long) : BackupDue
}

/** The export reminder (spec §8.1, D21): "Last export 47 days ago". */
object BackupReminder {
    private const val DAY_MS = 24 * 60 * 60 * 1000L
    private const val REMIND_AFTER_DAYS = 30

    /**
     * Null when no reminder is due. Ride history is what can't be recovered any other way, so a
     * library without rides isn't nagged until it has been backed up once.
     */
    fun due(lastBackupAt: Long?, now: Long, routes: Int, rides: Int): BackupDue? {
        if (routes == 0 && rides == 0) return null
        if (lastBackupAt == null) return if (rides > 0) BackupDue.Never else null
        val days = (now - lastBackupAt) / DAY_MS
        return if (days > REMIND_AFTER_DAYS) BackupDue.DaysAgo(days) else null
    }
}
