package io.github.lrosa2.singletrail.spike

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import io.github.lrosa2.singletrail.gpx.GpxReader
import io.github.lrosa2.singletrail.nav.Geo
import io.github.lrosa2.singletrail.nav.PreparedRoute
import io.github.lrosa2.singletrail.nav.RoutePoint
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The renderer spike (spec §12.3): shows the installed map with the 35 km route, waits until the
 * first screen of tiles is drawn, then rides the route at 6 m/s with a fix every second, heading
 * up, for the set time. Frames, tiles, battery and memory go to `files/logs/` and logcat ("Spike").
 *
 * Extras (`adb shell am start -n … --es mode step --ei minutes 30`):
 * - `mode`: `smooth` (moves and turns every frame between fixes, default) or `step` (once a second)
 * - `minutes` (default 30) or `seconds`: how long the ride runs after the first render
 * - `zoom` (default 16), `theme` (file in `files/themes/OAM/`, default `singletrail_mtb.xml`;
 *   `none` for the library's own theme), `brightness` (0–1, default 0.5)
 * - `waitUnplug` (boolean): start the ride only once the USB cable is pulled, for battery runs
 */
class SpikeActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val stats = SpikeStats()
    private lateinit var renderer: SpikeRenderer
    private lateinit var route: PreparedRoute
    private lateinit var status: TextView
    private lateinit var logFile: File

    private var smooth = true
    private var runMillis = 30 * 60_000L
    private var refreshHz = 60f
    private var createdNanos = 0L
    private var rideStartUptime = 0L
    private var running = false
    private var fixCount = 0
    private var previous: Fix? = null
    private var current: Fix? = null
    private var fixNanos = 0L

    private var startBattery: BatteryReading? = null
    private var currentSumMa = 0.0
    private var currentSamples = 0
    private var windowCurrentSumMa = 0.0
    private var windowCurrentSamples = 0
    private var maxPssKb = 0
    private var firstRenderMs = -1L

    private data class Fix(val s: Double, val lat: Double, val lon: Double, val heading: Double)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createdNanos = System.nanoTime()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = intent.getFloatExtra("brightness", 0.5f) }
        @Suppress("DEPRECATION")
        refreshHz = (if (Build.VERSION.SDK_INT >= 30) display?.refreshRate else windowManager.defaultDisplay.refreshRate) ?: 60f

        val files = getExternalFilesDir(null)!!
        val mapFile = File(files, "maps").listFiles { f -> f.name.endsWith(".map") }?.firstOrNull()
        if (mapFile == null) {
            setContentView(TextView(this).apply { text = "No map. Push a .map file into\n${File(files, "maps")}" })
            return
        }
        val themeName = intent.getStringExtra("theme") ?: "singletrail_mtb.xml"
        val theme = if (themeName == "none") null else File(files, "themes/OAM/$themeName").takeIf { it.isFile }
        smooth = intent.getStringExtra("mode") != "step"
        runMillis = when {
            intent.hasExtra("seconds") -> intent.getIntExtra("seconds", 60) * 1000L
            else -> intent.getIntExtra("minutes", 30) * 60_000L
        }
        val zoom = intent.getIntExtra("zoom", 16)

        route = assets.open("route.gpx").use { input ->
            val doc = GpxReader({ android.util.Xml.newPullParser() }).read(input)
            PreparedRoute.prepare(doc.tracks.first().points.map { RoutePoint(it.lat, it.lon, it.elevation) })
        }
        renderer = createRenderer()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        logFile = File(File(files, "logs").apply { mkdirs() }, "spike-${renderer.name.lowercase()}-${if (smooth) "smooth" else "step"}-$stamp.log")
        log(
            "START renderer=${renderer.name} mode=${if (smooth) "smooth" else "step"} zoom=$zoom theme=${theme?.name ?: "library default"} " +
                "map=${mapFile.name} route=${"%.1f".format(Locale.ROOT, route.lengthMeters / 1000)}km/${route.points.size}pts " +
                "run=${runMillis / 1000}s device=${Build.MANUFACTURER} ${Build.MODEL} android=${Build.VERSION.RELEASE} refresh=${"%.0f".format(Locale.ROOT, refreshHz)}Hz",
        )

        val mapView = renderer.createView(this, mapFile, theme, route.points, zoom, stats)
        status = label(Gravity.TOP, 14f)
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE)
            addView(mapView, FrameLayout.LayoutParams(-1, -1))
            addView(RiderArrow(context), FrameLayout.LayoutParams(-1, -1))
            addView(status, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
            addView(label(Gravity.END, 11f).apply { text = "© OpenStreetMap contributors · OpenAndroMaps" }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END))
        })
        val start = fixAt(0.0)
        current = start
        mapView.post {
            renderer.show(start.lat, start.lon, start.heading)
            stats.armFirstRender()
        }
        status.text = "${renderer.name}: loading map…"
        main.postDelayed(::checkFirstRender, 200)
    }

    override fun onResume() {
        super.onResume()
        if (::renderer.isInitialized) renderer.onResume()
    }

    override fun onPause() {
        if (running) {
            log("PAUSED: the app left the screen, so this run is not a valid measurement")
            stopRide(summary = true)
        }
        if (::renderer.isInitialized) renderer.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        if (::renderer.isInitialized) renderer.onDestroy()
        super.onDestroy()
    }

    /**
     * First render = the first frame whose tiles were all drawn, at the ride's start. Then wait a
     * little more for labels before the ride begins.
     */
    private fun checkFirstRender() {
        val (complete, tiles) = stats.firstRender()
        val waited = (System.nanoTime() - createdNanos) / 1_000_000
        if (complete != 0L) {
            firstRenderMs = (complete - createdNanos) / 1_000_000
            log("FIRST RENDER ${firstRenderMs} ms after onCreate ($tiles tiles)")
            main.postDelayed(::waitForUnplug, 2_000)
        } else if (waited > 60_000) {
            log("FIRST RENDER not settled after 60 s ($tiles tiles); starting anyway")
            waitForUnplug()
        } else {
            main.postDelayed(::checkFirstRender, 100)
        }
    }

    /** With `--ez waitUnplug true` the ride (and the battery measurement) starts once the cable is out. */
    private fun waitForUnplug() {
        if (intent.getBooleanExtra("waitUnplug", false) && battery().plugged) {
            status.text = "${renderer.name}: unplug the phone to start the ${runMillis / 60_000}-minute run"
            main.postDelayed(::waitForUnplug, 1_000)
        } else {
            startRide()
        }
    }

    private fun startRide() {
        running = true
        startBattery = battery()
        log("BATTERY at start: ${startBattery}")
        stats.takeWindow(slowMs()) // drop the loading phase
        stats.restartFrameClock()
        rideStartUptime = SystemClock.uptimeMillis()
        fixCount = 0
        nextFix()
        if (smooth) Choreographer.getInstance().postFrameCallback(frame)
        main.postDelayed(::reportWindow, WINDOW_MS)
        main.postDelayed(::reportMemory, 1_000)
    }

    private fun stopRide(summary: Boolean) {
        if (!running) return
        running = false
        Choreographer.getInstance().removeFrameCallback(frame)
        main.removeCallbacksAndMessages(null)
        if (summary) writeSummary()
    }

    /** A fix every second (spec §6: GPS at 1 Hz), at 6 m/s round the loop. */
    private fun nextFix() {
        if (!running) return
        val elapsed = SystemClock.uptimeMillis() - rideStartUptime
        if (elapsed >= runMillis) {
            log("END after ${elapsed / 1000} s")
            stopRide(summary = true)
            status.text = "${renderer.name}: done. Logs in Android/data/$packageName/files/logs"
            return
        }
        val s = (SPEED_MPS * fixCount) % route.lengthMeters
        previous = current
        current = fixAt(s).also { fix ->
            fixNanos = System.nanoTime()
            renderer.setProgress(indexAhead(s))
            if (!smooth) renderer.show(fix.lat, fix.lon, fix.heading)
        }
        val reading = battery()
        reading.currentMa?.let {
            currentSumMa += it
            currentSamples++
            windowCurrentSumMa += it
            windowCurrentSamples++
        }
        status.text = statusText(elapsed, reading.percent)
        fixCount++
        main.postAtTime(::nextFix, rideStartUptime + fixCount * 1000L)
    }

    /** Smooth mode: between two fixes, move and turn the map a little every frame. */
    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val from = previous ?: current!!
            val to = current!!
            val f = ((frameTimeNanos - fixNanos) / 1e9).coerceIn(0.0, 1.0)
            val turn = ((to.heading - from.heading + 540) % 360) - 180
            renderer.show(
                from.lat + (to.lat - from.lat) * f,
                from.lon + Geo.lonDelta(from.lon, to.lon) * f,
                from.heading + turn * f,
            )
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun reportWindow() {
        if (!running) return
        val w = stats.takeWindow(slowMs())
        val b = battery()
        val avgMa = if (windowCurrentSamples == 0) null else windowCurrentSumMa / windowCurrentSamples
        windowCurrentSumMa = 0.0
        windowCurrentSamples = 0
        val seconds = WINDOW_MS / 1000.0
        log(
            String.format(
                Locale.ROOT,
                "t=%4ds fps=%5.1f p50=%5.1f p95=%5.1f p99=%5.1f max=%6.1f slow=%4.1f%% map=%4.1f/s tiles=%3d batt=%d%% charge=%s cur=%s temp=%.1fC",
                (SystemClock.uptimeMillis() - rideStartUptime) / 1000, w.frames / seconds, w.p50, w.p95, w.p99, w.max, w.slowShare * 100,
                w.mapFrames / seconds, w.tiles, b.percent, b.chargeMah?.let { "%.0fmAh".format(Locale.ROOT, it) } ?: "?",
                avgMa?.let { "%.0fmA".format(Locale.ROOT, it) } ?: "?", b.temperatureC,
            ),
        )
        main.postDelayed(::reportWindow, WINDOW_MS)
    }

    private fun reportMemory() {
        if (!running) return
        val info = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        maxPssKb = max(maxPssKb, info.totalPss)
        fun stat(name: String) = (info.getMemoryStat(name)?.toIntOrNull() ?: 0) / 1024
        log(
            "MEM pss=${info.totalPss / 1024}MB java=${stat("summary.java-heap")}MB native=${stat("summary.native-heap")}MB " +
                "graphics=${stat("summary.graphics")}MB code=${stat("summary.code")}MB",
        )
        main.postDelayed(::reportMemory, 60_000)
    }

    private fun writeSummary() {
        val minutes = (SystemClock.uptimeMillis() - rideStartUptime) / 60_000.0
        val start = startBattery
        val end = battery()
        val dropPercent = start?.let { it.percent - end.percent }
        val dropMah = if (start?.chargeMah != null && end.chargeMah != null) start.chargeMah - end.chargeMah else null
        val avgMa = if (currentSamples == 0) null else currentSumMa / currentSamples
        log(
            String.format(
                Locale.ROOT,
                "SUMMARY renderer=%s mode=%s minutes=%.1f firstRender=%dms %s pssMax=%dMB battery %s→%d%% (%s %%/h) charge %s mAh (%s mAh/h) avgCurrent=%s plugged=%s",
                renderer.name, if (smooth) "smooth" else "step", minutes, firstRenderMs, stats.totals(slowMs()), maxPssKb / 1024,
                start?.percent ?: "?", end.percent,
                dropPercent?.let { "%.1f".format(Locale.ROOT, it * 60 / minutes) } ?: "?",
                dropMah?.let { "%.0f".format(Locale.ROOT, it) } ?: "?",
                dropMah?.let { "%.0f".format(Locale.ROOT, it * 60 / minutes) } ?: "?",
                avgMa?.let { "%.0fmA".format(Locale.ROOT, it) } ?: "?",
                start?.plugged ?: "?",
            ),
        )
    }

    private fun statusText(elapsedMs: Long, batteryPercent: Int): String {
        val left = max(0L, runMillis - elapsedMs) / 1000
        return "${renderer.name} · ${if (smooth) "smooth" else "step"} · ${left / 60}:${"%02d".format(left % 60)} left · $batteryPercent%"
    }

    private fun fixAt(s: Double): Fix {
        val p = route.positionAt(s)
        val a = route.positionAt(max(0.0, s - 10))
        val b = route.positionAt(min(route.lengthMeters, s + 10))
        return Fix(s, p.lat, p.lon, Geo.bearingDeg(a.lat, a.lon, b.lat, b.lon))
    }

    /** The first route point beyond [s]. */
    private fun indexAhead(s: Double): Int {
        var lo = 0
        var hi = route.points.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (route.distanceAt(mid) <= s) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun slowMs() = 2 * 1000.0 / refreshHz + 1

    private data class BatteryReading(
        val percent: Int,
        val plugged: Boolean,
        val temperatureC: Double,
        val chargeMah: Double?,
        val currentMa: Double?,
    )

    private fun battery(): BatteryReading {
        val manager = getSystemService(BatteryManager::class.java)
        val sticky = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val charge = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val current = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        return BatteryReading(
            percent = if (level < 0) -1 else level * 100 / scale,
            plugged = (sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0,
            temperatureC = (sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0,
            chargeMah = charge.takeIf { it > 0 && it != Int.MIN_VALUE }?.let { it / 1000.0 },
            // Sign conventions differ between phones; the drain is what matters.
            currentMa = current.takeIf { it != 0 && it != Int.MIN_VALUE }?.let { abs(it) / 1000.0 },
        )
    }

    private fun log(line: String) {
        Log.i("Spike", line)
        val time = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())
        try {
            logFile.appendText("$time $line\n")
        } catch (_: Exception) {
        }
    }

    private fun label(gravity: Int, sp: Float) = TextView(this).apply {
        this.gravity = gravity
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(Color.BLACK)
        setBackgroundColor(0xCCFFFFFF.toInt())
        val pad = dp(6f).toInt()
        setPadding(pad, pad / 2, pad, pad / 2)
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density

    /** The rider, always pointing up (the map turns underneath). */
    private class RiderArrow(context: Context) : View(context) {
        private val density = context.resources.displayMetrics.density
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1565C0.toInt() }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 3 * density
            strokeJoin = Paint.Join.ROUND
        }
        private val path = Path()

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height * SpikeRenderer.RIDER_Y
            val r = 16 * density
            path.reset()
            path.moveTo(cx, cy - r)
            path.lineTo(cx + r * 0.75f, cy + r)
            path.lineTo(cx, cy + r * 0.45f)
            path.lineTo(cx - r * 0.75f, cy + r)
            path.close()
            canvas.drawPath(path, edge)
            canvas.drawPath(path, fill)
        }
    }

    private companion object {
        const val SPEED_MPS = 6.0
        const val WINDOW_MS = 10_000L
    }
}
