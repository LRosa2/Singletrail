package io.github.lrosa2.singletrail.data.maps

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.SystemClock
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import io.github.lrosa2.singletrail.R
import io.github.lrosa2.singletrail.data.db.MapDao
import io.github.lrosa2.singletrail.data.db.MapFileEntity
import io.github.lrosa2.singletrail.data.settings.PendingMapDownload
import io.github.lrosa2.singletrail.data.settings.SettingsStore
import io.github.lrosa2.singletrail.maps.MapCatalog
import io.github.lrosa2.singletrail.maps.MapCheck
import io.github.lrosa2.singletrail.maps.MapFileException
import io.github.lrosa2.singletrail.maps.MapInstaller
import io.github.lrosa2.singletrail.maps.MapLink
import io.github.lrosa2.singletrail.maps.MapLinkException
import io.github.lrosa2.singletrail.maps.MapProblem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Space an app may write on the volume of [dir], counting space the system could free up. */
fun allocatableBytes(context: Context, dir: File): Long = try {
    val storage = context.getSystemService(StorageManager::class.java)
    storage.getAllocatableBytes(storage.getUuidForPath(dir))
} catch (_: IOException) {
    @Suppress("UsableSpace") // Only when the volume isn't known to StorageManager.
    dir.usableSpace
}

class RoomMapCatalog(private val dao: MapDao) : MapCatalog {
    override suspend fun all() = dao.all()
    override suspend fun put(map: MapFileEntity) = dao.put(map)
    override suspend fun remove(id: String) = dao.remove(id)
}

/** What the maps screen shows about the current download or install. */
sealed interface MapJob {
    val region: String

    data class Downloading(override val region: String, val bytes: Long, val total: Long?, val waitingForWifi: Boolean) : MapJob
    data class Installing(override val region: String) : MapJob
    data class Installed(override val region: String) : MapJob

    /** [problem] null: the download itself failed. */
    data class Failed(override val region: String, val problem: MapProblem?, val bytesNeeded: Long? = null) : MapJob
}

/** An install link waiting for the user's confirmation (spec §7.2 step 4). */
sealed interface MapOffer {
    data class Checking(val link: MapLink) : MapOffer
    data class Ready(val link: MapLink, val zipBytes: Long?, val neededBytes: Long?, val freeBytes: Long) : MapOffer {
        val fits get() = neededBytes == null || neededBytes <= freeBytes
    }

    /** Another map is still downloading or being installed. */
    data class Busy(val link: MapLink) : MapOffer
    data class Refused(val reason: MapLinkException.Reason) : MapOffer
}

fun interface MapSizeProbe {
    /** The download's size from a HEAD request, or null when the server doesn't say or can't be reached. */
    fun size(url: String, userAgent: String): Long?
}

class HeadSizeProbe : MapSizeProbe {
    override fun size(url: String, userAgent: String): Long? = try {
        val connection = URL(url).openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = "HEAD"
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("User-Agent", userAgent)
            if (connection.responseCode == 200 && connection.url.protocol == "https") connection.contentLengthLong.takeIf { it > 0 } else null
        } finally {
            connection.disconnect()
        }
    } catch (_: IOException) {
        null
    } catch (_: ClassCastException) {
        null
    }
}

/**
 * Offline maps on this phone (spec §7.2–7.3): install links, the system `DownloadManager` (one
 * download at a time, Wi-Fi only unless the user allows mobile data), unpacking, manual import and
 * the check for maps deleted outside the app.
 */
