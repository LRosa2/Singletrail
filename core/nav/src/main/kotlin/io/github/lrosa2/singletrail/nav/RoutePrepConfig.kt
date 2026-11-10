package io.github.lrosa2.singletrail.nav

/** Route preparation thresholds (spec §5.2). */
data class RoutePrepConfig(
    /** Consecutive points closer than this to the last kept point are dropped. */
    val minPointSpacingMeters: Double = 1.0,
    /** Start and end closer than this make the route a loop. */
    val loopMaxGapMeters: Double = 50.0,
    /** Spacing of the resampled polyline used for turn detection and the elevation profile. */
    val sampleSpacingMeters: Double = 5.0,
    /** Cell size of the nearest-segment grid. */
    val gridCellMeters: Double = 50.0,
    /** Total width of the moving-average window applied to elevation before counting climb. */
    val elevationWindowMeters: Double = 60.0,
    /** Elevation swings smaller than this are noise, not climb or descent. */
    val climbThresholdMeters: Double = 5.0,
) {
    init {
        require(minPointSpacingMeters > 0) { "minPointSpacingMeters must be > 0" }
        require(loopMaxGapMeters >= 0) { "loopMaxGapMeters must be >= 0" }
        require(sampleSpacingMeters > 0) { "sampleSpacingMeters must be > 0" }
        require(gridCellMeters > 0) { "gridCellMeters must be > 0" }
        require(elevationWindowMeters >= 0) { "elevationWindowMeters must be >= 0" }
        require(climbThresholdMeters > 0) { "climbThresholdMeters must be > 0" }
    }

    companion object {
        val DEFAULT = RoutePrepConfig()
    }
}
