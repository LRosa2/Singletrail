package io.github.lrosa2.singletrail.maps

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File

class MapHeaderTest {
    @TempDir
    lateinit var dir: File

    private fun file(bytes: ByteArray) = File(dir, "test.map").apply { writeBytes(bytes) }

    private fun problem(bytes: ByteArray): MapProblem = assertThrows<MapFileException> { MapHeader.read(file(bytes)) }.problem

    @Test
    fun `reads the bounding box, date and version`() {
        val header = MapHeader.read(file(mapBytes()))
        assertEquals(5, header.version)
        assertEquals(1_793_750_400_000, header.createdAt)
        assertEquals(45.8, header.minLat, 1e-6)
        assertEquals(5.9, header.minLon, 1e-6)
        assertEquals(47.9, header.maxLat, 1e-6)
        assertEquals(10.5, header.maxLon, 1e-6)
        assertEquals("mapsforge-map-writer", header.createdBy)
    }

    @Test
    fun `older format versions are read too`() {
        assertEquals(3, MapHeader.read(file(mapBytes(version = 3, createdBy = null))).version)
    }

    @Test
    fun `a file that isn't a Mapsforge map is refused`() {
        assertEquals(MapProblem.NOT_A_MAP, problem(mapBytes(magic = "mapsforge binary XYZ")))
        assertEquals(MapProblem.NOT_A_MAP, problem("<gpx></gpx>".toByteArray()))
        assertEquals(MapProblem.NOT_A_MAP, problem(ByteArray(0)))
    }

    @Test
    fun `an unknown format version is refused`() {
        assertEquals(MapProblem.UNSUPPORTED_VERSION, problem(mapBytes(version = 2)))
        assertEquals(MapProblem.UNSUPPORTED_VERSION, problem(mapBytes(version = 6)))
    }

    @Test
    fun `a file shorter than its header says is incomplete`() {
        assertEquals(MapProblem.INCOMPLETE, problem(mapBytes(declaredSize = 4096)))
        assertEquals(MapProblem.INCOMPLETE, problem(mapBytes().copyOf(40)))
    }

    @Test
    fun `a file longer than its header says, or with an impossible area, is damaged`() {
        assertEquals(MapProblem.DAMAGED, problem(mapBytes(declaredSize = 1024)))
        assertEquals(MapProblem.DAMAGED, problem(mapBytes(minLat = 48.0, maxLat = 47.0)))
        assertEquals(MapProblem.DAMAGED, problem(mapBytes(maxLon = 181.0)))
    }

    @Test
    fun `a huge string length in the header is refused without reading it`() {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            write("mapsforge binary OSM".toByteArray())
            writeInt(100)
            writeInt(5)
            writeLong(2048)
            writeLong(1)
            repeat(4) { writeInt(0) }
            writeShort(256)
            writeVbe(Int.MAX_VALUE.toLong()) // projection name length
        }
        val bytes = out.toByteArray() + ByteArray(2048 - out.size())
        assertEquals(MapProblem.DAMAGED, problem(bytes))
    }
}
