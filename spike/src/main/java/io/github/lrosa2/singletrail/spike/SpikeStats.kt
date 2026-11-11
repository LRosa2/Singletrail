package io.github.lrosa2.singletrail.spike

import java.util.Locale
import kotlin.math.max

/**
 * Frame and tile counters, fed from the renderers' own threads (Mapsforge: UI thread and its
 * layer thread; VTM: the GL thread).
 *
 * "Frames" are frames that showed the map; "map frames" are times the map content itself was
 * redrawn (Mapsforge redraws its layers on a separate thread and blits the result with the current
 * rotation in every frame; for VTM both are the same).
 */
class SpikeStats {
    private val lock = Any()

    private var lastFrame = 0L
    private var frames = 0
    private var mapFrames = 0
    private val window = LongArray(8192)
    private var windowCount = 0

    /** Whole-run histogram of frame intervals in 0.5 ms steps, up to 1 s. */
    private val histogram = IntArray(2001)
    private var totalFrames = 0L
    private var totalIntervals = 0L

    private var tiles = 0
    private var totalTiles = 0
    private var armed = false
    private var tilesSinceArmed = 0
    private var firstCompleteNanos = 0L

    fun onFrame(nanos: Long) = synchronized(lock) {
        if (lastFrame != 0L) {
            val interval = nanos - lastFrame
            if (windowCount < window.size) window[windowCount++] = interval
            histogram[(interval / 500_000).toInt().coerceAtMost(histogram.size - 1)]++
            totalIntervals++
        }
        lastFrame = nanos
        frames++
        totalFrames++
    }

    fun onMapFrame() = synchronized(lock) { mapFrames++ }

    fun onTileLoaded() = synchronized(lock) {
        tiles++
        totalTiles++
        if (armed) tilesSinceArmed++
    }

    /** Called once the map is at the ride's start: only screens drawn after that count as the first render. */
    fun armFirstRender() = synchronized(lock) {
        armed = true
        tilesSinceArmed = 0
    }

    /** The renderer found every tile it needs for the screen already drawn. */
    fun onScreenComplete(nanos: Long) = synchronized(lock) {
        if (armed && tilesSinceArmed > 0 && firstCompleteNanos == 0L) firstCompleteNanos = nanos
    }

    /** When the first complete screen at the start was drawn (0 = not yet), and how many tiles it took. */
    fun firstRender(): Pair<Long, Int> = synchronized(lock) { firstCompleteNanos to tilesSinceArmed }

    /** Forget the last frame time, so a pause (startup, the end of the run) doesn't count as a slow frame. */
    fun restartFrameClock() = synchronized(lock) { lastFrame = 0L }

    data class Window(
        val frames: Int,
        val mapFrames: Int,
        val tiles: Int,
        val p50: Double,
        val p95: Double,
        val p99: Double,
        val max: Double,
        val slowShare: Double,
    )

    /** Frames since the last call; intervals over [slowMs] count as slow (two refreshes missed). */
    fun takeWindow(slowMs: Double): Window = synchronized(lock) {
        val sorted = window.copyOf(windowCount).also { it.sort() }
        fun pct(p: Double) = if (sorted.isEmpty()) 0.0 else sorted[((sorted.size - 1) * p).toInt()] / 1e6
        val slow = sorted.count { it / 1e6 > slowMs }
        Window(
            frames, mapFrames, tiles,
            pct(0.50), pct(0.95), pct(0.99), if (sorted.isEmpty()) 0.0 else sorted.last() / 1e6,
            if (sorted.isEmpty()) 0.0 else slow.toDouble() / sorted.size,
        ).also {
            frames = 0
            mapFrames = 0
            tiles = 0
            windowCount = 0
        }
    }

    /** Whole-run frame intervals: p50, p95, p99 (ms) and the share over [slowMs]. */
    fun totals(slowMs: Double): String = synchronized(lock) {
        fun pct(p: Double): Double {
            if (totalIntervals == 0L) return 0.0
            val target = max(1L, (totalIntervals * p).toLong())
            var seen = 0L
            for (i in histogram.indices) {
                seen += histogram[i]
                if (seen >= target) return i * 0.5
            }
            return 1000.0
        }
        val slowFrom = (slowMs / 0.5).toInt() + 1
        val slow = histogram.drop(slowFrom).sum()
        String.format(
            Locale.ROOT, "frames=%d intervals p50=%.1f p95=%.1f p99=%.1f ms slow=%.2f%% tiles=%d",
            totalFrames, pct(0.50), pct(0.95), pct(0.99), if (totalIntervals == 0L) 0.0 else 100.0 * slow / totalIntervals, totalTiles,
        )
    }
}
