package io.github.lrosa2.singletrail.ride

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.os.storage.StorageManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.lrosa2.singletrail.R
import io.github.lrosa2.singletrail.SingletrailApp
import io.github.lrosa2.singletrail.data.db.Direction
import io.github.lrosa2.singletrail.data.db.RideEntity
import io.github.lrosa2.singletrail.data.rides.RideCheckpoint
import io.github.lrosa2.singletrail.data.rides.TrackPoint
import io.github.lrosa2.singletrail.data.rides.TrackWriter
import io.github.lrosa2.singletrail.data.settings.RideSettings
import io.github.lrosa2.singletrail.nav.NavRoute
import io.github.lrosa2.singletrail.nav.PreparedRoute
import io.github.lrosa2.singletrail.util.thinTo
import io.github.lrosa2.singletrail.nav.engine.NavCheckpoint
import io.github.lrosa2.singletrail.nav.engine.NavState
import io.github.lrosa2.singletrail.nav.engine.Navigator
import io.github.lrosa2.singletrail.nav.engine.RideMode
import io.github.lrosa2.singletrail.nav.engine.RidePhase
import io.github.lrosa2.singletrail.nav.engine.TraceRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.FileWriter
import java.io.IOException
import java.io.Writer

/**
 * The ride (spec §6): a location foreground service started from the visible app when the rider taps
 * Ride, or when the app reopens with a ride still in progress. Everything that touches the
 * [Navigator] runs on one thread ([rideThread]).
 */
class RideService : Service() {
    private val container get() = (application as SingletrailApp).container

