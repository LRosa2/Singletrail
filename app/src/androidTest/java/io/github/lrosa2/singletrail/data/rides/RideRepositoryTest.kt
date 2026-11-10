package io.github.lrosa2.singletrail.data.rides

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lrosa2.singletrail.data.db.Direction
import io.github.lrosa2.singletrail.data.db.RideStatus
import io.github.lrosa2.singletrail.data.db.SingletrailDatabase
import io.github.lrosa2.singletrail.data.db.StartMode
import io.github.lrosa2.singletrail.data.gpx.androidGpxReader
import io.github.lrosa2.singletrail.data.routes.RouteRepository
import io.github.lrosa2.singletrail.line
import io.github.lrosa2.singletrail.newRoute
import io.github.lrosa2.singletrail.nav.engine.NavCheckpoint
import io.github.lrosa2.singletrail.nav.engine.RidePhase
import io.github.lrosa2.singletrail.track
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RideRepositoryTest {

    private lateinit var db: SingletrailDatabase
    private lateinit var dir: File
    private lateinit var routes: RouteRepository
    private lateinit var rides: RideRepository
    private var now = 5_000L

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, SingletrailDatabase::class.java).build()
        dir = File(context.cacheDir, "ride-test-${UUID.randomUUID()}").apply { mkdirs() }
        val reader = androidGpxReader()
        routes = RouteRepository(db, dir, { reader.read(it) }, clock = { now })
        rides = RideRepository(db, dir, clock = { now })
    }

    @After
    fun tearDown() {
        db.close()
        dir.deleteRecursively()
    }

    private suspend fun addRoute(): String = routes.add(newRoute(track("Loop", line(0 to 0, 1000 to 0))))

    @Test
    fun startingARideRecordsItInProgress() = runTest {
        val routeId = addRoute()
        val ride = rides.start(routeId, "Loop", reversed = true)
        assertEquals(RideStatus.IN_PROGRESS, ride.status)
        assertEquals(Direction.REVERSE, ride.direction)
        assertEquals(StartMode.NEAREST_POINT, ride.startMode)
        assertEquals("rides/${ride.id}.track", ride.trackPath)
        assertEquals(1, ride.segmentCount)
        assertEquals(ride, rides.inProgress())
    }

    @Test
    fun aResumeStartsTheNextSegment() = runTest {
        val ride = rides.start(addRoute(), "Loop", reversed = false)
        assertEquals(2, rides.startSegment(ride.id))
        assertEquals(3, rides.startSegment(ride.id))
        assertEquals(3, rides.get(ride.id)!!.segmentCount)
    }

    @Test
    fun finishingEndsTheRideAndMarksTheRouteRidden() = runTest {
        val routeId = addRoute()
        val ride = rides.start(routeId, "Loop", reversed = false)
        rides.checkpoints.save(RideCheckpoint(ride.id, routeId, 1, nav = NavCheckpoint(phase = RidePhase.ON_ROUTE, reversed = false, progressM = 1.0, highWaterM = 1.0)))
        now = 9_000L
        rides.finish(ride.id, completed = true, reversed = true, distanceM = 1_234.5, movingSeconds = 300.4)
        val done = rides.get(ride.id)!!
        assertEquals(RideStatus.FINISHED, done.status)
        assertEquals(9_000L, done.endedAt)
        assertEquals(Direction.REVERSE, done.direction)
        assertEquals(1_234.5, done.distanceM, 0.0)
        assertEquals(300L, done.movingTimeS)
        assertNull(rides.inProgress())
        assertEquals(9_000L, db.routes().get(routeId)!!.lastRiddenAt)
        assertNull(rides.checkpoints.load(ride.id))
    }

    @Test
    fun stoppingEarlyIsAbandoned() = runTest {
        val ride = rides.start(addRoute(), "Loop", reversed = false)
        rides.finish(ride.id, completed = false, reversed = false, distanceM = 10.0, movingSeconds = 5.0)
        assertEquals(RideStatus.ABANDONED, rides.get(ride.id)!!.status)
    }

    @Test
    fun aRideOutlivesItsRoute() = runTest {
        val routeId = addRoute()
        val ride = rides.start(routeId, "Loop", reversed = false)
        routes.delete(routeId)
        val kept = rides.get(ride.id)
        assertNotNull(kept)
        assertNull(kept!!.routeId)
        assertEquals("Loop", kept.routeNameSnapshot)
    }
}
