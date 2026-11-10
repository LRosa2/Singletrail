package io.github.lrosa2.singletrail.ui.library

import io.github.lrosa2.singletrail.data.db.RouteEntity
import java.text.Collator
import java.util.Locale

/** Library sort orders (spec §8.1); "near me" arrives with location in M9. */
enum class LibrarySort { NAME, STARS, LENGTH, LAST_RIDDEN }

// Root collation sorts "Älggi" next to "alp" and ignores case, unlike plain string comparison.
private val collator: Collator = Collator.getInstance(Locale.ROOT)
private val byName = Comparator<RouteEntity> { a, b -> collator.compare(a.name, b.name) }

/** The routes to show: those with at least [minStars] (when set), in [sort] order, ties by name. */
fun arrange(routes: List<RouteEntity>, sort: LibrarySort, minStars: Int?): List<RouteEntity> {
    val visible = if (minStars == null) routes else routes.filter { (it.rating ?: 0) >= minStars }
    val order = when (sort) {
        LibrarySort.NAME -> byName
        LibrarySort.STARS -> compareByDescending<RouteEntity> { it.rating ?: 0 }.then(byName)
        LibrarySort.LENGTH -> compareBy<RouteEntity> { it.distanceM }.then(byName)
        LibrarySort.LAST_RIDDEN -> compareByDescending<RouteEntity> { it.lastRiddenAt ?: Long.MIN_VALUE }.then(byName)
    }
    return visible.sortedWith(order)
}
