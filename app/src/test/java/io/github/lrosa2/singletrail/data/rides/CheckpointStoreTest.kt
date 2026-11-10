package io.github.lrosa2.singletrail.data.rides

import io.github.lrosa2.singletrail.nav.engine.NavCheckpoint
import io.github.lrosa2.singletrail.nav.engine.RidePhase
import io.github.lrosa2.singletrail.nav.engine.RouteRange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class CheckpointStoreTest {

    @TempDir
    lateinit var dir: File

    private val nav = NavCheckpoint(
        phase = RidePhase.OFF_ROUTE,
        reversed = true,
        loopStartM = 120.5,
        progressM = 830.0,
        highWaterM = 845.0,
        nextBarrier = 3,
        exitM = 820.0,
        offRouteDistanceM = 44.0,
        approached = listOf(0, 1),
        announced = listOf(0),
        skipped = listOf(RouteRange(100.0, 160.0)),
        rideDistanceM = 2_400.0,
        movingSeconds = 610.0,
    )

    @Test
    fun `a checkpoint reads back as saved`() {
        val store = CheckpointStore(dir)
        val cp = RideCheckpoint("ride-1", "route-9", segment = 2, simulated = true, nav = nav)
        store.save(cp)
        assertEquals(cp, store.load("ride-1"))
    }

    @Test
    fun `saving again replaces the checkpoint and leaves no temporary file`() {
        val store = CheckpointStore(dir)
        store.save(RideCheckpoint("ride-1", "route-9", segment = 1, nav = nav))
        val later = RideCheckpoint("ride-1", "route-9", segment = 1, nav = nav.copy(progressM = 900.0))
        store.save(later)
        assertEquals(later, store.load("ride-1"))
        assertEquals(listOf("ride-1.checkpoint.json"), File(dir, "rides").list()!!.toList())
    }

    @Test
    fun `a missing or damaged checkpoint reads as none`() {
        val store = CheckpointStore(dir)
        assertNull(store.load("nope"))
        File(dir, "rides").mkdirs()
        File(dir, "rides/bad.checkpoint.json").writeText("{\"rideId\":")
        assertNull(store.load("bad"))
    }

    @Test
    fun `delete removes it`() {
        val store = CheckpointStore(dir)
        store.save(RideCheckpoint("ride-1", "route-9", segment = 1, nav = nav))
        store.delete("ride-1")
        assertFalse(File(dir, "rides/ride-1.checkpoint.json").exists())
        assertNull(store.load("ride-1"))
    }
}
