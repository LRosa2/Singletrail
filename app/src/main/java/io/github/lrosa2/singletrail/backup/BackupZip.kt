package io.github.lrosa2.singletrail.backup

import io.github.lrosa2.singletrail.gpx.GpxDocument
import io.github.lrosa2.singletrail.gpx.GpxWriter
import io.github.lrosa2.singletrail.util.FileNames
import kotlinx.serialization.builtins.ListSerializer
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Writes Singletrail zips (spec §8.5). Callers own [OutputStream]s; the zip is finished, not closed. */
object BackupZip {

    /** A full backup: routes with stars, rides with tracks, settings. Contains location history. */
    fun writeFull(
        out: OutputStream,
        manifest: BackupManifest,
        routes: List<BackupRoute>,
        routeGpx: (BackupRoute) -> GpxDocument,
        rides: List<BackupRide>,
        rideGpx: (BackupRide) -> GpxDocument,
        settings: BackupSettings?,
        collections: List<BackupCollection> = emptyList(),
    ) {
        val zip = ZipOutputStream(out)
        zip.text("manifest.json", BackupJson.encodeToString(BackupManifest.serializer(), manifest))
        zip.text("routes.json", BackupJson.encodeToString(ListSerializer(BackupRoute.serializer()), routes))
        zip.text("collections.json", BackupJson.encodeToString(ListSerializer(BackupCollection.serializer()), collections))
        zip.text("rides.json", BackupJson.encodeToString(ListSerializer(BackupRide.serializer()), rides))
        settings?.let { zip.text("settings.json", BackupJson.encodeToString(BackupSettings.serializer(), it)) }
        routes.forEach { zip.text(it.file, GpxWriter.writeToString(routeGpx(it))) }
        rides.forEach { zip.text(it.file, GpxWriter.writeToString(rideGpx(it))) }
        zip.finish()
    }

    /** Several routes to share (spec §8.5): GPX only, named after the routes; no rides, no stars. */
    fun writeRoutes(out: OutputStream, app: String, createdAt: Long, routes: List<Pair<String, GpxDocument>>, collection: String? = null) {
        val zip = ZipOutputStream(out)
        val manifest = BackupManifest(kind = BackupManifest.KIND_ROUTES, app = app, createdAt = createdAt, routes = routes.size, collection = collection)
        zip.text("manifest.json", BackupJson.encodeToString(BackupManifest.serializer(), manifest))
        val names = FileNames.Unique()
        routes.forEach { (name, doc) -> zip.text(names.next(name, "gpx"), GpxWriter.writeToString(doc)) }
        zip.finish()
    }

    private fun ZipOutputStream.text(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }
}
