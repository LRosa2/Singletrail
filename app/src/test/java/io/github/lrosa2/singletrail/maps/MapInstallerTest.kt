package io.github.lrosa2.singletrail.maps

import io.github.lrosa2.singletrail.data.db.MapFileEntity
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class MapInstallerTest {
    @TempDir
    lateinit var root: File

    private val catalog = FakeCatalog()
    private var free = 10L * 1024 * 1024 * 1024
    private var now = 1000L
    private var ids = 0
    private lateinit var installer: MapInstaller
    private val mapsDir get() = File(root, "maps")

    @BeforeEach
    fun setUp() {
        installer = installer()
    }

    private fun installer(limits: MapZipLimits = MapZipLimits()) =
        MapInstaller(root, catalog, freeBytes = { free }, clock = { now }, newId = { "id${++ids}" }, limits = limits)

    private fun download(vararg entries: Pair<String, ByteArray>) = zipOf(File(root, "download.zip"), *entries)

    private suspend fun problem(block: suspend () -> Unit): MapProblem {
        val e = runCatching { block() }.exceptionOrNull()
        assertTrue(e is MapFileException, "expected a MapFileException, got $e")
        return (e as MapFileException).problem
    }

    @Test
    fun `a downloaded zip installs its map`() = runTest {
        val installed = installer.installZip(download("Switzerland.map" to mapBytes()), "https://ftp.gwdg.de/x/Switzerland.zip")
        val map = installed.single()
        assertEquals("Switzerland", map.displayName)
        assertEquals("maps/Switzerland.map", map.path)
        assertEquals(2048, map.sizeBytes)
        assertEquals(45.8, map.minLat, 1e-6)
        assertEquals(10.5, map.maxLon, 1e-6)
        assertEquals(1_793_750_400_000, map.mapDate)
        assertEquals("https://ftp.gwdg.de/x/Switzerland.zip", map.sourceUrl)
        assertEquals(1000, map.installedAt)
        assertTrue(File(mapsDir, "Switzerland.map").isFile)
        assertEquals(listOf(map), catalog.all())
        assertEquals(emptyList<String>(), File(mapsDir, ".incoming").list().orEmpty().toList())
    }

    @Test
    fun `other files in the zip are ignored and folders don't matter`() = runTest {
        val installed = installer.installZip(download("readme.txt" to "hi".toByteArray(), "maps/Czech_Republic.map" to mapBytes()), null)
        assertEquals("Czech Republic", installed.single().displayName)
        assertEquals(listOf("Czech_Republic.map"), mapsDir.list()!!.filter { it.endsWith(".map") })
    }

    @Test
    fun `OpenAndroMaps file names read as just the region`() = runTest {
        val installed = installer.installZip(download("UK-IsleOfMan_oam.osm.map" to mapBytes()), null)
        assertEquals("UK-IsleOfMan", installed.single().displayName)
        assertEquals("Czech Republic", MapInstaller.displayName("Czech_Republic_oam_osm.map"))
        assertEquals("Malta", MapInstaller.displayName("Malta.map"))
    }

    @Test
    fun `an entry name can't escape the maps folder`() = runTest {
        installer.installZip(download("../../evil.map" to mapBytes()), null)
        assertTrue(File(mapsDir, "evil.map").isFile)
        assertFalse(File(root.parentFile, "evil.map").exists())
    }

    @Test
    fun `a zip without a map, or not a zip at all, is refused`() = runTest {
        assertEquals(MapProblem.NO_MAP_IN_ZIP, problem { installer.installZip(download("readme.txt" to "hi".toByteArray()), null) })
        val notZip = File(root, "x.zip").apply { writeText("hello") }
        assertEquals(MapProblem.NOT_A_ZIP, problem { installer.installZip(notZip, null) })
    }

    @Test
    fun `too many entries or too many bytes are refused before unpacking`() = runTest {
        val zip = download("a.map" to mapBytes(), "b.map" to mapBytes(), "c.txt" to ByteArray(1))
        assertEquals(MapProblem.TOO_MANY_ENTRIES, problem { installer(MapZipLimits(maxEntries = 2)).installZip(zip, null) })
        assertEquals(MapProblem.TOO_LARGE, problem { installer(MapZipLimits(maxTotalBytes = 3000)).installZip(zip, null) })
        assertTrue(catalog.all().isEmpty())
    }

    @Test
    fun `without enough free space nothing is written`() = runTest {
        free = 1000
        val e = runCatching { installer.installZip(download("Switzerland.map" to mapBytes()), null) }.exceptionOrNull() as MapFileException
        assertEquals(MapProblem.NO_SPACE, e.problem)
        assertTrue(catalog.all().isEmpty())
        assertFalse(File(mapsDir, "Switzerland.map").exists())
    }

    @Test
    fun `a broken map in the zip leaves nothing behind`() = runTest {
        assertEquals(MapProblem.NOT_A_MAP, problem { installer.installZip(download("Switzerland.map" to ByteArray(3000)), null) })
        assertTrue(catalog.all().isEmpty())
        assertEquals(emptyList<String>(), mapsDir.walk().filter { it.isFile }.map { it.name }.toList())
    }

    @Test
    fun `installing a region again replaces its map and keeps its id`() = runTest {
        val first = installer.installZip(download("Switzerland.map" to mapBytes(createdAt = 1)), "https://a").single()
        now = 2000
        val second = installer.installZip(download("Switzerland.map" to mapBytes(createdAt = 2, totalSize = 4096)), null).single()
        assertEquals(first.id, second.id)
        assertEquals(2L, second.mapDate)
        assertEquals(4096, File(mapsDir, "Switzerland.map").length())
        assertEquals("https://a", second.sourceUrl)
        assertEquals(1, catalog.all().size)
    }

    @Test
    fun `a single map file is validated and moved in`() = runTest {
        val picked = File(root, "picked.part").apply { writeBytes(mapBytes()) }
        val map = installer.installMapFile(picked, "Austria.map", null)
        assertEquals("Austria", map.displayName)
        assertTrue(File(mapsDir, "Austria.map").isFile)
        assertFalse(picked.exists())

        val bad = File(root, "bad.part").apply { writeText("<gpx/>") }
        assertEquals(MapProblem.NOT_A_MAP, problem { installer.installMapFile(bad, "Bad.map", null) })
        assertFalse(bad.exists())
        assertFalse(File(mapsDir, "Bad.map").exists())
    }

    @Test
    fun `deleting a map removes its file and its row`() = runTest {
        val map = installer.installZip(download("Switzerland.map" to mapBytes()), null).single()
        installer.delete(map.id)
        assertTrue(catalog.all().isEmpty())
        assertFalse(File(mapsDir, "Switzerland.map").exists())
    }

    @Test
    fun `the startup check notices maps deleted or added outside the app`() = runTest {
        val gone = installer.installZip(download("Switzerland.map" to mapBytes()), null).single()
        File(mapsDir, "Switzerland.map").delete()
        File(mapsDir, "Italy.map").writeBytes(mapBytes())
        File(mapsDir, "Broken.map").writeText("nope")
        File(mapsDir, ".incoming").mkdirs()
        File(mapsDir, ".incoming/left-over.part").writeText("x")

        val check = installer.check()

        assertEquals(listOf("Switzerland"), check.removed)
        assertEquals(listOf("Italy"), check.added)
        assertEquals(listOf("Italy"), catalog.all().map { it.displayName })
        assertNotEquals(gone.id, catalog.all().single().id)
        assertEquals(emptyList<String>(), File(mapsDir, ".incoming").list().orEmpty().toList())
    }

    private class FakeCatalog : MapCatalog {
        private val rows = linkedMapOf<String, MapFileEntity>()
        override suspend fun all() = rows.values.toList()
        override suspend fun put(map: MapFileEntity) {
            rows[map.id] = map
        }
        override suspend fun remove(id: String) {
            rows.remove(id)
        }
    }
}
