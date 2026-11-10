package io.github.lrosa2.singletrail.nav.engine

import io.github.lrosa2.singletrail.nav.Geo

/** The rider's course from a fix, when fast enough and accurate enough to trust (spec §5.9). */
internal fun Fix.trustedCourse(config: NavConfig): Double? {
    val speed = speedMps ?: return null
    val bearing = bearingDeg ?: return null
    if (speed < config.minCourseSpeedMps) return null
    if (bearingAccDeg != null && bearingAccDeg > config.maxCourseAccuracyDeg) return null
    return bearing.toDouble()
}

internal fun Fix.distanceTo(other: Fix): Double = Geo.distanceMeters(lat, lon, other.lat, other.lon)

/** Speed averaged over the last few seconds of fixes (spec §5.10). */
internal class SpeedSmoother(private val windowSeconds: Double) {
    private val times = ArrayDeque<Long>()
    private val speeds = ArrayDeque<Double>()

    fun add(fix: Fix, previous: Fix?) {
        val v = fix.speedMps?.toDouble() ?: previous?.let { p ->
            val dt = fix.elapsedNanos.secondsSince(p.elapsedNanos)
            if (dt > 0) fix.distanceTo(p) / dt else null
        } ?: return
        times.addLast(fix.elapsedNanos)
        speeds.addLast(v)
        while (fix.elapsedNanos.secondsSince(times.first()) > windowSeconds) {
            times.removeFirst()
            speeds.removeFirst()
        }
    }

    val current: Double get() = if (speeds.isEmpty()) 0.0 else speeds.sum() / speeds.size
}

/**
 * Heading source (spec §5.9, D30): GPS course while riding, the compass when slow or stopped, and
 * otherwise the last heading used, greyed out. With "compass off" the compass is never used.
 */
internal class HeadingFusion(private val config: NavConfig) {
    private var latestFixCourse: Double? = null
    private var compass: HeadingSample? = null
    private var lastUsed: Double? = null

    var current: Heading? = null
        private set

    fun onFix(fix: Fix) {
        latestFixCourse = fix.trustedCourse(config)
        resolve(fix.elapsedNanos)
    }

    fun onHeading(sample: HeadingSample) {
        compass = sample
        resolve(sample.elapsedNanos)
    }

    private fun resolve(now: Long) {
        val gps = latestFixCourse
        val c = compass?.takeIf { !config.compassOff && now.secondsSince(it.elapsedNanos) <= config.compassMaxAgeSeconds }
        current = when {
            gps != null -> Heading(gps, HeadingSource.GPS)
            c != null -> Heading(c.azimuthDeg.toDouble(), HeadingSource.COMPASS, needsCalibration = !c.reliable)
            else -> lastUsed?.let { Heading(it, HeadingSource.FROZEN) }
        }
        current?.takeIf { it.source != HeadingSource.FROZEN }?.let { lastUsed = it.degrees }
    }
}
