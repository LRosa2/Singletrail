package io.github.lrosa2.singletrail.data.collections

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lrosa2.singletrail.data.db.SingletrailDatabase
import io.github.lrosa2.singletrail.data.gpx.androidGpxReader
import io.github.lrosa2.singletrail.data.routes.RouteRepository
import io.github.lrosa2.singletrail.line
import io.github.lrosa2.singletrail.newRoute
import io.github.lrosa2.singletrail.track
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CollectionRepositoryTest {

    private lateinit var db: SingletrailDatabase
    private lateinit var dir: File
    private lateinit var routes: RouteRepository
    private lateinit var collections: CollectionRepository

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, SingletrailDatabase::class.java).build()
        dir = File(context.cacheDir, "collections-test-${UUID.randomUUID()}").apply { mkdirs() }
        val reader = androidGpxReader()
        routes = RouteRepository(db, dir, { reader.read(it) }, clock = { 1_000L })
        collections = CollectionRepository(db, clock = { 2_000L })
    }

    @After
    fun tearDown() {
        db.close()
        dir.deleteRecursively()
    }

    private suspend fun east() = routes.add(newRoute(track("East", line(0 to 0, 1000 to 0))))
    private suspend fun north() = routes.add(newRoute(track("North", line(0 to 0, 0 to 1000))))
    private suspend fun counts() = collections.observeCounts().first().map { it.name to it.routes }

    @Test
    fun namesAreUniqueIgnoringCaseAndSpaces() = runTest {
        val a = collections.named("Waldegg loops")!!
        val b = collections.named("  waldegg   LOOPS ")!!
        assertEquals(a.id, b.id)
        assertEquals(listOf("Waldegg loops"), collections.all().map { it.name })
        assertNull(collections.named("   "))
    }

    @Test
    fun namesWithUmlautsAreUniqueIgnoringCase() = runTest {
        val a = collections.named("Hörnli")!!
        val b = collections.named("HÖRNLI")!!
        assertEquals(a.id, b.id)
        val c = collections.named("Säntis")!!
        assertFalse(collections.rename(c.id, "hÖrnli"))
    }

    @Test
    fun aRouteCanBeInSeveralCollectionsAndCountsFollow() = runTest {
        val east = east()
        val north = north()
        val komoot = collections.named("komoot")!!
        val toRide = collections.named("To ride")!!
        collections.add(komoot.id, listOf(east, north))
        collections.add(toRide.id, listOf(east))
        collections.add(toRide.id, listOf(east)) // Adding twice keeps one membership.

        assertEquals(listOf("komoot" to 2, "To ride" to 1), counts())
        assertEquals(mapOf(komoot.id to setOf(east, north), toRide.id to setOf(east)), collections.observeMembers().first())
        assertEquals(listOf("komoot", "To ride"), collections.observeForRoute(east).first().map { it.name })
        assertEquals(mapOf(east to listOf("komoot", "To ride"), north to listOf("komoot")), collections.namesByRoute().mapValues { it.value.sortedWith(String.CASE_INSENSITIVE_ORDER) })
    }

    @Test
    fun deletingARouteLeavesItsCollections() = runTest {
        val east = east()
        val north = north()
        val komoot = collections.named("komoot")!!
        collections.add(komoot.id, listOf(east, north))

        routes.delete(east)

        assertEquals(listOf("komoot" to 1), counts())
    }

    @Test
    fun deletingACollectionKeepsItsRoutes() = runTest {
        val east = east()
        val komoot = collections.named("komoot")!!
        collections.add(komoot.id, listOf(east))

        collections.delete(komoot.id)

        assertEquals(emptyList<Pair<String, Int>>(), counts())
        assertEquals(listOf(east), routes.all().map { it.id })
    }

    @Test
    fun renameRefusesBlankNamesAndNamesAnotherCollectionHas() = runTest {
        collections.named("komoot")!!
        val toRide = collections.named("To ride")!!

        assertFalse(collections.rename(toRide.id, "KOMOOT"))
        assertFalse(collections.rename(toRide.id, "  "))
        assertTrue(collections.rename(toRide.id, " Later "))
        assertTrue(collections.rename(toRide.id, "later")) // Its own name in another case is fine.
        assertEquals(listOf("komoot", "later"), collections.all().map { it.name })
    }

    @Test
    fun setForRouteKeepsExactlyTheGivenCollections() = runTest {
        val east = east()
        val a = collections.named("A")!!
        val b = collections.named("B")!!
        val c = collections.named("C")!!
        collections.add(a.id, listOf(east))
        collections.add(b.id, listOf(east))

        collections.setForRoute(east, setOf(b.id, c.id))
        assertEquals(listOf("B", "C"), collections.observeForRoute(east).first().map { it.name })

        collections.setForRoute(east, emptySet())
        assertEquals(emptyList<String>(), collections.observeForRoute(east).first().map { it.name })
    }

    @Test
    fun addToCreatesTheCollectionOnceAndEvenWithoutRoutes() = runTest {
        val east = east()
        val north = north()
        collections.addTo("SchweizMobil", listOf(east))
        collections.addTo("schweizmobil", listOf(north))
        collections.addTo("Empty", emptyList())

        assertEquals(listOf("Empty" to 0, "SchweizMobil" to 2), counts())
        assertEquals(listOf("Empty", "SchweizMobil"), collections.names())
    }
}
