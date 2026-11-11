package io.github.lrosa2.singletrail.ui.ride

import android.content.Context
import android.graphics.Paint
import androidx.core.graphics.createBitmap
import androidx.core.view.doOnLayout
import io.github.lrosa2.singletrail.maps.MapScene
import io.github.lrosa2.singletrail.maps.RouteCuts
import io.github.lrosa2.singletrail.maps.Stretch
import io.github.lrosa2.singletrail.nav.PreparedRoute
import io.github.lrosa2.singletrail.nav.engine.LatLon
import io.github.lrosa2.singletrail.nav.engine.RouteRange
import org.oscim.android.MapView
import org.oscim.android.canvas.AndroidBitmap
import org.oscim.android.theme.AssetsRenderTheme
import org.oscim.backend.canvas.Paint.Cap
import org.oscim.core.BoundingBox
import org.oscim.core.GeoPoint
import org.oscim.core.MapPosition
import org.oscim.core.Tile
import org.oscim.layers.PathLayer
import org.oscim.layers.TileGridLayer
import org.oscim.layers.marker.ItemizedLayer
import org.oscim.layers.marker.MarkerInterface
import org.oscim.layers.marker.MarkerItem
import org.oscim.layers.marker.MarkerSymbol
import org.oscim.layers.tile.vector.labeling.LabelLayer
import org.oscim.map.Map
import org.oscim.renderer.MapRenderer
import org.oscim.theme.styles.LineStyle
import org.oscim.tiling.source.mapfile.MapFileTileSource
import org.oscim.tiling.source.mapfile.MultiMapFileTileSource
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The VTM map of the ride screen (spec §4.1, §7.4): the installed maps combined, drawn with the
 * Singletrail MTB theme, with the route, turns, waypoints and the rider on top. It redraws when
 * something changes (a fix, a gesture), never on its own (spec §4.4).
 */
