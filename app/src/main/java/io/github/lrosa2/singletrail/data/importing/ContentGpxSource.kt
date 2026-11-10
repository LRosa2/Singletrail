package io.github.lrosa2.singletrail.data.importing

import android.content.ContentResolver
import android.provider.OpenableColumns
import androidx.core.net.toUri
import io.github.lrosa2.singletrail.gpx.GpxDocument
import io.github.lrosa2.singletrail.gpx.GpxReader
import io.github.lrosa2.singletrail.importing.GpxSource
import io.github.lrosa2.singletrail.importing.ImportRequest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException

/** Reads `content://` URIs from the share sheet, "open with" and the file picker (spec §8.4). */
class ContentGpxSource(
    private val resolver: ContentResolver,
    private val reader: GpxReader,
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

    override suspend fun load(request: ImportRequest): GpxDocument = withContext(io) {
        val uri = request.uri.toUri()
        // Only content URIs: file:// would bypass the sender's permission grant (spec S5).
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) throw FileNotFoundException("Not a content URI")
        resolver.openInputStream(uri)?.buffered()?.use { reader.read(it) }
            ?: throw FileNotFoundException("Provider returned no stream")
    }
}
