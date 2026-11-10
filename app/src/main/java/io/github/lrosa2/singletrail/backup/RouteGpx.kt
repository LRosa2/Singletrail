package io.github.lrosa2.singletrail.backup

import io.github.lrosa2.singletrail.gpx.GpxDocument

/** Routes as GPX to share or back up (spec §8.5). */
object RouteGpx {
    /**
     * The stored copy with the route's current [name] and [description] (renaming only changes the
     * library row): track and waypoints only, never stars or rides.
     */
    fun forSharing(stored: GpxDocument, name: String, description: String?): GpxDocument {
        val track = stored.joinedTrack()?.copy(name = name, description = description)
        return GpxDocument(name, description, null, listOfNotNull(track), stored.waypoints)
    }
}