internal class RideMapView(
    context: Context,
    mapFiles: List<File>,
    private val density: Float,
    log: (String) -> Unit,
    /** The rider dragged the map: it stops following for a while (spec §4.5). */
    private val onPan: () -> Unit,
) {
    val view: MapView
    private val map: Map
    private val ridden: PathLayer
    private val ahead: PathLayer
    private val skipped: List<PathLayer>
    private val targetLine: PathLayer
    private val marks: ItemizedLayer
    private val rider: ItemizedLayer

    private val turnSymbol = dot(14f, fill = 0xFFFFFFFF.toInt(), ring = 0xFF263238.toInt(), ringDp = 3f)
    private val waypointSymbol = dot(14f, fill = 0xFF00897B.toInt(), ring = 0xFFFFFFFF.toInt(), ringDp = 2f)
    private val riderSymbol = dot(20f, fill = 0xFF1565C0.toInt(), ring = 0xFFFFFFFF.toInt(), ringDp = 3f)
    private val riderInactiveSymbol = dot(20f, fill = 0xFF8A8F8B.toInt(), ring = 0xFFFFFFFF.toInt(), ringDp = 3f)

    private var course: PreparedRoute? = null
    private var cuts: RouteCuts? = null
    private var points: List<GeoPoint> = emptyList()
    private var drawnProgress = Double.NaN
    private var drawnSkipped: List<RouteRange>? = null
    private var drawnTurns: List<LatLon>? = null
    private var drawnWaypoints: List<LatLon>? = null
    private var drawnTarget: Pair<LatLon?, LatLon?>? = null
    private var drawnRider: Triple<LatLon?, Boolean, Boolean>? = null
    private var overviewShown = false
    private var zoomChosen = false
    private var pivotY = Float.NaN
    private val position = MapPosition()

    // Reused for every update: PathLayer copies them (spec §10: no full-route churn per fix).
    private val riddenPoints = ArrayList<GeoPoint>()
    private val aheadPoints = ArrayList<GeoPoint>()
    private val scratch = ArrayList<GeoPoint>()

    init {
        view = MapView(context)
        map = view.map()
        // Neutral paper where no map is installed (spec §7.4); after the view, which resets it to grey.
        MapRenderer.setBackgroundColor(BACKGROUND)
        ridden = PathLayer(map, LineStyle(RIDDEN, 8 * density, Cap.ROUND))
        ahead = PathLayer(map, LineStyle(AHEAD, 10 * density, Cap.ROUND))
        skipped = List(MAX_SKIPPED) { PathLayer(map, LineStyle(SKIPPED, 3 * density, Cap.ROUND)) }
        targetLine = PathLayer(map, LineStyle(TARGET, 4 * density, Cap.ROUND))
        marks = ItemizedLayer(map, turnSymbol)
        rider = ItemizedLayer(map, riderSymbol)

        val layers = map.layers()
        val base = if (mapFiles.isEmpty()) {
            null
        } else {
            val source = MultiMapFileTileSource().apply {
                // Neighbouring maps overlap at the border; draw what they share once.
                setDeduplicate(true)
                mapFiles.forEach { add(MapFileTileSource().apply { setMapFile(it.absolutePath) }) }
            }
            map.setBaseMap(source).also {
                try {
                    map.setTheme(AssetsRenderTheme(context.assets, "", THEME))
                } catch (e: Exception) {
                    log("Theme failed: ${e.javaClass.simpleName}")
                }
            }
        }
        if (base == null) layers.add(TileGridLayer(map, GRID, density, 1))
        layers.add(ridden)
        skipped.forEach(layers::add)
        layers.add(ahead)
        layers.add(targetLine)
        if (base != null) layers.add(LabelLayer(map, base))
        layers.add(marks)
        layers.add(rider)

        // Heading-up turns the map; the rider only pans and zooms, and zooms around the rider.
        map.getEventLayer().apply {
            enableRotation(false)
            enableTilt(false)
            setFixOnCenter(true)
        }
        map.events.bind(Map.UpdateListener { event, _ -> if (event === Map.MOVE_EVENT) onPan() })
    }

    /** Brings the map up to date; only what changed is redrawn. */
    fun show(course: PreparedRoute?, waypoints: List<LatLon>, progressM: Double, skippedRanges: List<RouteRange>, scene: MapScene, following: Boolean) {
        if (course !== this.course) {
            this.course = course
            cuts = course?.let(::RouteCuts)
            points = course?.points?.map { GeoPoint(it.lat, it.lon) }.orEmpty()
            drawnProgress = Double.NaN
            overviewShown = false
        }
        if (drawnProgress.isNaN() || abs(progressM - drawnProgress) >= REDRAW_METERS || skippedRanges != drawnSkipped) {
            drawRoute(progressM, skippedRanges)
        }
        if (scene.turns != drawnTurns || waypoints != drawnWaypoints) drawMarks(scene.turns, waypoints)
        val showRider = !following && scene.rider != null
        drawRider(scene.rider.takeIf { showRider }, scene.riderInactive, showRider)
        if (drawnTarget != (scene.rider to scene.target)) {
            drawnTarget = scene.rider to scene.target
            val from = scene.rider
            val to = scene.target
            if (from != null && to != null) targetLine.setPoints(listOf(GeoPoint(from.lat, from.lon), GeoPoint(to.lat, to.lon))) else targetLine.clearPath()
        }
        moveCamera(scene, following)
    }

    fun onResume() = view.onResume()

    fun onPause() = view.onPause()

    fun destroy() = view.onDestroy()

    private fun drawRoute(progressM: Double, skippedRanges: List<RouteRange>) {
        val cuts = cuts
        drawnProgress = progressM
        drawnSkipped = skippedRanges
        if (cuts == null) {
            ridden.clearPath()
            ahead.clearPath()
            skipped.forEach { it.clearPath() }
            return
        }
        ridden.setPoints(fill(riddenPoints, cuts.stretch(0.0, progressM)))
        ahead.setPoints(fill(aheadPoints, cuts.stretch(progressM, cuts.route.lengthMeters)))
        // More skipped stretches than layers are rare; the rest stay in the ridden grey.
        skipped.forEachIndexed { i, layer ->
            val range = skippedRanges.getOrNull(i)
            if (range == null) layer.clearPath() else layer.setPoints(fill(scratch, cuts.stretch(range.from, range.to)))
        }
    }

    private fun fill(into: ArrayList<GeoPoint>, stretch: Stretch?): ArrayList<GeoPoint> {
        into.clear()
        if (stretch != null) {
            into.add(GeoPoint(stretch.start.lat, stretch.start.lon))
            into.addAll(points.subList(stretch.from, stretch.to))
            into.add(GeoPoint(stretch.end.lat, stretch.end.lon))
        }
        return into
    }

    private fun drawMarks(turns: List<LatLon>, waypoints: List<LatLon>) {
        drawnTurns = turns
        drawnWaypoints = waypoints
        val items = ArrayList<MarkerInterface>(turns.size + waypoints.size)
        waypoints.forEach { items += MarkerItem(null, null, GeoPoint(it.lat, it.lon)).apply { marker = waypointSymbol } }
        turns.forEach { items += MarkerItem(null, null, GeoPoint(it.lat, it.lon)).apply { marker = turnSymbol } }
        marks.removeAllItems(false)
        marks.addItems(items)
        map.updateMap(true)
    }

    /** While the map follows, the rider is drawn over it at a fixed point; while looking around, as a dot on it. */
    private fun drawRider(at: LatLon?, inactive: Boolean, visible: Boolean) {
        val state = Triple(at, inactive, visible)
        if (state == drawnRider) return
        drawnRider = state
        rider.removeAllItems(false)
        if (at != null) {
            rider.addItem(MarkerItem(null, null, GeoPoint(at.lat, at.lon)).apply { marker = if (inactive) riderInactiveSymbol else riderSymbol })
        } else {
            rider.populate()
        }
        map.updateMap(true)
    }

    private fun moveCamera(scene: MapScene, following: Boolean) {
        val camera = scene.camera
        if (camera == null) {
            if (!overviewShown) view.doOnLayout { showOverview() }
            return
        }
        if (!following) return
        // Heading-up puts the rider 3/4 down the screen, north-up in the middle.
        val wantedPivot = if (camera.headingUp) RIDER_PIVOT else 0f
        if (wantedPivot != pivotY) {
            pivotY = wantedPivot
            map.viewport().setMapViewCenter(0f, pivotY)
        }
        map.getMapPosition(position)
        if (!zoomChosen) {
            // The first fix zooms in; after that the rider's own zoom is kept.
            position.setScale(defaultScale())
            zoomChosen = true
        }
        position.setPosition(camera.lat, camera.lon)
        position.setBearing(-camera.bearingDeg.toFloat())
        position.setTilt(0f)
        map.setMapPosition(position)
    }

    /** Before the first fix: the whole route, north-up. */
    private fun showOverview() {
        val course = course ?: return
        if (overviewShown || zoomChosen || view.width == 0) return
        overviewShown = true
        pivotY = 0f
        map.viewport().setMapViewCenter(0f, 0f)
        val box = BoundingBox(
            course.points.minOf { it.lat }, course.points.minOf { it.lon },
            course.points.maxOf { it.lat }, course.points.maxOf { it.lon },
        )
        val overview = MapPosition()
        overview.setByBoundingBox(box, (view.width * OVERVIEW_FILL).toInt(), (view.height * OVERVIEW_FILL).toInt())
        overview.setBearing(0f)
        map.setMapPosition(overview)
    }

    /** Zoom [DEFAULT_ZOOM] at 256 px tiles scaled by the screen density, as in the renderer spike. */
    private fun defaultScale() = 256.0 * density * (1 shl DEFAULT_ZOOM) / Tile.SIZE

    private fun dot(sizeDp: Float, fill: Int, ring: Int, ringDp: Float): MarkerSymbol {
        val px = (sizeDp * density).roundToInt()
        val bitmap = createBitmap(px, px)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = ring
        canvas.drawCircle(px / 2f, px / 2f, px / 2f, paint)
        paint.color = fill
        canvas.drawCircle(px / 2f, px / 2f, px / 2f - ringDp * density, paint)
        return MarkerSymbol(AndroidBitmap(bitmap), MarkerSymbol.HotspotPlace.CENTER, true)
    }

    companion object {
        const val THEME = "theme/singletrail_mtb.xml"

        /** Pivot in [-1, 1] from the view's centre: 0.5 puts the rider 3/4 down. */
        const val RIDER_PIVOT = 0.5f
        const val DEFAULT_ZOOM = 16
        private const val MAX_SKIPPED = 4

        /** The ridden/ahead split moves on in steps this long, not on every metre. */
        private const val REDRAW_METERS = 2.0
        private const val OVERVIEW_FILL = 0.85

        // Route ahead translucent, so the trail's difficulty colour still shows through (spec §7.4).
        private const val AHEAD = 0x8CD500F9.toInt()
        private const val RIDDEN = 0x80707070.toInt()
        private const val SKIPPED = 0xCC505050.toInt()
        private const val TARGET = 0xE6E65100.toInt()
        private const val BACKGROUND = 0xFFF2EFE9.toInt()
        private const val GRID = 0x40000000
    }
}
