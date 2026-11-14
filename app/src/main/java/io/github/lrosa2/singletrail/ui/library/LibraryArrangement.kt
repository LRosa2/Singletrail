package io.github.lrosa2.singletrail.ui.library

import io.github.lrosa2.singletrail.data.db.RouteEntity
import io.github.lrosa2.singletrail.nav.Geo
import java.text.Collator
import java.text.Normalizer
import java.util.Locale
import kotlin.math.min

/** Library sort orders (spec §8.1). */
enum class LibrarySort { NEAR_ME, NAME, STARS, LENGTH, LAST_RIDDEN }

/** Where the rider is, from one location request when "near me" is chosen. */
data class Here(val lat: Double, val lon: Double)

// Root collation sorts "Älggi" next to "alp" and ignores case, unlike plain string comparison.
private val collator: Collator = Collator.getInstance(Locale.ROOT)
private val byName = Comparator<RouteEntity> { a, b -> collator.compare(a.name, b.name) }

/** Length bands of the Filters sheet (spec §8.1, D49). */
enum class LengthBand {
    UP_TO_20_KM, KM_20_TO_40, OVER_40_KM;

    fun contains(meters: Double): Boolean = when (this) {
        UP_TO_20_KM -> meters <= 20_000.0
        KM_20_TO_40 -> meters > 20_000.0 && meters <= 40_000.0
        OVER_40_KM -> meters > 40_000.0
    }
}

/** Climb bands of the Filters sheet (spec §8.1, D49). */
enum class ClimbBand {
    UP_TO_500_M, M_500_TO_1000, OVER_1000_M;

    fun contains(meters: Double): Boolean = when (this) {
        UP_TO_500_M -> meters <= 500.0
        M_500_TO_1000 -> meters > 500.0 && meters <= 1000.0
        OVER_1000_M -> meters > 1000.0
    }
}

/** The Filters sheet (spec §8.1, D49); every filter that is set must match. */
data class LibraryFilters(
    val minStars: Int? = null,
    val length: LengthBand? = null,
    val climb: ClimbBand? = null,
    val notRidden: Boolean = false,
    val loopsOnly: Boolean = false,
) {
    /** How many filters are on, for "Filters (2)". */
    val count: Int get() = listOf(minStars != null, length != null, climb != null, notRidden, loopsOnly).count { it }

    fun matches(route: RouteEntity): Boolean =
        (minStars == null || (route.rating ?: 0) >= minStars) &&
            (length == null || length.contains(route.distanceM)) &&
            // A route without elevation has no known climb, so a climb filter leaves it out.
            (climb == null || route.ascentM?.let(climb::contains) == true) &&
            (!notRidden || route.lastRiddenAt == null) &&
            (!loopsOnly || route.isLoop)
}

/** What the Collection chip shows (spec §8.1, D48). */
sealed interface CollectionChoice {
    data object All : CollectionChoice

    /** Routes that are in no collection. */
    data object NotInAny : CollectionChoice

    data class One(val id: String) : CollectionChoice
}

/** The route ids this choice lets through, from route ids per collection id; null lets everything through. */
fun CollectionChoice.allowed(members: Map<String, Set<String>>, routeIds: Collection<String>): Set<String>? = when (this) {
    CollectionChoice.All -> null
    CollectionChoice.NotInAny -> {
        val grouped = members.values.flatten().toSet()
        routeIds.filterNot { it in grouped }.toSet()
    }
    is CollectionChoice.One -> members[id].orEmpty()
}

/** This choice, given the collection ids that exist: a deleted collection falls back to All (spec §8.1). */
fun CollectionChoice.resolve(existing: Set<String>): CollectionChoice =
    if (this is CollectionChoice.One && id !in existing) CollectionChoice.All else this

/**
 * The routes to show: those [allowed] by the chosen collection (null: all), matching [filters] and every
 * word of [query] in their name or description, in [sort] order, ties by name. "Near me" needs [here];
 * without it the routes stay in name order.
 */
fun arrange(
    routes: List<RouteEntity>,
    sort: LibrarySort,
    filters: LibraryFilters = LibraryFilters(),
    here: Here? = null,
    query: String = "",
    allowed: Set<String>? = null,
): List<RouteEntity> {
    val words = query.split(' ').map(::searchKey).filter { it.isNotBlank() }
    val visible = routes.filter { route ->
        (allowed == null || route.id in allowed) &&
            filters.matches(route) &&
            (words.isEmpty() || searchKey(route.name + " " + route.description.orEmpty()).let { text -> words.all { it in text } })
    }
    val order = when (sort) {
        LibrarySort.NEAR_ME -> if (here == null) {
            byName
        } else {
            // Measured once per route: a comparator would measure each route many times.
            val away = visible.associate { it.id to distanceTo(it, here) }
            compareBy<RouteEntity> { away.getValue(it.id) }.then(byName)
        }
        LibrarySort.NAME -> byName
        LibrarySort.STARS -> compareByDescending<RouteEntity> { it.rating ?: 0 }.then(byName)
        LibrarySort.LENGTH -> compareBy<RouteEntity> { it.distanceM }.then(byName)
        LibrarySort.LAST_RIDDEN -> compareByDescending<RouteEntity> { it.lastRiddenAt ?: Long.MIN_VALUE }.then(byName)
    }
    return visible.sortedWith(order)
}

private val marks = Regex("\\p{Mn}+")

/** Text folded for search: lower case, no accents, and "ae"/"oe"/"ue" as "a"/"o"/"u", so "hoernli" finds "Hörnli". */
internal fun searchKey(text: String): String =
    marks.replace(Normalizer.normalize(text.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD), "")
        .replace("ae", "a").replace("oe", "o").replace("ue", "u")

/**
 * Metres in a straight line from [here] to the nearest point of the route, where a ride would join it
 * (rides start from the nearest point, D14). A route whose outline isn't filled in yet is measured to
 * its nearer end.
 */
fun distanceTo(route: RouteEntity, here: Here): Double = route.outline?.distanceMeters(here.lat, here.lon) ?: min(
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