class MapManager(
    private val context: Context,
    val installer: MapInstaller,
    private val dao: MapDao,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val userAgent: String,
    private val log: (String) -> Unit,
    private val probe: MapSizeProbe = HeadSizeProbe(),
) {
    val maps: Flow<List<MapFileEntity>> = dao.observeAll()

    /** The installed map files, for the ride map (combined, spec §7.4). */
    val mapFiles: Flow<List<File>> = maps.map { list -> list.map(installer::fileOf) }.distinctUntilChanged()

    private val _job = MutableStateFlow<MapJob?>(null)
    val job: StateFlow<MapJob?> = _job.asStateFlow()

    private val _offer = MutableStateFlow<MapOffer?>(null)
    val offer: StateFlow<MapOffer?> = _offer.asStateFlow()

    /** Maps the startup check found deleted outside the app, for a notice. */
    private val _removed = MutableStateFlow<List<String>>(emptyList())
    val removed: StateFlow<List<String>> = _removed.asStateFlow()

    private val downloads = context.getSystemService(DownloadManager::class.java)
    private val refreshing = Mutex()
    private var checked = false

    init {
        // The download finishing while the app is alive; otherwise it is picked up when the app opens.
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) = refresh()
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            // Sent by the system download provider, which is a separate app.
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    /** An install link from the website: checks it, then asks for confirmation with size and free space. */
    fun offer(text: String) {
        val link = try {
            MapLink.parse(text)
        } catch (e: MapLinkException) {
            log("Map link refused: ${e.reason}")
            _offer.value = MapOffer.Refused(e.reason)
            return
        }
        _offer.value = MapOffer.Checking(link)
        scope.launch {
            val job = _job.value
            if (settings.pendingMapDownload() != null || job is MapJob.Installing) {
                _offer.value = MapOffer.Busy(link)
                return@launch
            }
            val zip = withContext(Dispatchers.IO) { probe.size(link.downloadUrl, userAgent) }
            // The zip, plus the unpacked map (map files shrink little in a zip), plus a margin.
            val needed = zip?.let { it + it * 13 / 10 + MARGIN }
            _offer.value = MapOffer.Ready(link, zip, needed, freeBytes(downloadDir()))
        }
    }

    fun dismissOffer() {
        _offer.value = null
    }

    fun accept(offer: MapOffer.Ready, mobileData: Boolean) {
        _offer.value = null
        scope.launch {
            val link = offer.link
            val dir = downloadDir()
            File(dir, link.fileName).delete()
            val request = DownloadManager.Request(link.downloadUrl.toUri())
                .setTitle(context.getString(R.string.maps_download_title, link.region))
                .setDescription(context.getString(R.string.maps_attribution))
                .addRequestHeader("User-Agent", userAgent)
                .setAllowedOverMetered(mobileData)
                .setAllowedOverRoaming(false)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(context, DOWNLOADS, link.fileName)
            val id = downloads.enqueue(request)
            settings.setPendingMapDownload(PendingMapDownload(id, offerLink(link)))
            log("Map download started: ${link.region} (${offer.zipBytes ?: "?"} bytes)")
            _job.value = MapJob.Downloading(link.region, 0, offer.zipBytes, waitingForWifi = false)
        }
    }

    fun cancelDownload() {
        scope.launch {
            val pending = settings.pendingMapDownload() ?: return@launch
            downloads.remove(pending.downloadId)
            settings.setPendingMapDownload(null)
            log("Map download cancelled")
            _job.value = null
        }
    }

    /** Looks at the pending download: progress, or install it when it has finished. */
    fun refresh() {
        scope.launch {
            if (!refreshing.tryLock()) return@launch
            try {
                refreshNow()
            } finally {
                refreshing.unlock()
            }
        }
    }

    /** Once per app start (spec §7.3): maps deleted outside the app, then any finished download. */
    fun startupCheck() {
        if (checked) return
        checked = true
        scope.launch {
            try {
                val check: MapCheck = installer.check()
                if (check.removed.isNotEmpty() || check.added.isNotEmpty()) log("Map check: removed ${check.removed}, added ${check.added}")
                _removed.value = check.removed
            } catch (e: IOException) {
                log("Map check failed: $e")
            }
            refresh()
        }
    }

    fun dismissRemoved() {
        _removed.value = emptyList()
    }

    fun dismissJob() {
        if (_job.value !is MapJob.Downloading && _job.value !is MapJob.Installing) _job.value = null
    }

    fun delete(map: MapFileEntity) {
        scope.launch {
            installer.delete(map.id)
            log("Map deleted: ${map.displayName}")
        }
    }

    /** A `.map` (or an OpenAndroMaps zip) picked from the phone's files (spec §7.2, manual import). */
    fun importPicked(uri: Uri) {
        scope.launch {
            val name = displayName(uri) ?: "map"
            val region = MapInstaller.displayName(MapInstaller.safeName(name.removeSuffix(".zip").removeSuffix(".ZIP")))
            _job.value = MapJob.Installing(region)
            val temp = File(installer.incomingDir, "picked-${System.currentTimeMillis()}.part")
            try {
                withContext(Dispatchers.IO) {
                    size(uri)?.let { installer.requireSpace(it) }
                    installer.incomingDir.mkdirs()
                    val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Can't open $uri")
                    input.use { inp -> temp.outputStream().use { inp.copyTo(it, 64 * 1024) } }
                }
                // Sniff rather than trust the name: some file providers drop extensions.
                if (isZip(temp)) {
                    installer.installZip(temp, null)
                    temp.delete()
                } else {
                    installer.installMapFile(temp, if (name.endsWith(".map", ignoreCase = true)) name else "$region.map", null)
                }
                log("Map imported: $region")
                _job.value = MapJob.Installed(region)
            } catch (e: CancellationException) {
                throw e
            } catch (e: MapFileException) {
                log("Map import failed: ${e.problem}")
                _job.value = MapJob.Failed(region, e.problem, e.bytesNeeded)
            } catch (e: IOException) {
                log("Map import failed: $e")
                _job.value = MapJob.Failed(region, MapProblem.DAMAGED)
            } finally {
                withContext(Dispatchers.IO) { temp.delete() }
            }
        }
    }

    private suspend fun refreshNow() {
        val pending = settings.pendingMapDownload() ?: return
        val link = try {
            MapLink.parse(pending.link)
        } catch (_: MapLinkException) {
            settings.setPendingMapDownload(null)
            return
        }
        val status = withContext(Dispatchers.IO) { query(pending.downloadId) }
        when {
            status == null -> {
                // Cancelled from the notification, or the system dropped it.
                settings.setPendingMapDownload(null)
                if (_job.value is MapJob.Downloading) _job.value = null
            }
            status.state == DownloadManager.STATUS_SUCCESSFUL -> install(link, pending, status.file)
            status.state == DownloadManager.STATUS_FAILED -> {
                log("Map download failed: ${link.region}, reason ${status.reason}")
                downloads.remove(pending.downloadId)
                settings.setPendingMapDownload(null)
                _job.value = MapJob.Failed(link.region, null)
            }
            else -> _job.value = MapJob.Downloading(
                link.region,
                status.bytes,
                status.total,
                waitingForWifi = status.state == DownloadManager.STATUS_PAUSED && status.reason == DownloadManager.PAUSED_QUEUED_FOR_WIFI,
            )
        }
    }

    private suspend fun install(link: MapLink, pending: PendingMapDownload, file: File?) {
        _job.value = MapJob.Installing(link.region)
        try {
            if (file == null || !file.isFile) throw MapFileException(MapProblem.INCOMPLETE, "Downloaded file missing")
            val started = SystemClock.elapsedRealtime()
            val installed = installer.installZip(file, link.downloadUrl)
            log("Map installed: ${installed.joinToString { it.displayName }}, unpacked in ${(SystemClock.elapsedRealtime() - started) / 1000} s")
            _job.value = MapJob.Installed(link.region)
        } catch (e: CancellationException) {
            throw e
        } catch (e: MapFileException) {
            log("Map install failed: ${link.region}, ${e.problem}")
            _job.value = MapJob.Failed(link.region, e.problem, e.bytesNeeded)
        } catch (e: IOException) {
            log("Map install failed: ${link.region}, $e")
            _job.value = MapJob.Failed(link.region, MapProblem.DAMAGED)
        } finally {
            // Removing the download also deletes the zip.
            downloads.remove(pending.downloadId)
            withContext(Dispatchers.IO) { file?.delete() }
            settings.setPendingMapDownload(null)
        }
    }

    private class Status(val state: Int, val reason: Int, val bytes: Long, val total: Long?, val file: File?)

    private fun query(id: Long): Status? =
        downloads.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (!c.moveToFirst()) return null
            val local = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
            Status(
                state = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
                bytes = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)).takeIf { it > 0 },
                file = local?.toUri()?.path?.let(::File),
            )
        }

    private fun downloadDir(): File = context.getExternalFilesDir(DOWNLOADS) ?: File(context.filesDir, DOWNLOADS)

    private fun freeBytes(dir: File): Long = allocatableBytes(context, dir)

    private fun displayName(uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) {
        null
    } ?: uri.lastPathSegment

    private fun size(uri: Uri): Long? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    } catch (_: Exception) {
        null
    }

    private fun isZip(file: File): Boolean = file.inputStream().use { input ->
        val head = ByteArray(4)
        input.read(head) == 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() && head[2].toInt() == 3 && head[3].toInt() == 4
    }

    companion object {
        private const val DOWNLOADS = "downloads"
        private const val MARGIN = 64L * 1024 * 1024

        /** The link as the website wrote it, for storing: the parsed form round-trips through [MapLink.parse]. */
        private fun offerLink(link: MapLink) = "mf-V4-map://" + link.downloadUrl.removePrefix("https://")
    }
}
