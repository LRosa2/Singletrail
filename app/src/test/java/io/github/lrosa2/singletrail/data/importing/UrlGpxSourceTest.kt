package io.github.lrosa2.singletrail.data.importing

import io.github.lrosa2.singletrail.data.db.SourceKind
import io.github.lrosa2.singletrail.doc
import io.github.lrosa2.singletrail.gpx.GpxParseException
import io.github.lrosa2.singletrail.gpx.GpxReader
import io.github.lrosa2.singletrail.gpx.GpxWriter
import io.github.lrosa2.singletrail.importing.DownloadException
import io.github.lrosa2.singletrail.importing.ImportRequest
import io.github.lrosa2.singletrail.importing.LoadedFile
import io.github.lrosa2.singletrail.line
import io.github.lrosa2.singletrail.track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.kxml2.io.KXmlParser
import java.io.ByteArrayInputStream
import java.io.InputStream

class UrlGpxSourceTest {

    @Test
    fun `only plain https links are accepted`() {
        assertEquals("https://example.org/a.gpx", UrlImport.check("  https://example.org/a.gpx \n"))
        assertNull(UrlImport.check("http://example.org/a.gpx"))
        assertNull(UrlImport.check("ftp://example.org/a.gpx"))
        assertNull(UrlImport.check("https://user:secret@example.org/a.gpx"))
        assertNull(UrlImport.check("https:///a.gpx"))
        assertNull(UrlImport.check("not a link"))
        assertNull(UrlImport.check("https://example.org/a b.gpx"))
        assertNull(UrlImport.check("https://" + "x".repeat(3000) + ".org"))
    }

    @Test
    fun `the file name comes from the link`() {
        assertEquals("Ridge loop.gpx", UrlImport.fileName("https://example.org/tracks/Ridge%20loop.gpx?dl=1"))
        assertEquals("example.org", UrlImport.fileName("https://example.org/"))
    }

    private val gpx = GpxWriter.writeToString(doc(track("Linked", line(0 to 0, 400 to 0)))).toByteArray()

    private class Fake(
        val code: Int = 200,
        val length: Long? = null,
        val body: () -> InputStream,
    ) : HttpFetcher {
        var asked: String? = null
        var userAgent: String? = null
        override fun open(url: String, userAgent: String): HttpResponse {
            asked = url
            this.userAgent = userAgent
            return HttpResponse(code, length, body())
        }
    }

    @TempDir
    lateinit var tempDir: java.io.File

    private val reader = GpxReader({ KXmlParser() })
    private fun source(fetcher: HttpFetcher) = UrlGpxSource(fetcher, { reader.read(it) }, "Singletrail/1.0", tempDir, Dispatchers.Unconfined)
    private fun request(url: String) = ImportRequest(url, SourceKind.URL, confirm = true)

    @Test
    fun `a GPX link is downloaded and read`() = runTest {
        val fetcher = Fake { ByteArrayInputStream(gpx) }
        val loaded = source(fetcher).load(request("https://example.org/a.gpx")) as LoadedFile.Gpx
        assertEquals("Linked", loaded.doc.tracks.single().name)
        assertEquals("https://example.org/a.gpx", fetcher.asked)
        assertEquals("Singletrail/1.0", fetcher.userAgent)
    }

    @Test
    fun `an http link is never fetched`() = runTest {
        val fetcher = Fake { ByteArrayInputStream(gpx) }
        assertThrows<DownloadException> { source(fetcher).load(request("http://example.org/a.gpx")) }
        assertNull(fetcher.asked)
    }

    @Test
    fun `an error response fails the download`() = runTest {
        val e = assertThrows<DownloadException> { source(Fake(code = 404) { ByteArrayInputStream(ByteArray(0)) }).load(request("https://example.org/a.gpx")) }
        assertEquals(DownloadException.Reason.HTTP_ERROR, e.reason)
    }

    @Test
    fun `a declared size over 20 MB is refused before reading`() = runTest {
        var read = false
        val fetcher = Fake(length = 21L * 1024 * 1024) { read = true; ByteArrayInputStream(gpx) }
        val e = assertThrows<DownloadException> { source(fetcher).load(request("https://example.org/a.gpx")) }
        assertEquals(DownloadException.Reason.TOO_LARGE, e.reason)
        assertEquals(true, read) // Opened, but the body is never parsed.
    }

    @Test
    fun `a body that turns out larger than 20 MB is cut off`() = runTest {
        val endless = object : InputStream() {
            override fun read(): Int = ' '.code
        }
        val e = assertThrows<GpxParseException> { source(Fake { endless }).load(request("https://example.org/a.gpx")) }
        assertEquals(GpxParseException.Reason.FILE_TOO_LARGE, e.reason)
    }

    @Test
    fun `content that is not GPX fails like a file`() = runTest {
        val e = assertThrows<GpxParseException> { source(Fake { ByteArrayInputStream("<html></html>".toByteArray()) }).load(request("https://example.org/a.gpx")) }
        assertEquals(GpxParseException.Reason.NOT_GPX, e.reason)
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun `a zip link is saved for the import queue, like a picked zip`() = runTest {
        val bytes = zipOf("a.gpx" to gpx, "b.gpx" to gpx)
        val loaded = source(Fake { ByteArrayInputStream(bytes) }).load(request("https://example.org/routes.zip")) as LoadedFile.Archive
        assertEquals(tempDir, loaded.file.parentFile)
        assertEquals(bytes.toList(), loaded.file.readBytes().toList())
    }

    @Test
    fun `a zip that turns out larger than 20 MB is refused and its partial file removed`() = runTest {
        val head = zipOf("a.gpx" to gpx)
        val endless = object : InputStream() {
            var at = 0
            override fun read(): Int = if (at < head.size) head[at++].toInt() and 0xFF else 0
        }
        val e = assertThrows<DownloadException> { source(Fake { endless }).load(request("https://example.org/routes.zip")) }
        assertEquals(DownloadException.Reason.TOO_LARGE, e.reason)
        assertEquals(emptyList<java.io.File>(), tempDir.listFiles().orEmpty().toList())
    }
}