    @OptIn(ExperimentalCoroutinesApi::class)
    private val rideThread = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + rideThread)
    private var startJob: Job? = null
    private var running: Running? = null
    private var notificationText: String? = null

    private class Running(
        val ride: RideEntity,
        val navigator: Navigator,
        val session: RideSession,
        val gps: GpsSource,
        val job: Job,
    )

    private val saverReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            scope.launch { refreshWarnings() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        RideNotification.ensureChannel(this)
        ContextCompat.registerReceiver(
            this,
            saverReceiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START, ACTION_RESUME -> {
                if (!enterForeground()) return START_NOT_STICKY
                if (startJob == null && running == null) {
                    startJob = scope.launch {
                        if (intent.action == ACTION_START) {
                            startRide(
                                routeId = intent.getStringExtra(EXTRA_ROUTE_ID).orEmpty(),
                                reversed = intent.getBooleanExtra(EXTRA_REVERSED, false),
                                simulate = intent.getBooleanExtra(EXTRA_SIMULATE, false),
                            )
                        } else {
                            resumeRide()
                        }
                    }
                }
            }
            ACTION_STOP -> scope.launch { stopRide() }
            // A while-in-use location service can't be restarted by the system (START_NOT_STICKY).
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        unregisterReceiver(saverReceiver)
        // Destroyed without a stop: the ride stays in progress and resumes when the app opens again.
        running?.let { container.log.log(TAG, "Service destroyed during ride ${it.ride.id}") }
        container.rideController.session = null
        scope.cancel()
        super.onDestroy()
    }

    private fun enterForeground(): Boolean = try {
        val notification = RideNotification.build(this, getString(R.string.ride_starting), null)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, RideNotification.ID, notification, type)
        true
    } catch (e: RuntimeException) {
        // Missing location permission, or started from the background.
        container.log.log(TAG, "Could not start in the foreground: ${e.javaClass.simpleName}")
        container.rideController.fail(RideFailure.NO_PERMISSION)
        stopSelf()
        false
    }

    private suspend fun startRide(routeId: String, reversed: Boolean, simulate: Boolean) {
        val route = container.routes.route(routeId) ?: return failAndStop(RideFailure.ROUTE_MISSING)
        val navRoute = loadNavRoute(routeId) ?: return failAndStop(RideFailure.ROUTE_UNREADABLE)
        val settings = container.settings.current()
        val ride = container.rides.start(routeId, route.name, reversed)
        container.log.log(TAG, "Start ride ${ride.id} reversed=$reversed simulated=$simulate")
        val navigator = Navigator(navRoute, settings.navConfig(), RideMode(reversed))
        launchRide(ride, navigator, segment = 1, simulate, settings, fresh = true, routeHash = route.geometryHash, rating = route.rating)
    }

    private suspend fun resumeRide() {
        val rides = container.rides
        val ride = rides.inProgress() ?: return stopNow(clear = true)
        val routeId = ride.routeId
        val route = routeId?.let { container.routes.route(it) }
        if (routeId == null || route == null) {
            rides.finish(ride.id, completed = false, reversed = ride.direction == Direction.REVERSE, distanceM = ride.distanceM, movingSeconds = 0.0)
            return failAndStop(RideFailure.ROUTE_MISSING)
        }
        val navRoute = loadNavRoute(routeId) ?: return failAndStop(RideFailure.ROUTE_UNREADABLE)
        val settings = container.settings.current()
        val checkpoint = rides.checkpoints.load(ride.id)
        val navigator = if (checkpoint != null) {
            Navigator.restore(navRoute, checkpoint.nav, settings.navConfig())
        } else {
            Navigator(navRoute, settings.navConfig(), RideMode(ride.direction == Direction.REVERSE))
        }
        val segment = rides.startSegment(ride.id)
        container.log.log(TAG, "Resume ride ${ride.id} segment=$segment checkpoint=${checkpoint?.nav?.phase}")
        launchRide(
            ride, navigator, segment, checkpoint?.simulated ?: false, settings, fresh = false,
            routeHash = route.geometryHash, rating = route.rating,
        )
    }

    private suspend fun loadNavRoute(routeId: String): NavRoute? = try {
        val started = SystemClock.elapsedRealtime()
        val prepared = container.routes.prepared(routeId)
        withContext(Dispatchers.Default) { NavRoute.of(prepared) }.also {
            container.log.log(TAG, "Route ready: ${it.forward.points.size} points, ${it.forwardManeuvers.size} maneuvers, ${SystemClock.elapsedRealtime() - started} ms")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        container.log.log(TAG, "Route $routeId unreadable: ${e.javaClass.simpleName}")
        null
    }

    private fun launchRide(
        ride: RideEntity,
        navigator: Navigator,
        segment: Int,
        simulated: Boolean,
        settings: RideSettings,
        fresh: Boolean,
        routeHash: String,
        rating: Int?,
    ) {
        val rides = container.rides
        val controller = container.rideController
        val writer = TrackWriter(rides.trackFile(ride), segment, freeBytes = ::allocatableBytes)
        val trace: Writer? = if (settings.debugTrace) openTrace(ride.id) else null
        val beeper = BeepPlayer(scope, settings.beepVolume)
        val output = object : RideOutput {
            override fun beep(tone: Tone) = beeper.beep(tone)

            override fun record(reading: GpsReading) {
                val f = reading.fix
                writer.append(TrackPoint(reading.utcMillis, f.elapsedNanos / 1_000_000, f.lat, f.lon, f.altitudeM, f.accuracyM, f.speedMps, f.bearingDeg, segment))
                if (writer.stopped) controller.update { it.copy(warnings = it.warnings + RideWarning.RECORDING_STOPPED) }
            }

            override fun checkpoint(nav: NavCheckpoint) {
                saveCheckpoint(ride, segment, simulated, nav)
                try {
                    trace?.flush()
                } catch (_: IOException) {
                    // A debug trace is best effort.
                }
            }

            override fun trace(line: String) {
                try {
                    trace?.apply {
                        write(line)
                        write("\n")
                    }
                } catch (_: IOException) {
                    // A debug trace is best effort.
                }
            }
        }
        // A resumed ride appends to the same trace; its header marks where each segment starts.
        val header = trace?.let {
            TraceRecord.Header(
                reversed = navigator.state.reversed,
                routeHash = routeHash,
                note = "ride ${ride.id} segment $segment" + if (fresh) "" else " (resumed from checkpoint)",
            )
        }
        val session = RideSession(navigator, output, SystemClock::elapsedRealtimeNanos, traceHeader = header)
        val gps = GpsSource(this)
        val readings = if (simulated) {
            RouteSimulator(navigator.route, speedMps = SIMULATED_SPEED_MPS)
                .readings(navigator.state.progressM, SystemClock::elapsedRealtimeNanos, System::currentTimeMillis)
        } else {
            gps.readings()
        }
        val headings = CompassSource(this) { gps.latest.get() }.headings()
        // Ride-start audio check (spec §4.3): a test beep, and warnings for anything that would silence it.
        if (fresh) beeper.beep(Tone.APPROACHING)
        controller.session = session
        var outlineOf: PreparedRoute = navigator.route
        var outline = outlineOf.samples.thinTo(OUTLINE_POINTS)
        controller.publish(
            RideUi(
                ride.id, ride.routeNameSnapshot, simulated, navigator.state, navigator.maneuvers, currentWarnings(),
                routeId = ride.routeId, rating = rating, outline = outline,
            ),
        )
        val job = scope.launch {
            try {
                launch {
                    session.state.collectLatest { state ->
                        // The course changes when a loop is joined or the direction reverses.
                        if (navigator.route !== outlineOf) {
                            outlineOf = navigator.route
                            outline = outlineOf.samples.thinTo(OUTLINE_POINTS)
                        }
                        controller.update { it.copy(nav = state, maneuvers = navigator.maneuvers, outline = outline) }
                        updateNotification(ride.routeNameSnapshot, state, gps.satellitesInView)
                    }
                }
                launch {
                    while (true) {
                        delay(1_000)
                        val inView = gps.satellitesInView
                        controller.update { it.copy(satellitesInView = inView) }
                        updateNotification(ride.routeNameSnapshot, session.state.value, inView)
                    }
                }
                session.run(readings, headings)
            } finally {
                withContext(NonCancellable) {
                    // Last checkpoint, then close the files; a stop deletes the checkpoint afterwards.
                    saveCheckpoint(ride, segment, simulated, navigator.snapshot())
                    writer.close()
                    try {
                        trace?.close()
                    } catch (_: IOException) {
                        // Best effort.
                    }
                    beeper.close()
                }
            }
        }
        running = Running(ride, navigator, session, gps, job)
        startJob = null
    }

    private suspend fun stopRide() {
        val current = running
        if (current == null) {
            // Stopped while still starting: end whatever was created.
            startJob?.cancelAndJoin()
            startJob = null
            container.rides.inProgress()?.let {
                container.rides.finish(it.id, completed = false, reversed = it.direction == Direction.REVERSE, distanceM = 0.0, movingSeconds = 0.0)
            }
            return stopNow(clear = true)
        }
        current.job.cancelAndJoin()
        val state = current.navigator.state
        container.rides.finish(
            current.ride.id,
            completed = state.phase == RidePhase.FINISHED,
            reversed = state.reversed,
            distanceM = state.rideDistanceM,
            movingSeconds = state.movingSeconds,
        )
        container.log.log(TAG, "Stop ride ${current.ride.id} phase=${state.phase}")
        running = null
        stopNow(clear = true)
    }

    private fun saveCheckpoint(ride: RideEntity, segment: Int, simulated: Boolean, nav: NavCheckpoint) {
        try {
            container.rides.checkpoints.save(RideCheckpoint(ride.id, ride.routeId.orEmpty(), segment, simulated, nav))
        } catch (e: IOException) {
            container.log.log(TAG, "Checkpoint failed: ${e.javaClass.simpleName}")
        }
    }

    /** Free space for the track, counting cached data Android would clear for it. */
    private fun allocatableBytes(): Long = try {
        val storage = getSystemService(StorageManager::class.java)
        storage.getAllocatableBytes(storage.getUuidForPath(filesDir))
    } catch (_: IOException) {
        Long.MAX_VALUE
    }

    private fun openTrace(rideId: String): Writer? = try {
        val file = container.rides.traceFile(rideId)
        file.parentFile?.mkdirs()
        BufferedWriter(FileWriter(file, true))
    } catch (_: IOException) {
        null
    }

    private fun currentWarnings(): Set<RideWarning> = buildSet {
        val simulated = container.rideController.ride.value?.simulated == true
        val location = getSystemService(android.location.LocationManager::class.java)
        if (!simulated && !location.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)) add(RideWarning.LOCATION_OFF)
        addAll(DeviceChecks.audio(this@RideService))
        addAll(DeviceChecks.batterySaver(this@RideService))
        if (notificationsBlocked(this@RideService)) add(RideWarning.NOTIFICATIONS_OFF)
    }

    private fun refreshWarnings() {
        val fresh = currentWarnings()
        container.rideController.update { ui ->
            // Recording problems don't come back by themselves; keep them.
            ui.copy(warnings = fresh + ui.warnings.filter { it == RideWarning.RECORDING_STOPPED })
        }
    }

    private fun updateNotification(routeName: String, state: NavState, satellitesInView: Int?) {
        val text = RideTexts.message(resources, RideMessage.of(state, SystemClock.elapsedRealtimeNanos(), satellitesInView))
        if (text == notificationText) return
        notificationText = text
        RideNotification.show(this, RideNotification.build(this, routeName, text))
    }

    private fun failAndStop(failure: RideFailure) {
        container.rideController.fail(failure)
        stopNow(clear = false)
    }

    private fun stopNow(clear: Boolean) {
        container.rideController.session = null
        if (clear) container.rideController.publish(null)
        startJob = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        private const val TAG = "Ride"
        private const val ACTION_START = "io.github.lrosa2.singletrail.ride.START"
        private const val ACTION_RESUME = "io.github.lrosa2.singletrail.ride.RESUME"
        private const val ACTION_STOP = "io.github.lrosa2.singletrail.ride.STOP"
        private const val EXTRA_ROUTE_ID = "routeId"
        private const val EXTRA_REVERSED = "reversed"
        private const val EXTRA_SIMULATE = "simulate"

        /** About 22 km/h: brisk enough to reach the turns at a desk, slow enough to watch. */
        private const val SIMULATED_SPEED_MPS = 6.0

        /** Points in the route view's outline; plenty for a phone screen. */
        private const val OUTLINE_POINTS = 800

        fun startIntent(context: Context, routeId: String, reversed: Boolean, simulate: Boolean): Intent =
            Intent(context, RideService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_ROUTE_ID, routeId)
                .putExtra(EXTRA_REVERSED, reversed)
                .putExtra(EXTRA_SIMULATE, simulate)

        fun resumeIntent(context: Context): Intent = Intent(context, RideService::class.java).setAction(ACTION_RESUME)

        fun stopIntent(context: Context): Intent = Intent(context, RideService::class.java).setAction(ACTION_STOP)
    }
}
