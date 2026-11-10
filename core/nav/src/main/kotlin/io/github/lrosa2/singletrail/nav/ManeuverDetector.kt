package io.github.lrosa2.singletrail.nav

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign

/**
 * Geometry-only turn detection, v1 (spec §5.3).
 *
 * At every resampled point the bearing of the [ManeuverConfig.armMeters] before it is compared with
 * the bearing of the arm after it. Because each arm's bearing is the route's direction at its middle,
 * that difference is the turning done within about 20 m, so a 40° threshold picks out corners and
 * leaves long sweeping curves (radius above ~29 m) alone. Neighbouring points over the threshold
 * form one turn. Its angle is measured from the straight before it to the straight after it (out to
 * where the change drops below [ManeuverConfig.edgeDeg]), so a rounded bend counts once with its
 * full angle.
 */
object ManeuverDetector {

    fun detect(route: PreparedRoute, config: ManeuverConfig = ManeuverConfig.DEFAULT): List<Maneuver> =
        groupSwitchbacks(turns(route, config), config)

    private class Region(var first: Int, var last: Int, var peak: Int)

    private fun turns(route: PreparedRoute, config: ManeuverConfig): List<Maneuver> {
        val samples = route.samples
        val arm = config.armMeters
        val length = route.lengthMeters
        val delta = DoubleArray(samples.size) { i ->
            val s = samples[i].s
            if (s < arm || s > length - arm) {
                Double.NaN // Too close to an end to see both arms.
            } else {
                Geo.turnDeg(chordBearing(route, s - arm, s), chordBearing(route, s, s + arm))
            }
        }
        fun isCandidate(i: Int) = !delta[i].isNaN() && abs(delta[i]) >= config.slightDeg

        // Runs of neighbouring candidates turning the same way.
        val regions = ArrayList<Region>()
        var i = 0
        while (i < samples.size) {
            if (!isCandidate(i)) {
                i++
                continue
            }
            val region = Region(i, i, i)
            while (i + 1 < samples.size && isCandidate(i + 1) && sign(delta[i + 1]) == sign(delta[i])) {
                i++
                region.last = i
                if (abs(delta[i]) > abs(delta[region.peak])) region.peak = i
            }
            regions += region
            i++
        }

        // Measure each turn out to where the turning fades, so a rounded bend gets its full angle.
        fun isEdge(k: Int, sign: Double) = !delta[k].isNaN() && sign(delta[k]) == sign && abs(delta[k]) >= config.edgeDeg
        regions.forEachIndexed { r, region ->
            val sign = sign(delta[region.peak])
            val floor = if (r > 0) regions[r - 1].last + 1 else 0
            val ceiling = if (r < regions.lastIndex) regions[r + 1].first - 1 else samples.lastIndex
            while (region.first > floor && isEdge(region.first - 1, sign)) region.first--
            while (region.last < ceiling && isEdge(region.last + 1, sign)) region.last++
        }

        // Non-maximum suppression: same-direction runs whose peaks are close are one turn.
        val merged = ArrayList<Region>()
        for (region in regions) {
            val prev = merged.lastOrNull()
            if (prev != null &&
                sign(delta[prev.peak]) == sign(delta[region.peak]) &&
                samples[region.peak].s - samples[prev.peak].s <= config.mergeMeters
            ) {
                prev.last = region.last
                if (abs(delta[region.peak]) > abs(delta[prev.peak])) prev.peak = region.peak
            } else {
                merged += region
            }
        }

        val step = route.config.sampleSpacingMeters
        return merged.mapNotNull { region ->
            val angle = totalTurn(route, samples[region.first].s - arm, samples[region.last].s, arm, step)
            val kind = classify(abs(angle), config) ?: return@mapNotNull null
            val at = samples[region.peak]
            Maneuver(at.s, at.lat, at.lon, kind, angle)
        }
    }

    /**
     * Direction change from the arm ending at `from + arm` to the arm starting at [to], summed in small
     * steps so it keeps its sign and can pass 180° (a hairpin) without wrapping.
     */
    private fun totalTurn(route: PreparedRoute, from: Double, to: Double, arm: Double, step: Double): Double {
        var x = from
        var prev = chordBearing(route, x, x + arm)
        var total = 0.0
        while (x < to) {
            x = min(x + step, to)
            val b = chordBearing(route, x, x + arm)
            total += Geo.turnDeg(prev, b)
            prev = b
        }
        return total
    }

    private fun chordBearing(route: PreparedRoute, from: Double, to: Double): Double =
        bearing(route.positionAt(from), route.positionAt(to))

    private fun classify(angle: Double, config: ManeuverConfig): ManeuverKind? = when {
        angle < config.slightDeg -> null
        angle < config.turnDeg -> ManeuverKind.SLIGHT
        angle < config.sharpDeg -> ManeuverKind.TURN
        angle <= config.uTurnDeg -> ManeuverKind.SHARP
        else -> ManeuverKind.U_TURN
    }

    /**
     * Three or more sharp turns or U-turns alternating sides, the first and last no more than
     * [ManeuverConfig.switchbackMaxSpanMeters] apart, become one [ManeuverKind.SWITCHBACKS]
     * maneuver. A longer stack becomes several groups.
     */
    private fun groupSwitchbacks(turns: List<Maneuver>, config: ManeuverConfig): List<Maneuver> {
        fun isHairpin(m: Maneuver) = m.kind == ManeuverKind.SHARP || m.kind == ManeuverKind.U_TURN
        val out = ArrayList<Maneuver>(turns.size)
        var i = 0
        while (i < turns.size) {
            val first = turns[i]
            if (isHairpin(first)) {
                var j = i
                while (j + 1 < turns.size) {
                    val next = turns[j + 1]
                    if (!isHairpin(next) || next.isRight == turns[j].isRight) break
                    if (next.s - first.s > config.switchbackMaxSpanMeters) break
                    j++
                }
                if (j - i + 1 >= config.switchbackMinTurns) {
                    out += first.copy(kind = ManeuverKind.SWITCHBACKS, parts = turns.subList(i, j + 1).toList())
                    i = j + 1
                    continue
                }
            }
            out += first
            i++
        }
        return out
    }
}
