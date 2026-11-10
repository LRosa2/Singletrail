package io.github.lrosa2.singletrail.backup

import io.github.lrosa2.singletrail.gpx.GpxDocument
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile

/** Caps for untrusted zips (spec §9 S3). */
data class ArchiveLimits(
    val maxEntries: Int = 10_000,
    /** Declared uncompressed size of all entries together. */
    val maxTotalBytes: Long = 2L * 1024 * 1024 * 1024,
    val maxJsonBytes: Long = 16L * 1024 * 1024,
    /** The GPX reader enforces its own 20 MB cap on top. */
    val maxGpxBytes: Long = 21L * 1024 * 1024,
)

class ArchiveException(val reason: Reason, message: String, cause: Throwable? = null) : IOException(message, cause) {
    enum class Reason { NOT_A_ZIP, TOO_MANY_ENTRIES, TOO_LARGE, ENTRY_TOO_LARGE, DAMAGED }
}

/**
 * A Singletrail zip opened for import: a full backup or routes to import. Entries are read in
 * memory one at a time and never written out by their own names, so a crafted path can't escape;
 * such entries, and anything that isn't GPX or our JSON, are ignored anyway.
 */
class BackupArchive private constructor(
    private val zip: ZipFile,
    private val entries: Map<String, ZipEntry>,
    private val order: List<String>,
    private val readGpx: (InputStream) -> GpxDocument,
    private val limits: ArchiveLimits,
) : Closeable {

    val manifest: BackupManifest? = entries["manifest.json"]?.let { json(it, BackupManifest.serializer()) }

    val kind: BackupKind = if (manifest?.kind == BackupManifest.KIND_FULL) BackupKind.FULL else BackupKind.ROUTES

    fun routes(): List<BackupRoute> = entries["routes.json"]?.let { json(it, ListSerializer(BackupRoute.serializer())) }.orEmpty()

    fun rides(): List<BackupRide> = entries["rides.json"]?.let { json(it, ListSerializer(BackupRide.serializer())) }.orEmpty()

    fun settings(): BackupSettings? = entries["settings.json"]?.let { json(it, BackupSettings.serializer()) }

    /** GPX entries in zip order: the routes of a routes zip. */
    fun gpxEntries(): List<String> = order.filter { it.endsWith(".gpx", ignoreCase = true) }

    fun has(name: String): Boolean = name in entries

    /** @throws io.github.lrosa2.singletrail.gpx.GpxParseException for unsafe or invalid GPX */
    fun gpx(name: String): GpxDocument {
        val entry = entries[name] ?: throw ArchiveException(ArchiveException.Reason.DAMAGED, "No entry $name")
        return zip.getInputStream(entry).use { readGpx(Bounded(it, limits.maxGpxBytes, name)) }
    }

    override fun close() = zip.close()

    private fun <T> json(entry: ZipEntry, serializer: KSerializer<T>): T {
        val bytes = zip.getInputStream(entry).use { Bounded(it, limits.maxJsonBytes, entry.name).readBytes() }
        return try {
            BackupJson.decodeFromString(serializer, bytes.toString(Charsets.UTF_8))
        } catch (e: SerializationException) {
            throw ArchiveException(ArchiveException.Reason.DAMAGED, "Unreadable ${entry.name}", e)
        } catch (e: IllegalArgumentException) {
            throw ArchiveException(ArchiveException.Reason.DAMAGED, "Unreadable ${entry.name}", e)
        }
    }

    /** Stops reading past [max] bytes, whatever the entry claimed its size was. */
    private class Bounded(input: InputStream, private val max: Long, private val name: String) : java.io.FilterInputStream(input) {
        private var count = 0L

        override fun read(): Int {
            val b = super.read()
            if (b >= 0) count(1)
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) count(n.toLong())
            return n
        }

        private fun count(n: Long) {
            count += n
            if (count > max) throw ArchiveException(ArchiveException.Reason.ENTRY_TOO_LARGE, "$name is larger than $max bytes")
        }
    }

    companion object {
        private val allowedJson = setOf("manifest.json", "routes.json", "rides.json", "settings.json")

        fun open(file: File, readGpx: (InputStream) -> GpxDocument, limits: ArchiveLimits = ArchiveLimits()): BackupArchive {
            val zip = try {
                ZipFile(file)
            } catch (e: ZipException) {
                throw ArchiveException(ArchiveException.Reason.NOT_A_ZIP, "Not a zip", e)
            }
            try {
                val all = zip.entries().toList()
                if (all.size > limits.maxEntries) {
                    throw ArchiveException(ArchiveException.Reason.TOO_MANY_ENTRIES, "${all.size} entries")
                }
                if (all.sumOf { it.size.coerceAtLeast(0) } > limits.maxTotalBytes) {
                    throw ArchiveException(ArchiveException.Reason.TOO_LARGE, "Archive too large")
                }
                val kept = all.filter { !it.isDirectory && isSafe(it.name) && isExpected(it.name) }
                return BackupArchive(zip, kept.associateBy { it.name }, kept.map { it.name }, readGpx, limits)
            } catch (e: Exception) {
                zip.close()
                throw e
            }
        }

        private fun isSafe(name: String): Boolean =
            name.isNotEmpty() && !name.startsWith("/") && '\\' !in name && name.split('/').none { it == ".." }

        private fun isExpected(name: String): Boolean = name in allowedJson || name.endsWith(".gpx", ignoreCase = true)
    }
}
