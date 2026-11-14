package io.github.lrosa2.singletrail.data.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Every schema version upgrades to the current one without losing data (spec §8.2, §12.4). */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val name = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), SingletrailDatabase::class.java)

    private fun createVersion1() = helper.createDatabase(name, 1).use { db ->
        db.execSQL(
            """INSERT INTO routes (id, name, description, sourceKind, sourceRef, geometryHash, geometryHashReversed,
                   gpxPath, pointCount, distanceM, ascentM, descentM, minEleM, maxEleM, hasElevation,
                   startLat, startLon, endLat, endLon, minLat, minLon, maxLat, maxLon, isLoop,
                   rating, importedAt, updatedAt, lastRiddenAt, turnsAlgoVersion)
               VALUES ('r1', 'Hörnli loop', 'Steep', 'URL', 'https://example.org/r.gpx', 'h1', 'h1r',
                   'routes/r1.gpx', 120, 15000.0, 600.0, 600.0, 500.0, 1100.0, 1,
                   47.3, 8.9, 47.3, 8.9, 47.2, 8.8, 47.4, 9.0, 1,
                   4, 1000, 2000, 3000, 0)""",
        )
        db.execSQL(
            """INSERT INTO rides (id, routeId, routeNameSnapshot, direction, startMode, startedAt, endedAt, status,
                   distanceM, movingTimeS, ascentM, trackPath, segmentCount)
               VALUES ('ride1', 'r1', 'Hörnli loop', 'FORWARD', 'NEAREST_POINT', 2500, 2900, 'FINISHED',
                   14800.0, 3600, 590.0, 'rides/ride1.track', 1)""",
        )
    }

    @Test
    fun version1To2AddsAnEmptyOutlineAndKeepsRoutesAndRides() {
        createVersion1()

        helper.runMigrationsAndValidate(name, 2, true).use { db ->
            db.query("SELECT name, rating, lastRiddenAt, outline FROM routes WHERE id = 'r1'").use { row ->
                assertTrue(row.moveToFirst())
                assertEquals("Hörnli loop", row.getString(0))
                assertEquals(4, row.getInt(1))
                assertEquals(3000L, row.getLong(2))
                assertTrue(row.isNull(3))
            }
            db.query("SELECT routeId, distanceM FROM rides WHERE id = 'ride1'").use { row ->
                assertTrue(row.moveToFirst())
                assertEquals("r1", row.getString(0))
                assertEquals(14800.0, row.getDouble(1), 0.0)
            }
        }
    }

    @Test
    fun version2To3AddsEmptyCollectionTablesAndKeepsRoutes() {
        createVersion1()

        helper.runMigrationsAndValidate(name, 3, true).use { db ->
            db.query("SELECT COUNT(*) FROM collections").use { row ->
                assertTrue(row.moveToFirst())
                assertEquals(0, row.getInt(0))
            }
            db.query("SELECT COUNT(*) FROM route_collections").use { row ->
                assertTrue(row.moveToFirst())
                assertEquals(0, row.getInt(0))
            }
            db.query("SELECT name, rating FROM routes WHERE id = 'r1'").use { row ->
                assertTrue(row.moveToFirst())
                assertEquals("Hörnli loop", row.getString(0))
                assertEquals(4, row.getInt(1))
            }
        }
    }

    @Test
    fun theAppOpensAVersion1DatabaseAndReadsItsRoutes() = runTest {
        createVersion1()
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        val db = Room.databaseBuilder(context, SingletrailDatabase::class.java, name).build()
        try {
            val route = db.routes().get("r1")!!
            assertEquals("Hörnli loop", route.name)
            assertEquals(Direction.FORWARD, db.rides().get("ride1")!!.direction)
            assertNull(route.outline)
        } finally {
            db.close()
        }
    }
}
