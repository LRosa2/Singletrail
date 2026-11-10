package io.github.lrosa2.singletrail.backup

import io.github.lrosa2.singletrail.data.db.RouteEntity
import io.github.lrosa2.singletrail.data.routes.RouteRepository
import io.github.lrosa2.singletrail.gpx.GpxWriter
import io.github.lrosa2.singletrail.util.FileNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Files for "Share routes" (spec §8.5): one GPX, or a zip for several. Name, description, track and waypoints only. */
object RouteSharing {
    class Prepared(val files: List<File>, val mimeType: String)

    suspend fun prepare(routes: List<RouteEntity>, repository: RouteRepository, dir: File, appVersion: String, now: Long): Prepared {
        require(routes.isNotEmpty()) { "No routes to share" }
        val docs = routes.map { it.name to repository.sharedGpx(it) }
        return withContext(Dispatchers.IO) {
            if (docs.size == 1) {
                val (name, doc) = docs.single()
                val file = File(dir, FileNames.safe(name, "gpx"))
                file.bufferedWriter().use { GpxWriter.write(doc, it) }
                Prepared(listOf(file), "application/gpx+xml")
            } else {
                val file = File(dir, "Singletrail routes.zip")
                file.outputStream().buffered().use { BackupZip.writeRoutes(it, appVersion, now, docs) }
                Prepared(listOf(file), "application/zip")
            }
        }
    }
}
