package io.github.lrosa2.singletrail.ui.route

import io.github.lrosa2.singletrail.nav.ElevationProfile

/** [count] elevations evenly spaced from the start to the finish, e.g. one per pixel column of a chart. */
fun profileSamples(profile: ElevationProfile, lengthMeters: Double, count: Int): DoubleArray {
    require(count >= 2) { "count must be at least 2" }
    return DoubleArray(count) { i -> profile.elevationAt(lengthMeters * i / (count - 1)) }
}
