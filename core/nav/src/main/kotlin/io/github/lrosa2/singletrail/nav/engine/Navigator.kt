package io.github.lrosa2.singletrail.nav.engine

import io.github.lrosa2.singletrail.nav.Geo
import io.github.lrosa2.singletrail.nav.Maneuver
import io.github.lrosa2.singletrail.nav.ManeuverDetector
import io.github.lrosa2.singletrail.nav.ManeuverKind
import io.github.lrosa2.singletrail.nav.NavRoute
import io.github.lrosa2.singletrail.nav.PreparedRoute
import io.github.lrosa2.singletrail.nav.RoutePosition
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * The navigation engine (spec §5): a deterministic function of the route, the config and the stream
 * of fixes, compass samples, clock ticks and prompt answers. Not thread-safe; the ride service
 * feeds it from one thread.
 */
class Navigator(
    private val navRoute: NavRoute,
    val config: NavConfig = NavConfig.DEFAULT,
    mode: RideMode = RideMode(),
) {
    private var reversed = mode.reversed

    /** Where a loop was joined, on the unrotated route of the current direction. */
    private var loopStart: Double? = null
    private var course = buildCourse()

    /** The geometry [NavState.progressM] refers to: the riding direction, rotated to the join point on loops. */
    val route: PreparedRoute get() = course.route

    /** Maneuvers along [route]; [UpcomingManeuver.index] and turn events index into this list. */
    val maneuvers: List<Maneuver> get() = course.maneuvers

    private var phase = RidePhase.WAITING_GPS
    private var lost = false
    private var poor = false
    private var lastFix: Fix? = null
    private var lastGood: Fix? = null

    /** Restored from a checkpoint: the next good fix finds the rider anywhere on the route. */
    private var relocating = false

    private var progress = 0.0
    private var highWater = 0.0
    private var rawS = 0.0
    private var lastMatchNanos = 0L
    private var distanceToRoute: Double? = null
    private var nextBarrier = 0

    private val approached = HashSet<Int>()
    private val announced = HashSet<Int>()
    private val skipped = ArrayList<RouteRange>()

    private var offCount = 0
    private var offFirstNanos = 0L
    private var offExitS = 0.0
    private var exitS = 0.0
    private var offDistance = 0.0
    private var backCount = 0
    private var aheadLocked = false
    private var approachS: Double? = null
    private var approachSince = 0L
    private var approachStartDist = 0.0
    private var approachMinDist = 0.0
    private var target: NavTarget? = null

    private var reverseAnchor: Double? = null
    private var reverseSuppressUntil = 0.0
    private var reverseSince: Long? = null

    private val speed = SpeedSmoother(config.speedWindowSeconds)
    private val heading = HeadingFusion(config)
    private var odometer = 0.0
    private var movingNanos = 0L

    private val threshold get() = config.offRouteMeters

    val state: NavState get() = buildState()

    fun onFix(fix: Fix, nowNanos: Long = fix.elapsedNanos): NavUpdate {
        val events = ArrayList<NavEvent>()
        val previous = lastFix
        if (previous != null && fix.elapsedNanos <= previous.elapsedNanos) return NavUpdate(state, events)
        if (lost) {
            lost = false
            events += NavEvent.SignalBack
        }
        lastFix = fix
        heading.onFix(fix)
        val good = fix.accuracyM == null || fix.accuracyM <= config.maxAccuracyMeters
        poor = !good
        speed.add(fix, lastGood)
        if (phase == RidePhase.FINISHED) return NavUpdate(state, events)
        if (phase == RidePhase.WAITING_GPS) phase = RidePhase.ACQUIRING
        if (good) countMovement(fix)

        when (phase) {
            RidePhase.ACQUIRING -> if (good) acquire(fix, events)
            RidePhase.ON_ROUTE, RidePhase.REVERSE_PENDING -> if (good) {
                if (relocating) relocate(fix, events) else follow(fix, events)
            }
            RidePhase.OFF_ROUTE -> if (good) offRoute(fix, events)
            RidePhase.WAITING_GPS, RidePhase.FINISHED -> Unit
        }
        if (good) lastGood = fix
        checkReverseDeadline(max(nowNanos, fix.elapsedNanos), events)
        if (phase == RidePhase.ON_ROUTE && good) alerts(extrapolated(nowNanos), events)
        updateTarget(fix, good)
        return NavUpdate(state, events)
    }

    fun onHeading(sample: HeadingSample): NavUpdate {
        heading.onHeading(sample)
        return NavUpdate(state, emptyList())
    }

    /** Call about once a second: detects lost GPS, ends the reverse prompt, extrapolates alerts between fixes. */
    fun onTick(nowNanos: Long): NavUpdate {
        val events = ArrayList<NavEvent>()
        val last = lastFix
        if (last != null && !lost && phase != RidePhase.FINISHED && nowNanos.secondsSince(last.elapsedNanos) >= config.noGpsSeconds) {
            lost = true
            events += NavEvent.SignalLost
        }
        checkReverseDeadline(nowNanos, events)
        if (phase == RidePhase.ON_ROUTE && !poor && !relocating && lastGood != null) alerts(extrapolated(nowNanos), events)
        return NavUpdate(state, events)
    }

    /** The rider confirmed the reverse prompt (or pressed the volume key for it). */
    fun acceptReverse(@Suppress("UNUSED_PARAMETER") nowNanos: Long): NavUpdate {
        val events = ArrayList<NavEvent>()
        if (phase == RidePhase.REVERSE_PENDING) switchDirection(events)
        return NavUpdate(state, events)
    }

    /** The rider pressed Undo: keep the direction and don't ask again for a while. */
    fun undoReverse(@Suppress("UNUSED_PARAMETER") nowNanos: Long): NavUpdate {
        val events = ArrayList<NavEvent>()
        if (phase == RidePhase.REVERSE_PENDING) {
            phase = RidePhase.ON_ROUTE
            reverseSince = null
            reverseAnchor = null
            reverseSuppressUntil = odometer + config.reverseUndoSuppressMeters
            events += NavEvent.ReverseUndone
        }
        return NavUpdate(state, events)
    }

    // ---- Phases ----

    private fun acquire(fix: Fix, events: MutableList<NavEvent>) {
        val c = course.bestNear(fix.lat, fix.lon, threshold) { _, d, b -> d + headingPenalty(fix, b) } ?: return
        if (course.route.isLoop) {
            // Start from the nearest point: the loop now runs once round from here (spec §5.8).
            loopStart = c.s
            course = buildCourse()
            moveTo(0.0, fix)
        } else {
            moveTo(c.s, fix)
        }
        distanceToRoute = c.distance
        markPassedBefore(progress)
        phase = RidePhase.ON_ROUTE
        events += NavEvent.Joined
    }

    private fun follow(fix: Fix, events: MutableList<NavEvent>) {
        val v = speed.current
        val dt = fix.elapsedNanos.secondsSince(lastMatchNanos).coerceIn(0.0, 10.0)
        val base = if (phase == RidePhase.REVERSE_PENDING) min(progress, rawS) else progress
        var ahead = progress + max(config.windowAheadMinMeters, config.windowAheadSeconds * v)
        if (nextBarrier < course.barriers.size) ahead = min(ahead, course.barriers[nextBarrier] + config.barrierPassMeters)
        val expected = rawS
        val slack = config.jumpSlackMeters + v * dt
        val c = course.bestInRange(fix.lat, fix.lon, base - config.windowBehindMeters, ahead) { s, d, b ->
            d + headingPenalty(fix, b) + config.jumpPenaltyPerMeter * max(0.0, abs(s - expected) - slack)
        } ?: return
        distanceToRoute = c.distance

        if (c.distance <= threshold) {
            offCount = 0
            rawS = c.s
            lastMatchNanos = fix.elapsedNanos
            progress = max(rawS, highWater - config.jitterBackMeters).coerceIn(0.0, course.length)
            highWater = max(highWater, progress)
            passBarriers(fix, c)
            if (phase == RidePhase.ON_ROUTE) checkReverse(fix, c, events)
            if (phase == RidePhase.ON_ROUTE) checkFinish(fix, events)
        } else if (phase == RidePhase.ON_ROUTE) {
            if (offCount == 0) {
                offFirstNanos = fix.elapsedNanos
                offExitS = progress
            }
            offCount++
            if (offCount >= config.offRouteMinFixes && fix.elapsedNanos.secondsSince(offFirstNanos) >= config.offRouteMinSeconds) {
                enterOffRoute(offExitS, 0.0, events)
            }
        }
    }

    /** First good fix after a restore: find the rider anywhere along the route, preferring ahead. */
    private fun relocate(fix: Fix, events: MutableList<NavEvent>) {
        relocating = false
        val from = progress
        val c = course.bestNear(fix.lat, fix.lon, threshold) { s, d, b ->
            d + headingPenalty(fix, b) + if (s < from - config.jitterBackMeters) BEHIND_PENALTY_M else 0.0
        }
        if (c == null) {
            // Away from the route: off route from where the ride was saved, as after a long detour.
            enterOffRoute(from, config.shortDetourMeters, events)
            distanceToRoute = course.nearestAnywhere(fix.lat, fix.lon).distance
            return
        }
        val s = c.s.coerceIn(0.0, course.length)
        if (s > from) markPassedBefore(s) else rearmAfter(s)
        moveTo(s, fix)
        distanceToRoute = c.distance
        checkFinish(fix, events)
    }

    private fun offRoute(fix: Fix, events: MutableList<NavEvent>) {
        lastGood?.let { last -> movement(last, fix)?.let { offDistance += it.distance } }
        val long = offDistance >= config.shortDetourMeters
        val radius = threshold - config.backOnRouteMarginMeters
        val c = if (!long) {
            val ahead = max(config.windowAheadMinMeters, config.windowAheadSeconds * speed.current)
            course.bestInRange(fix.lat, fix.lon, exitS - config.windowBehindMeters, exitS + ahead) { _, d, b -> d + headingPenalty(fix, b) }
        } else {
            course.bestNear(fix.lat, fix.lon, radius) { s, d, b ->
                d + headingPenalty(fix, b) + if (s < exitS) BEHIND_PENALTY_M else 0.0
            }
        }
        distanceToRoute = c?.distance ?: course.nearestAnywhere(fix.lat, fix.lon).distance
        val courseOk = c != null && fix.trustedCourse(config).let { it == null || abs(Geo.turnDeg(it, c.bearing)) <= config.rejoinMaxCourseDeg }
        if (c != null && c.distance < radius && courseOk) {
            backCount++
            if (backCount >= config.backOnRouteFixes) rejoin(c, fix, events)
        } else {
            backCount = 0
        }
    }

    private fun enterOffRoute(exit: Double, alreadyOff: Double, events: MutableList<NavEvent>) {
        phase = RidePhase.OFF_ROUTE
        exitS = exit
        offDistance = alreadyOff
        offCount = 0
        backCount = 0
        aheadLocked = false
        approachS = null
        reverseAnchor = null
        events += NavEvent.OffRoute
    }

    private fun rejoin(c: Course.Candidate, fix: Fix, events: MutableList<NavEvent>) {
        val s = c.s.coerceIn(0.0, course.length)
        if (s > exitS) {
            if (s - exitS > config.jitterBackMeters) skipped += RouteRange(exitS, s)
            // Turns in the skipped section, and one right at the rejoin point, are dropped.
            maneuvers.forEachIndexed { i, m ->
                if (m.s > exitS && m.s <= s + 1.0) {
                    approached += i
                    announced += i
                }
            }
        } else {
            rearmAfter(s)
        }
        moveTo(s, fix)
        distanceToRoute = c.distance
        phase = RidePhase.ON_ROUTE
        backCount = 0
        target = null
        events += NavEvent.BackOnRoute
    }

    private fun checkReverse(fix: Fix, c: Course.Candidate, events: MutableList<NavEvent>) {
        if (odometer < reverseSuppressUntil) {
            reverseAnchor = null
            return
        }
        val crs = fix.trustedCourse(config)
        val opposite = crs != null && speed.current > config.reverseMinSpeedMps &&
            abs(Geo.turnDeg(crs, c.bearing)) > config.reverseCourseDeg
        val anchor = reverseAnchor
        when {
            !opposite -> reverseAnchor = null
            anchor == null || rawS > anchor + REVERSE_RESET_M -> reverseAnchor = rawS
            anchor - rawS >= config.reverseMinMeters -> {
                phase = RidePhase.REVERSE_PENDING
                reverseSince = fix.elapsedNanos
                reverseAnchor = null
                events += NavEvent.ReversePrompt
            }
        }
    }

    private fun checkReverseDeadline(now: Long, events: MutableList<NavEvent>) {
        val since = reverseSince ?: return
        if (phase == RidePhase.REVERSE_PENDING && now.secondsSince(since) >= config.reversePromptSeconds) switchDirection(events)
    }

    private fun switchDirection(events: MutableList<NavEvent>) {
        val length = course.length
        val newRaw = if (course.closed) {
            loopStart = navRoute.forward.lengthMeters - loopStart!!
            ((-rawS % length) + length) % length
        } else {
            length - rawS
        }
        reversed = !reversed
        course = buildCourse()
        val s = newRaw.coerceIn(0.0, course.length)
        lastMatchNanos = lastGood?.elapsedNanos ?: lastMatchNanos
        progress = s
        highWater = s
        rawS = s
        nextBarrier = course.firstBarrierAfter(s)
        approached.clear()
        announced.clear()
        skipped.clear()
        markPassedBefore(s)
        reverseSince = null
        reverseAnchor = null
        phase = RidePhase.ON_ROUTE
        events += NavEvent.Reversed
    }

    private fun checkFinish(fix: Fix, events: MutableList<NavEvent>) {
        if (progress < config.finishMinFraction * course.length) return
        val end = course.route.positionAt(course.length)
        if (Geo.distanceMeters(fix.lat, fix.lon, end.lat, end.lon) <= config.finishRadiusMeters) {
            phase = RidePhase.FINISHED
            target = null
            events += NavEvent.Finished
        }
    }

    // ---- Progress, barriers, alerts ----

    private fun moveTo(s: Double, fix: Fix) {
        progress = s
        highWater = s
        rawS = s
        lastMatchNanos = fix.elapsedNanos
        nextBarrier = course.firstBarrierAfter(s)
        offCount = 0
        reverseAnchor = null
    }

    /**
     * A barrier is ridden once progress is past it and the rider's course follows the route there;
     * someone carrying straight on past a hairpin hasn't ridden it, so progress stays capped (D29).
     */
    private fun passBarriers(fix: Fix, c: Course.Candidate) {
        val crs = fix.trustedCourse(config)
        while (nextBarrier < course.barriers.size && progress >= course.barriers[nextBarrier] + config.barrierRiddenMeters) {
            if (crs != null && abs(Geo.turnDeg(crs, c.bearing)) > config.rejoinMaxCourseDeg) break
            nextBarrier++
        }
    }

    private fun markPassedBefore(s: Double) {
        maneuvers.forEachIndexed { i, m ->
            if (m.s < s) {
                approached += i
                announced += i
            }
        }
    }

    private fun rearmAfter(s: Double) {
        maneuvers.forEachIndexed { i, m ->
            if (m.s > s + 1.0) {
                approached -= i
                announced -= i
            }
        }
    }

    private fun alerts(at: Double, events: MutableList<NavEvent>) {
        if (lost) return
        val v = speed.current
        val approachAt = max(config.approachMinMeters, config.approachSeconds * v)
        val nowAt = max(config.nowMinMeters, config.nowSeconds * v)
        maneuvers.forEachIndexed { i, m ->
            if (m.kind == ManeuverKind.SLIGHT && !config.beepForSlight) return@forEachIndexed
            val d = m.s - at
            if (d < -PASSED_MARGIN_M) return@forEachIndexed
            if (m.kind == ManeuverKind.SWITCHBACKS) {
                // One approaching alert for the whole group, no "now" (spec §5.3).
                if (d <= approachAt && approached.add(i)) events += NavEvent.TurnApproaching(i, m)
            } else if (d <= nowAt) {
                if (announced.add(i)) {
                    approached += i
                    events += NavEvent.TurnNow(i, m)
                }
            } else if (d <= approachAt && approached.add(i)) {
                events += NavEvent.TurnApproaching(i, m)
            }
        }
    }

    /** Progress moved on by the time since the last good fix at the smoothed speed (spec §5.10). */
    private fun extrapolated(now: Long): Double {
        val last = lastGood ?: return progress
        val age = now.secondsSince(last.elapsedNanos).coerceIn(0.0, config.maxExtrapolationSeconds)
        return min(progress + speed.current * age, course.length)
    }

    private fun headingPenalty(fix: Fix, segmentBearing: Double): Double {
        val crs = fix.trustedCourse(config) ?: return 0.0
        val angle = Math.toRadians(Geo.turnDeg(crs, segmentBearing))
        return config.headingPenaltyMeters * (1 - cos(angle)) / 2
    }

    private fun countMovement(fix: Fix) {
        val last = lastGood ?: return
        val move = movement(last, fix) ?: return
        if (move.speed >= config.movingMinSpeedMps) {
            odometer += move.distance
            movingNanos += move.nanos
        }
    }

    private class Movement(val distance: Double, val speed: Double, val nanos: Long)

    /**
     * How far the rider went between two good fixes: the fix's own (Doppler) speed over the interval
     * when it has one, which is much steadier than positions jittering by a few metres; otherwise the
     * straight line. Null for a jump no bike could make (a stale or wild fix). Long gaps count at most
     * [NavConfig.noGpsSeconds].
     */
    private fun movement(last: Fix, fix: Fix): Movement? {
        val rawNanos = fix.elapsedNanos - last.elapsedNanos
        if (rawNanos <= 0) return null
        val nanos = min(rawNanos, (config.noGpsSeconds * NANOS_PER_SECOND).toLong())
        val seconds = nanos.toDouble() / NANOS_PER_SECOND
        val reported = fix.speedMps?.toDouble()
        if (reported != null) return Movement(reported * seconds, reported, nanos)
        val d = fix.distanceTo(last)
        val speed = d / (rawNanos.toDouble() / NANOS_PER_SECOND)
        return if (speed > MAX_PLAUSIBLE_SPEED_MPS) null else Movement(d, speed, nanos)
    }

    // ---- Targets ----

    private fun updateTarget(fix: Fix, good: Boolean) {
        target = when (phase) {
            RidePhase.WAITING_GPS, RidePhase.FINISHED -> null
            RidePhase.ACQUIRING -> {
                val c = course.nearestAnywhere(fix.lat, fix.lon)
                distanceToRoute = c.distance
                targetAt(TargetKind.NEAREST_POINT, c.s, fix)
            }
            RidePhase.ON_ROUTE, RidePhase.REVERSE_PENDING -> {
                val ahead = (config.lookAheadSeconds * speed.current).coerceIn(config.lookAheadMinMeters, config.lookAheadMaxMeters)
                targetAt(TargetKind.LOOK_AHEAD, min(progress + ahead, course.length), fix)
            }
            RidePhase.OFF_ROUTE -> if (good) returnTarget(fix) else target?.let { targetAt(it.kind, it.s, fix) }
        }
    }

    /** The off-route arrow (spec §5.7, D29). */
    private fun returnTarget(fix: Fix): NavTarget {
        val long = offDistance >= config.shortDetourMeters
        var aheadTo = min(exitS + config.longDetourWindowMeters, course.length)
        // Never point past a turn that progress hasn't ridden: no "straight down the slope" arrows.
        if (nextBarrier < course.barriers.size) aheadTo = min(aheadTo, course.barriers[nextBarrier])
        val ahead = if (aheadTo > exitS + 1.0) course.nearestInRange(fix.lat, fix.lon, exitS + 1.0, aheadTo) else null
        if (long || aheadLocked) {
            return if (ahead != null) targetAt(TargetKind.AHEAD, ahead.s, fix) else targetAt(TargetKind.EXIT_POINT, exitS, fix)
        }
        val exit = course.position(exitS)
        val exitDistance = Geo.distanceMeters(fix.lat, fix.lon, exit.lat, exit.lon)
        if (ahead != null && ahead.distance <= config.aheadMaxFraction * exitDistance) {
            val tracked = approachS
            when {
                tracked == null || abs(ahead.s - tracked) > APPROACH_SAME_TARGET_M || ahead.distance > approachMinDist + APPROACH_NOISE_M -> {
                    approachSince = fix.elapsedNanos
                    approachStartDist = ahead.distance
                    approachMinDist = ahead.distance
                }
                else -> approachMinDist = min(approachMinDist, ahead.distance)
            }
            approachS = ahead.s
            val approaching = fix.elapsedNanos.secondsSince(approachSince) >= config.aheadApproachSeconds &&
                approachStartDist - ahead.distance >= APPROACH_MIN_GAIN_M
            if (approaching) {
                aheadLocked = true
                return targetAt(TargetKind.AHEAD, ahead.s, fix)
            }
        } else {
            approachS = null
        }
        return targetAt(TargetKind.EXIT_POINT, exitS, fix)
    }

    private fun targetAt(kind: TargetKind, s: Double, fix: Fix): NavTarget {
        val p: RoutePosition = course.position(s)
        return NavTarget(
            kind,
            s,
            p.lat,
            p.lon,
            Geo.distanceMeters(fix.lat, fix.lon, p.lat, p.lon),
            Geo.bearingDeg(fix.lat, fix.lon, p.lat, p.lon),
        )
    }

    // ---- State ----

    private fun buildState(): NavState {
        val length = course.length
        val joined = phase != RidePhase.WAITING_GPS && phase != RidePhase.ACQUIRING
        val gps = when {
            lastFix == null -> GpsStatus.WAITING
            lost -> GpsStatus.LOST
            poor -> GpsStatus.POOR
            else -> GpsStatus.OK
        }
        val nextIndex = if (joined && phase != RidePhase.FINISHED) {
            maneuvers.indexOfFirst { (if (it.parts.isEmpty()) it.s else it.endS) > progress }
        } else {
            -1
        }
        val next = maneuvers.getOrNull(nextIndex)?.let { UpcomingManeuver(nextIndex, it, max(0.0, it.s - progress)) }
        val then = next?.let { n ->
            maneuvers.getOrNull(n.index + 1)
                ?.takeIf { it.s - n.maneuver.s <= config.thenMaxGapMeters }
                ?.let { UpcomingManeuver(n.index + 1, it, it.s - progress) }
        }
        val h = heading.current
        val t = target
        return NavState(
            phase = phase,
            gps = gps,
            satellites = lastFix?.satellites,
            lastFixNanos = lastFix?.elapsedNanos,
            reversed = reversed,
            progressM = progress,
            lengthM = length,
            distanceLeftM = max(0.0, length - progress),
            climbLeftM = course.route.elevation?.ascentBetween(progress, length),
            distanceToRouteM = distanceToRoute,
            next = next,
            then = then,
            target = t,
            heading = h,
            arrowDeg = if (t != null && h != null) Geo.turnDeg(h.degrees, t.bearingDeg) else null,
            reverseDeadlineNanos = reverseSince?.let { it + (config.reversePromptSeconds * NANOS_PER_SECOND).toLong() },
            skipped = skipped.toList(),
            rideDistanceM = odometer,
            movingSeconds = movingNanos.toDouble() / NANOS_PER_SECOND,
            speedMps = speed.current,
            position = lastFix?.let { LatLon(it.lat, it.lon) },
        )
    }

    private fun buildCourse(): Course {
        val base = if (reversed) navRoute.reverse else navRoute.forward
        val start = loopStart ?: return Course(base, if (reversed) navRoute.reverseManeuvers else navRoute.forwardManeuvers, false, config.barrierKinds)
        val rotated = base.rotatedLoop(start)
        return Course(rotated, ManeuverDetector.detect(rotated, navRoute.maneuverConfig), true, config.barrierKinds)
    }

    // ---- Checkpoints (D28) ----

    fun snapshot(): NavCheckpoint = NavCheckpoint(
        phase = if (phase == RidePhase.REVERSE_PENDING) RidePhase.ON_ROUTE else phase,
        reversed = reversed,
        loopStartM = loopStart,
        progressM = progress,
        highWaterM = highWater,
        nextBarrier = nextBarrier,
        exitM = if (phase == RidePhase.OFF_ROUTE) exitS else null,
        offRouteDistanceM = if (phase == RidePhase.OFF_ROUTE) offDistance else 0.0,
        approached = approached.sorted(),
        announced = announced.sorted(),
        skipped = skipped.toList(),
        rideDistanceM = odometer,
        movingSeconds = movingNanos.toDouble() / NANOS_PER_SECOND,
        reverseSuppressUntilM = reverseSuppressUntil,
    )

    private fun restoreFrom(cp: NavCheckpoint) {
        loopStart = cp.loopStartM
        course = buildCourse()
        phase = cp.phase
        progress = cp.progressM
        highWater = cp.highWaterM
        rawS = cp.progressM
        nextBarrier = cp.nextBarrier.coerceIn(0, course.barriers.size)
        approached += cp.approached
        announced += cp.announced
        skipped += cp.skipped
        odometer = cp.rideDistanceM
        movingNanos = (cp.movingSeconds * NANOS_PER_SECOND).toLong()
        reverseSuppressUntil = cp.reverseSuppressUntilM
        when (phase) {
            RidePhase.ON_ROUTE, RidePhase.REVERSE_PENDING -> {
                phase = RidePhase.ON_ROUTE
                relocating = true
            }
            // The clock may have restarted (reboot), so off route resumes as after a long detour.
            RidePhase.OFF_ROUTE -> {
                exitS = cp.exitM ?: cp.progressM
                offDistance = max(cp.offRouteDistanceM, config.shortDetourMeters)
            }
            else -> Unit
        }
    }

    companion object {
        /** Off-route searches prefer points ahead: a point behind has to be this much closer. */
        private const val BEHIND_PENALTY_M = 10.0

        /** The reverse check restarts when progress moves forward by this much. */
        private const val REVERSE_RESET_M = 10.0

        /** Faster than this between two fixes (108 km/h) is a GPS jump, not riding. */
        private const val MAX_PLAUSIBLE_SPEED_MPS = 30.0

        /** A turn this far behind the rider is passed: no late alerts for it. */
        private const val PASSED_MARGIN_M = 5.0

        /** Off-route approach tracking: the point ahead may drift this far and still be the same target... */
        private const val APPROACH_SAME_TARGET_M = 50.0

        /** ...and its distance may grow this much (GPS noise) before the approach counts as broken... */
        private const val APPROACH_NOISE_M = 3.0

        /** ...and over the approach the rider must have got at least this much closer. */
        private const val APPROACH_MIN_GAIN_M = 5.0

        /** Continues a ride from a checkpoint (spec D28). The first fix finds the rider again. */
        fun restore(route: NavRoute, checkpoint: NavCheckpoint, config: NavConfig = NavConfig.DEFAULT): Navigator {
            require(checkpoint.version == NavCheckpoint.VERSION) { "Unknown checkpoint version ${checkpoint.version}" }
            return Navigator(route, config, RideMode(checkpoint.reversed)).also { it.restoreFrom(checkpoint) }
        }
    }
}
