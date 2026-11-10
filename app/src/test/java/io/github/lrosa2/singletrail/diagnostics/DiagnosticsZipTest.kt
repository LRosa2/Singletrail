package io.github.lrosa2.singletrail.diagnostics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipFile

class DiagnosticsZipTest {

    @TempDir
    lateinit var dir: File

    private fun setUp() {
        File(dir, "logs").mkdirs()
        File(dir, "logs/app.log").writeText("started")
        File(dir, "logs/app.log.1").writeText("older")
        File(dir, "logs/crash-123.txt").writeText("trace")
        File(dir, "traces").mkdirs()
        File(dir, "traces/ride1.jsonl").writeText("{\"type\":\"fix\"}")
        File(dir, "rides").mkdirs()
        File(dir, "rides/ride1.track").writeText("1,2,46.5,7.0")
    }

    private fun entries(file: File) = ZipFile(file).use { zip -> zip.entries().toList().map { it.name }.sorted() }

    @Test
    fun `logs only by default, never ride tracks`() {
        setUp()
        val out = File(dir, "d.zip")
        out.outputStream().use { DiagnosticsZip.write(dir, it, includeTraces = false) }
        assertEquals(listOf("logs/app.log", "logs/app.log.1", "logs/crash-123.txt"), entries(out))
    }

    @Test
    fun `traces only when asked for`() {
        setUp()
        val out = File(dir, "d.zip")
        out.outputStream().use { DiagnosticsZip.write(dir, it, includeTraces = true) }
        assertEquals(listOf("logs/app.log", "logs/app.log.1", "logs/crash-123.txt", "traces/ride1.jsonl"), entries(out))
    }

    @Test
    fun `no logs yet still makes a valid zip`() {
        val out = File(dir, "d.zip")
        out.outputStream().use { DiagnosticsZip.write(dir, it, includeTraces = true) }
        assertEquals(listOf("README.txt"), entries(out))
    }
}
