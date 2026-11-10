package io.github.lrosa2.singletrail

import android.app.Application
import android.content.Context
import io.github.lrosa2.singletrail.data.db.SingletrailDatabase
import io.github.lrosa2.singletrail.data.gpx.androidGpxReader
import io.github.lrosa2.singletrail.data.importing.ContentGpxSource
import io.github.lrosa2.singletrail.data.routes.RouteRepository
import io.github.lrosa2.singletrail.importing.ImportCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Manual dependency injection (spec §13.1): one instance of each app-wide service. */
class AppContainer(context: Context) {
    /** Outlives screens, so an import keeps going while the user moves around the app. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gpxReader = androidGpxReader()

    val database: SingletrailDatabase = SingletrailDatabase.open(context)
    val routes = RouteRepository(database, context.filesDir, { gpxReader.read(it) })
    val importer = ImportCoordinator(ContentGpxSource(context.contentResolver, gpxReader), routes, appScope)
}

class SingletrailApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
