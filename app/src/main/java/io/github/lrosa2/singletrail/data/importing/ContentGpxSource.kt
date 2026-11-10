package io.github.lrosa2.singletrail.data.importing

import android.content.ContentResolver
import android.provider.OpenableColumns
import androidx.core.net.toUri
import io.github.lrosa2.singletrail.backup.ArchiveException
import io.github.lrosa2.singletrail.gpx.GpxReader
import io.github.lrosa2.singletrail.importing.GpxSource
import io.github.lrosa2.singletrail.importing.ImportRequest
import io.github.lrosa2.singletrail.importing.LoadedFile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/**
 * Reads `content://` URIs from the share sheet, "open with" and the file picker (spec §8.4). A zip
 * (a backup or several routes) is copied to [tempDir] for the import queue; anything else must be GPX.
 */
class ContentGpxSource(
    private val resolver: ContentResolver,
    private val reader: GpxReader,
    private val tempDir: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : GpxSource {

    override suspend fun displayName(request: ImportRequest): String? = withContext(io) {
        try {
            resolver.query(request.uri.toUri(), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (e: RuntimeException) {
            null
        }
    }

    override suspend fun load(request: ImportRequest): LoadedFile = withContext(io) {
        val uri = request.uri.toUri()
        // Only content URIs: file:// would bypass the sender's permission grant (spec S5).
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) throw FileNotFoundException("Not a content URI")
        val stream = resolver.openInputStream(uri) ?: throw FileNotFoundException("Provider returned no stream")
        BufferedInputStream(stream).use { input ->
            if (isZip(input)) LoadedFile.Archive(copyToTemp(input)) else LoadedFile.Gpx(reader.read(input))
        }
    }

    /** Peeks at the zip signature "PK\u0003\u0004" and rewinds. */
    private fun isZip(input: BufferedInputStream): Boolean {
        input.mark(ZIP_MAGIC.size)
        val head = ByteArray(ZIP_MAGIC.size)
        var n = 0
        while (n < head.size) {
            val read = input.read(head, n, head.size - n)
            if (read < 0) break
            n += read
        }
        input.reset()
        return n == ZIP_MAGIC.size && head.contentEquals(ZIP_MAGIC)
    }

    @Suppress("UsableSpace") // A rough check before a copy into the app's own cache.
    private fun copyToTemp(input: InputStream): File {
        tempDir.mkdirs()
        if (tempDir.usableSpace < MIN_FREE_BYTES) throw IOException("Not enough free space")
        val file = File(tempDir, "${UUID.randomUUID()}.zip")
        try {
            file.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > MAX_ZIP_BYTES) throw ArchiveException(ArchiveException.Reason.TOO_LARGE, "Zip larger than $MAX_ZIP_BYTES bytes")
                    out.write(buffer, 0, n)
                }
            }
        } catch (e: IOException) {
            file.delete()
            throw e
        }
        return file
    }

    private companion object {
        val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
        const val MAX_ZIP_BYTES = 2L * 1024 * 1024 * 1024
        const val MIN_FREE_BYTES = 100L * 1024 * 1024
    }
}
