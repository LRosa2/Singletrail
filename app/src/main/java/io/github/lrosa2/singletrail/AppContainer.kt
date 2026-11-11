package io.github.lrosa2.singletrail

import android.app.Application
import android.content.Context
import io.github.lrosa2.singletrail.backup.BackupArchive
import io.github.lrosa2.singletrail.backup.BackupExporter
import io.github.lrosa2.singletrail.backup.BackupRestorer
import io.github.lrosa2.singletrail.backup.LibraryRestoreTarget
import io.github.lrosa2.singletrail.data.db.SingletrailDatabase
import io.github.lrosa2.singletrail.data.gpx.androidGpxReader
import io.github.lrosa2.singletrail.data.importing.ContentGpxSource
import io.github.lrosa2.singletrail.data.importing.RoutingGpxSource
import io.github.lrosa2.singletrail.data.importing.UrlConnectionFetcher
import io.github.lrosa2.singletrail.data.importing.UrlGpxSource
import io.github.lrosa2.singletrail.data.importing.WatchedFolder
import io.github.lrosa2.singletrail.data.maps.MapManager
import io.github.lrosa2.singletrail.data.maps.RoomMapCatalog
import io.github.lrosa2.singletrail.data.maps.allocatableBytes
import io.github.lrosa2.singletrail.data.rides.RideRepository
import io.github.lrosa2.singletrail.data.routes.RouteRepository
import io.github.lrosa2.singletrail.data.settings.SettingsStore
import io.github.lrosa2.singletrail.diagnostics.AppLog
import io.github.lrosa2.singletrail.importing.ArchiveSupport
import io.github.lrosa2.singletrail.importing.ImportCoordinator
import io.github.lrosa2.singletrail.maps.MapInstaller
import java.io.File
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
    private val userAgent = "Singletrail/${BuildConfig.VERSION_NAME} (Android)"

    val log = AppLog(context.filesDir)
    val database: SingletrailDatabase = SingletrailDatabase.open(context)
    val routes = RouteRepository(database, context.filesDir, { gpxReader.read(it) })
    val rides = RideRepository(database, context.filesDir)
    val settings = SettingsStore(context)
    val importer = ImportCoordinator(
        RoutingGpxSource(
            content = ContentGpxSource(context.contentResolver, gpxReader, File(context.cacheDir, "import")),
            // An app-specific User-Agent (spec S6), so servers can tell who is asking.
            urls = UrlGpxSource(UrlConnectionFetcher(), { gpxReader.read(it) }, userAgent, File(context.cacheDir, "import")),
        ),
        routes,
        appScope,
        archives = ArchiveSupport(
            open = { BackupArchive.open(it, { input -> gpxReader.read(input) }) },
            restorer = BackupRestorer(LibraryRestoreTarget(routes, rides, settings)),
            ownerId = settings::backupOwner,
        ),
        log = { log.log("Import", it) },
    )
    val backups = BackupExporter(routes, rides, settings, BuildConfig.VERSION_NAME)
    val watchedFolder = WatchedFolder(context.contentResolver, settings, importer, routes, database.watchedFiles(), { gpxReader.read(it) })
    val rideController = RideController(context.applicationContext, rides, routes, appScope)

    /** Offline maps live in app-specific external storage (spec §8.3), next to the download folder. */
    val maps = MapManager(
        context.applicationContext,
        MapInstaller(
            context.getExternalFilesDir(null) ?: context.filesDir,
            RoomMapCatalog(database.maps()),
            freeBytes = { allocatableBytes(context, it) },
        ),
        database.maps(),
        settings,
        appScope,
        userAgent,
        log = { log.log("Maps", it) },
    )

    /** Work that must outlive the screen that started it (a backup, a share). */
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
