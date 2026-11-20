package io.github.lrosa2.singletrail.ui.route

import io.github.lrosa2.singletrail.data.db.RouteEntity

/**
 * The route as it is ridden the other way (spec §8.1, D53): the outline from the finish, so the start marker
 * and the arrows turn round, and the elevation profile backwards. Waypoints stay where they are.
 */
fun GeometryLoad.Loaded.turnedRound(): GeometryLoad.Loaded {
    val length = outline.lastOrNull()?.s ?: 0.0
    return copy(
        outline = outline.asReversed().map { it.copy(s = length - it.s) },
        profile = profile?.reversedArray(),
    )
}

/** Climb and descent in metres the way the rider will go: swapped when riding the other way. */
fun RouteEntity.climbAndDescent(otherWay: Boolean): Pair<Double?, Double?> =
    if (otherWay) descentM to ascentM else ascentM to descentM
