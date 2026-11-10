package io.github.lrosa2.singletrail.data.routes

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lrosa2.singletrail.data.db.SingletrailDatabase
import io.github.lrosa2.singletrail.data.db.SourceKind
import io.github.lrosa2.singletrail.data.gpx.androidGpxReader
import io.github.lrosa2.singletrail.line
import io.github.lrosa2.singletrail.newRoute
import io.github.lrosa2.singletrail.nav.RouteIdentity
import io.github.lrosa2.singletrail.nav.RoutePoint
import io.github.lrosa2.singletrail.track
import io.github.lrosa2.singletrail.waypointAt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RouteRepositoryTest {

    private lateinit var db: SingletrailDatabase
    private lateinit var dir: File
    private lateinit var repo: RouteRepository
    private var now = 1_000L

    /** 1 km east, climbing 50 m, with a waypoint halfway. */
    private val east = newRoute(
        track("East", line(0 to 0, 1000 to 0, ele = { 600 + it / 20 }), "Along the ridge"),
        waypoints = listOf(waypointAt(500, 20, "Bench")),
    )

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, SingletrailDatabase::class.java).build()
        dir = File(context.cacheDir, "repo-test-${UUID.randomUUID()}").apply { mkdirs() }
        val reader = androidGpxReader()
        repo = RouteRepository(db, dir, { reader.read(it) }, clock = { now })
    }

    @After
    fun tearDown() {
        db.close()
        dir.deleteRecursively()
    }

    private fun routeFiles() = File(dir, "routes").listFiles().orEmpty().map { it.name }.sorted()

    @Test
    fun addStoresTheRowWaypointsAndANormalisedGpxCopy() = runTest {
        val id = repo.add(east)

        val row = repo.observeRoute(id).first()!!
        assertEquals("East", row.name)
        assertEquals("Along the ridge", row.description)
        assertEquals(SourceKind.PICKER, row.sourceKind)
        assertEquals(1000.0, row.distanceM, 1.0)
        assertEquals(now, row.importedAt)
        assertEquals(listOf("Bench"), repo.waypoints(id).map { it.name })
        assertEquals(listOf("$id.gpx"), routeFiles())

        // The stored copy reads back as the same geometry.
        val stored = repo.gpxFile(row).inputStream().use { androidGpxReader().read(it) }
        val points = stored.tracks.single().points.map { RoutePoint(it.lat, it.lon) }
        assertEquals(row.geometryHash, RouteIdentity.hashes(points).forward)
        assertEquals(1000.0, repo.prepared(id).lengthMeters, 1.0)
    }

    @Test
    fun findsRoutesByGeometryHash() = runTest {
        val id = repo.add(east)
        assertEquals(id, repo.findByGeometryHash(east.hashes.forward)?.id)
        assertNull(repo.findByGeometryHash(east.hashes.reversed))
    }

    @Test
    fun similarCandidatesNeedAboutTheSameLengthAndAnOverlappingArea() = runTest {
        val id = repo.add(east)
        val shifted = newRoute(track("Shifted", line(0 to 3, 1000 to 3))).prepared
        val farAway = newRoute(track("Far", line(5000 to 5000, 6000 to 5000))).prepared
        val longer = newRoute(track("Longer", line(0 to 0, 1000 to 0, 1000 to 500))).prepared

        assertEquals(listOf(id), repo.similarCandidates(shifted).map { it.id })
        assertTrue(repo.similarCandidates(farAway).isEmpty())
        assertTrue(repo.similarCandidates(longer).isEmpty())
    }

    @Test
    fun aDuplicateGeometryIsRejectedWithoutLeavingAFile() = runTest {
        repo.add(east)
        assertThrows(SQLiteConstraintException::class.java) { kotlinx.coroutines.runBlocking { repo.add(east) } }
        assertEquals(1, routeFiles().size)
    }

    @Test
    fun replacingKeepsNameStarsAndSwapsTheGeometryAndFile() = runTest {
        val id = repo.add(east)
        repo.setRating(id, 4)
        repo.edit(id, "My ridge", "Mine")
        val oldFile = repo.gpxFile(repo.observeRoute(id).first()!!)

        now = 2_000L
        val longer = newRoute(
            track("East v2", line(0 to 0, 1000 to 0, 1000 to 400)),
            waypoints = listOf(waypointAt(1000, 200, "Spring"), waypointAt(1000, 390, "Hut")),
        )
        repo.replace(id, longer)

        val row = repo.observeRoute(id).first()!!
        assertEquals("My ridge", row.name)
        assertEquals("Mine", row.description)
        assertEquals(4, row.rating)
        assertEquals(1400.0, row.distanceM, 1.0)
        assertEquals(longer.hashes.forward, row.geometryHash)
        assertEquals(1_000L, row.importedAt)
        assertEquals(2_000L, row.updatedAt)
        assertEquals(listOf("Spring", "Hut"), repo.waypoints(id).map { it.name })
        assertFalse(oldFile.exists())
        assertTrue(repo.gpxFile(row).exists())
        assertEquals(1, routeFiles().size)
        assertEquals(1400.0, repo.prepared(id).lengthMeters, 1.0)
    }

    @Test
    fun deleteRemovesTheRowWaypointsAndFile() = runTest {
        val id = repo.add(east)
        repo.delete(id)

        assertNull(repo.observeRoute(id).first())
        assertTrue(repo.waypoints(id).isEmpty())
        assertTrue(routeFiles().isEmpty())
    }

    @Test
    fun starsAreOneToFiveOrNone() = runTest {
        val id = repo.add(east)
        repo.setRating(id, 5)
        assertEquals(5, repo.observeRoute(id).first()!!.rating)
        repo.setRating(id, null)
        assertNull(repo.observeRoute(id).first()!!.rating)
        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { repo.setRating(id, 6) } }
    }

    @Test
    fun editTrimsAndRejectsABlankName() = runTest {
        val id = repo.add(east)
        repo.edit(id, "  Ridge  ", "  ")
        val row = repo.observeRoute(id).first()!!
        assertEquals("Ridge", row.name)
        assertNull(row.description)
        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { repo.edit(id, " ", null) } }
        assertNotEquals("", repo.observeRoute(id).first()!!.name)
    }
}
