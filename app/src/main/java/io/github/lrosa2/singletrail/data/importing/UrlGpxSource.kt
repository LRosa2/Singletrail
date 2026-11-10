package io.github.lrosa2.singletrail.data.importing

import io.github.lrosa2.singletrail.gpx.GpxDocument
import io.github.lrosa2.singletrail.importing.DownloadException
import io.github.lrosa2.singletrail.importing.GpxSource
import io.github.lrosa2.singletrail.importing.ImportRequest
import io.github.lrosa2.singletrail.importing.LoadedFile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.InputStream
import java.net.URI
import java.net.URISyntaxException
import java.net.URL
import java.net.URLDecoder
import javax.net.ssl.HttpsURLConnection

/** URL import (spec §8.4, S4): https only, at most 20 MB, and it must be GPX. Never shown as a page. */
object UrlImport {
    private const val MAX_LENGTH = 2048
    const val MAX_BYTES = 20L * 1024 * 1024

    /** The link, trimmed, if it is a plain https URL with a host and no credentials; else null. */
    fun check(text: String): String? {
        val link = text.trim()
        if (link.length > MAX_LENGTH || link.any { it.isWhitespace() }) return null
        val uri = try {
            URI(link)
        } catch (_: URISyntaxException) {
            return null
        }
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrEmpty() || uri.rawUserInfo != null) return null
        return link
    }

    /** A name for messages: the link's last path segment, else its host. */
    fun fileName(url: String): String {
        val uri = try {
            URI(url)
        } catch (_: URISyntaxException) {
            return url
        }
        val last = uri.rawPath?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }
        return last?.let { URLDecoder.decode(it, Charsets.UTF_8.name()) } ?: uri.host ?: url
    }
}

class HttpResponse(val code: Int, val contentLength: Long?, val body: InputStream, private val onClose: () -> Unit = {}) : Closeable {
    override fun close() {
        try {
            body.close()
        } finally {
            onClose()
        }
    }
}

fun interface HttpFetcher {
    fun open(url: String, userAgent: String): HttpResponse
}

/** Downloads with the platform's HTTPS stack. Redirects are followed only from https to https. */
class UrlConnectionFetcher : HttpFetcher {
    override fun open(url: String, userAgent: String): HttpResponse {
        val connection = URL(url).openConnection() as? HttpsURLConnection
            ?: throw DownloadException(DownloadException.Reason.NOT_HTTPS, "Not an https link")
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("User-Agent", userAgent)
        connection.setRequestProperty("Accept", "application/gpx+xml, application/xml;q=0.9, */*;q=0.5")
        val code = connection.responseCode
        if (connection.url.protocol != "https") {
            connection.disconnect()
            throw DownloadException(DownloadException.Reason.NOT_HTTPS, "Redirected away from https")
        }
        val body = if (code in 200..299) connection.inputStream else connection.errorStream ?: ByteArrayInputStream(ByteArray(0))
        return HttpResponse(code, connection.contentLengthLong.takeIf { it >= 0 }, body) { connection.disconnect() }
    }
}

class UrlGpxSource(
    private val fetcher: HttpFetcher,
    private val readGpx: (InputStream) -> GpxDocument,
    private val userAgent: String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : GpxSource {

    override suspend fun displayName(request: ImportRequest): String = UrlImport.fileName(request.uri)

    override suspend fun load(request: ImportRequest): LoadedFile = withContext(io) {
        val url = UrlImport.check(request.uri) ?: throw DownloadException(DownloadException.Reason.NOT_HTTPS, "Not an https link")
        fetcher.open(url, userAgent).use { response ->
            if (response.code !in 200..299) throw DownloadException(DownloadException.Reason.HTTP_ERROR, "HTTP ${response.code}")
            if ((response.contentLength ?: 0) > UrlImport.MAX_BYTES) {
                throw DownloadException(DownloadException.Reason.TOO_LARGE, "Larger than ${UrlImport.MAX_BYTES} bytes")
            }
            // The GPX reader stops at 20 MB whatever the server said.
            LoadedFile.Gpx(readGpx(response.body))
        }
    }
}

/** Sends https links to [urls] and everything else to [content]. */
class RoutingGpxSource(private val content: GpxSource, private val urls: GpxSource) : GpxSource {
    private fun pick(request: ImportRequest) = if (request.uri.startsWith("https://", ignoreCase = true)) urls else content

    override suspend fun displayName(request: ImportRequest): String? = pick(request).displayName(request)

    override suspend fun load(request: ImportRequest): LoadedFile = pick(request).load(request)
}

