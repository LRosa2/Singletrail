package io.github.lrosa2.singletrail.data.rides

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class TrackFileTest {

    @TempDir
    lateinit var dir: File

    private val file get() = File(dir, "rides/r1.track")

    private fun point(k: Int, segment: Int = 1, ele: Double? = 512.3) = TrackPoint(
        timeMs = 1_700_000_000_000 + k * 1000L,
        elapsedMs = 50_000L + k * 1000L,
        lat = 46.5 + k * 1e-5,
        lon = 7.0 + k * 1e-5,
        ele = ele,
        accuracy = 4.5f,
        speed = 5.25f,
        bearing = 181.5f,
        segment = segment,
    )

    @Test
    fun `points read back as written`() {
        TrackWriter(file, segment = 1).use { w -> repeat(3) { w.append(point(it)) } }
        assertEquals(List(3) { point(it) }, TrackReader.read(file))
    }

    @Test
    fun `missing values stay missing`() {
        val p = point(0, ele = null).copy(accuracy = null, speed = null, bearing = null)
        TrackWriter(file, segment = 1).use { it.append(p) }
        assertEquals(listOf(p), TrackReader.read(file))
    }

    @Test
    fun `a resumed ride appends a new segment`() {
        TrackWriter(file, segment = 1).use { w -> repeat(2) { w.append(point(it)) } }
        TrackWriter(file, segment = 2).use { w -> w.append(point(5, segment = 2)) }
        assertEquals(listOf(1, 1, 2), TrackReader.read(file).map { it.segment })
    }

    @Test
    fun `lines reach the file every ten points`() {
        val w = TrackWriter(file, segment = 1, clock = { 0L })
        repeat(9) { w.append(point(it)) }
        assertEquals(0, TrackReader.read(file).size)
        w.append(point(9))
        assertEquals(10, TrackReader.read(file).size)
        w.close()
    }

    @Test
    fun `lines reach the file every ten seconds`() {
        var now = 0L
        val w = TrackWriter(file, segment = 1, clock = { now })
        w.append(point(0))
        now = 9_999
        w.append(point(1))
        assertEquals(0, TrackReader.read(file).size)
        now = 10_000
        w.append(point(2))
        assertEquals(3, TrackReader.read(file).size)
        w.close()
    }

    @Test
    fun `a partial last line from a crash is ignored`() {
        TrackWriter(file, segment = 1).use { w -> repeat(2) { w.append(point(it)) } }
        file.appendText("1700000002000,52000,46.50002,7.0")
        assertEquals(2, TrackReader.read(file).size)
    }

    @Test
    fun `malformed lines are skipped`() {
        TrackWriter(file, segment = 1).use { it.append(point(0)) }
        file.appendText("garbage\n1,2,x,4,,,,,1\n")
        TrackWriter(file, segment = 1).use { it.append(point(1)) }
        assertEquals(listOf(point(0), point(1)), TrackReader.read(file))
    }

    @Test
    fun `a missing file reads as no points`() {
        assertEquals(emptyList<TrackPoint>(), TrackReader.read(File(dir, "none.track")))
    }

    @Test
    fun `recording stops when storage runs low and says so`() {
        var free = 100L * 1024 * 1024
        val w = TrackWriter(file, segment = 1, clock = { 0L }, freeBytes = { free })
        repeat(10) { w.append(point(it)) }
        assertFalse(w.stopped)
        free = 1024
        repeat(10) { w.append(point(10 + it)) }
        assertTrue(w.stopped)
        w.append(point(30))
        w.close()
        // What was flushed before storage ran low is kept; nothing after.
        assertEquals(10, TrackReader.read(file).size)
    }

    @Test
    fun `a write error stops recording instead of crashing`() {
        file.parentFile!!.mkdirs()
        file.mkdirs() // A directory where the file should be: opening it fails.
        val w = TrackWriter(file, segment = 1, clock = { 0L })
        repeat(10) { w.append(point(it)) }
        assertTrue(w.stopped)
        assertTrue(w.error is java.io.IOException)
        w.close()
    }
}
