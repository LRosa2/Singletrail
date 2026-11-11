package io.github.lrosa2.singletrail.maps

import io.github.lrosa2.singletrail.data.db.MapFileEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipException
import java.util.zip.ZipFile

/** Caps for map zips (spec S3); the largest OpenAndroMaps regions unpack to a few GB. */
data class MapZipLimits(
    val maxEntries: Int = 64,
    /** Declared uncompressed size of the map entries together. */
    val maxTotalBytes: Long = 16L * 1024 * 1024 * 1024,
)

/** Where installed maps are listed (the `map_files` table). */
interface MapCatalog {
    suspend fun all(): List<MapFileEntity>
    suspend fun put(map: MapFileEntity)
    suspend fun remove(id: String)
}

/** What the startup check changed, by display name. */
data class MapCheck(val removed: List<String>, val added: List<String>)

/**
 * Puts map files into `maps/` under [root] (spec §7.2–7.3): unpacks a downloaded zip, or takes a
 * single picked file, checks each map's header, replaces an older map of the same name and lists
 * it in the [catalog]. One operation at a time.
 */
class MapInstaller(
    private val root: File,
    private val catalog: MapCatalog,
    /** Space that can be written in the given folder. */
    private val freeBytes: (File) -> Long,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val limits: MapZipLimits = MapZipLimits(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    val mapsDir = File(root, MAPS)

    /** Files being unpacked or copied in; on the same volume as the maps, so they can be moved in. */
    val incomingDir = File(mapsDir, ".incoming")
    private val lock = Mutex()

    fun fileOf(map: MapFileEntity) = File(root, map.path)

    /**
     * Installs every `.map` in [zip]; other entries are ignored and folders inside the zip don't
     * matter. Entry names are reduced to a plain file name, so none can point outside `maps/`.
     */
    suspend fun installZip(zip: File, sourceUrl: String?): List<MapFileEntity> = lock.withLock {
        withContext(io) {
            val archive = try {
                ZipFile(zip)
            } catch (e: ZipException) {
                throw MapFileException(MapProblem.NOT_A_ZIP, "Not a zip", e)
            }
            archive.use { z ->
                val all = z.entries().toList()
                if (all.size > limits.maxEntries) throw MapFileException(MapProblem.TOO_MANY_ENTRIES, "${all.size} entries")
                val maps = all.filter { !it.isDirectory && baseName(it.name).endsWith(MAP_SUFFIX, ignoreCase = true) }
                if (maps.isEmpty()) throw MapFileException(MapProblem.NO_MAP_IN_ZIP, "No map in the zip")
                if (maps.any { it.size < 0 }) throw MapFileException(MapProblem.DAMAGED, "Entry without a size")
                val total = maps.sumOf { it.size }
                if (total > limits.maxTotalBytes) throw MapFileException(MapProblem.TOO_LARGE, "$total bytes")
                requireSpace(total)
                incomingDir.mkdirs()
                maps.map { entry ->
                    val name = safeName(entry.name)
                    val temp = File(incomingDir, "$name.part")
                    try {
                        z.getInputStream(entry).use { input -> temp.outputStream().use { copyAtMost(input, it, entry.size) } }
                    } catch (e: Exception) {
                        temp.delete()
                        throw e
                    }
                    place(temp, name, sourceUrl)
                }
            }
        }
    }

    /** Moves a single map file (already copied next to the maps) in, if it is a valid map. */
    suspend fun installMapFile(file: File, fileName: String, sourceUrl: String?): MapFileEntity = lock.withLock {
        withContext(io) { place(file, safeName(fileName), sourceUrl) }
    }

    /** @throws MapFileException with [MapProblem.NO_SPACE] when [bytes] plus a margin don't fit */
    fun requireSpace(bytes: Long) {
        mapsDir.mkdirs()
        val needed = bytes + SPACE_MARGIN
        if (freeBytes(mapsDir) < needed) {
            throw MapFileException(MapProblem.NO_SPACE, "Needs $needed bytes", bytesNeeded = needed)
        }
    }

    suspend fun delete(id: String) = lock.withLock {
        withContext(io) {
            val map = catalog.all().firstOrNull { it.id == id } ?: return@withContext
            fileOf(map).delete()
            catalog.remove(id)
        }
    }

    /**
     * When the app starts (spec §7.3): forgets maps whose files were deleted outside the app, lists
     * valid map files that appeared in `maps/`, and clears what an interrupted install left behind.
     */
    suspend fun check(): MapCheck = lock.withLock {
        withContext(io) {
            val removed = mutableListOf<String>()
            for (map in catalog.all()) {
                if (!fileOf(map).isFile) {
                    catalog.remove(map.id)
                    removed += map.displayName
                }
            }
            val known = catalog.all().map { it.path }.toSet()
            val added = mutableListOf<String>()
            val files = mapsDir.listFiles { f -> f.isFile && f.name.endsWith(MAP_SUFFIX, ignoreCase = true) }.orEmpty()
            for (file in files.sortedBy { it.name }) {
                val path = "$MAPS/${file.name}"
                if (path in known) continue
                val header = try {
                    MapHeader.read(file)
                } catch (_: IOException) {
                    continue
                }
                val map = entity(newId(), file, path, header, sourceUrl = null)
                catalog.put(map)
                added += map.displayName
            }
            incomingDir.listFiles()?.forEach { it.deleteRecursively() }
            MapCheck(removed, added)
        }
    }

    private suspend fun place(temp: File, fileName: String, sourceUrl: String?): MapFileEntity {
        val header = try {
            MapHeader.read(temp)
        } catch (e: IOException) {
            temp.delete()
            throw e
        }
        val target = File(mapsDir, fileName)
        // Belt and braces: the name has no separators, but the result must stay in maps/.
        check(target.canonicalFile.parentFile == mapsDir.canonicalFile) { "Unsafe map name $fileName" }
        val path = "$MAPS/$fileName"
        val existing = catalog.all().firstOrNull { it.path == path }
        if (target.exists() && !target.delete()) throw IOException("Can't replace $fileName")
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        val map = entity(existing?.id ?: newId(), target, path, header, sourceUrl ?: existing?.sourceUrl)
        catalog.put(map)
        return map
    }

    private fun entity(id: String, file: File, path: String, header: MapHeader, sourceUrl: String?) = MapFileEntity(
        id = id,
        displayName = displayName(file.name),
        path = path,
        sizeBytes = file.length(),
        minLat = header.minLat,
        minLon = header.minLon,
        maxLat = header.maxLat,
        maxLon = header.maxLon,
        mapDate = header.createdAt,
        sourceUrl = sourceUrl,
        installedAt = clock(),
    )

    /** Copies [input], but a zip entry that turns out larger than it said is damaged (or a zip bomb). */
    private fun copyAtMost(input: InputStream, output: OutputStream, max: Long) {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > max) throw MapFileException(MapProblem.DAMAGED, "Entry larger than it says")
            output.write(buffer, 0, n)
        }
    }

    companion object {
        const val MAPS = "maps"
        private const val MAP_SUFFIX = ".map"
        private const val SPACE_MARGIN = 32L * 1024 * 1024

        private fun baseName(entry: String) = entry.substringAfterLast('/').substringAfterLast('\\')

        /** A plain file name: the entry's last part, odd characters replaced, always ending in `.map`. */
        fun safeName(entry: String): String {
            val stem = baseName(entry).let { if (it.endsWith(MAP_SUFFIX, ignoreCase = true)) it.dropLast(MAP_SUFFIX.length) else it }
            val clean = stem.replace(Regex("[^A-Za-z0-9 _-]"), "_").trim().ifEmpty { "map" }
            return clean + MAP_SUFFIX
        }

        // OpenAndroMaps names its files "<Region>_oam.osm.map"; safeName turns the dot into "_".
        private val OAM_SUFFIX = Regex("(_oam)?([._]osm)?$", RegexOption.IGNORE_CASE)

        /** "Czech_Republic_oam_osm.map" → "Czech Republic". */
        fun displayName(fileName: String) =
            fileName.removeSuffix(MAP_SUFFIX).replace(OAM_SUFFIX, "").replace('_', ' ').ifBlank { fileName }
    }
}
