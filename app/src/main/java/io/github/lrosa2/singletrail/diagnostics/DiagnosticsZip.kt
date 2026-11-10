package io.github.lrosa2.singletrail.diagnostics

import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * "Share diagnostics" (spec §11): the app log and crash files, which hold no positions (§9.2), and
 * the per-ride debug traces only when the user includes them, since those do.
 */
object DiagnosticsZip {
    fun write(filesDir: File, out: OutputStream, includeTraces: Boolean) {
        val files = buildList {
            addAll(filesIn(filesDir, "logs"))
            if (includeTraces) addAll(filesIn(filesDir, "traces"))
        }
        val zip = ZipOutputStream(out)
        if (files.isEmpty()) {
            zip.putNextEntry(ZipEntry("README.txt"))
            zip.write("No logs or traces were recorded on this phone.\n".toByteArray())
            zip.closeEntry()
        }
        for ((name, file) in files) {
            zip.putNextEntry(ZipEntry(name))
            file.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
        zip.finish()
    }

    fun hasTraces(filesDir: File): Boolean = filesIn(filesDir, "traces").isNotEmpty()

    private fun filesIn(filesDir: File, folder: String): List<Pair<String, File>> =
        File(filesDir, folder).listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }.map { "$folder/${it.name}" to it }
}
