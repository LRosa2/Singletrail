package io.github.lrosa2.singletrail

import android.app.Application
import android.content.Context
import io.github.lrosa2.singletrail.data.db.SingletrailDatabase
import io.github.lrosa2.singletrail.data.gpx.androidGpxReader
import io.github.lrosa2.singletrail.data.importing.ContentGpxSource
import io.github.lrosa2.singletrail.data.rides.RideRepository
import io.github.lrosa2.singletrail.data.routes.RouteRepository
import io.github.lrosa2.singletrail.data.settings.SettingsStore
import io.github.lrosa2.singletrail.diagnostics.AppLog
import io.github.lrosa2.singletrail.importing.ImportCoordinator
import io.github.lrosa2.singletrail.ride.RideController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Manual dependency injection (spec §13.1): one instance of each app-wide service. */
class AppContainer(context: Context) {
    /** Outlives screens, so an import keeps going while the user moves around the app. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gpxReader = androidGpxReader()

    val log = AppLog(context.filesDir)
    val database: SingletrailDatabase = SingletrailDatabase.open(context)
    val routes = RouteRepository(database, context.filesDir, { gpxReader.read(it) })
    val importer = ImportCoordinator(ContentGpxSource(context.contentResolver, gpxReader), routes, appScope)
    val rides = RideRepository(database, context.filesDir)
    val settings = SettingsStore(context)
    val rideController = RideController(context.applicationContext, rides)

    fun launchInBackground(block: suspend () -> Unit) {
        appScope.launch { block() }
    }
}

class SingletrailApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.log.installCrashHandler()
        container.launchInBackground { container.log.recordExitReasons(this) }
    }
}
