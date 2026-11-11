package io.github.lrosa2.singletrail.spike

import android.app.Activity
import android.view.View
import io.github.lrosa2.singletrail.nav.RoutePoint
import org.mapsforge.core.graphics.Canvas
import org.mapsforge.core.graphics.Cap
import org.mapsforge.core.graphics.Join
import org.mapsforge.core.graphics.Paint
import org.mapsforge.core.graphics.Style
import org.mapsforge.core.graphics.TileBitmap
import org.mapsforge.core.model.BoundingBox
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.MapPosition
import org.mapsforge.core.model.Point
import org.mapsforge.core.model.Rotation
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.layer.Layer
import org.mapsforge.map.layer.cache.InMemoryTileCache
import org.mapsforge.map.layer.cache.TileCache
import org.mapsforge.map.layer.labels.ThreadedLabelLayer
import org.mapsforge.map.layer.overlay.Polyline
import org.mapsforge.map.layer.queue.Job
import org.mapsforge.map.layer.renderer.TileRendererLayer
import org.mapsforge.map.reader.MapFile
import org.mapsforge.map.rendertheme.ExternalRenderTheme
import org.mapsforge.map.rendertheme.internal.MapsforgeThemes
import java.io.File
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.sqrt

fun createRenderer(): SpikeRenderer = MapsforgeRenderer()

/**
 * Mapsforge: tiles are drawn into bitmaps on worker threads (Android Canvas), labels on their own
 * layer so they stay upright when the map turns; the view blits the frame buffer rotated.
 */
private class MapsforgeRenderer : SpikeRenderer {
    override val name = "Mapsforge"
    private lateinit var mapView: MapView
    private lateinit var ridden: Polyline
    private lateinit var ahead: Polyline
    private lateinit var route: List<LatLong>
    private var zoom: Byte = 16

    override fun createView(activity: Activity, mapFile: File, theme: File?, route: List<RoutePoint>, zoom: Int, stats: SpikeStats): View {
        AndroidGraphicFactory.createInstance(activity.application)
        this.zoom = zoom.toByte()
        mapView = object : MapView(activity) {
            override fun onDraw(androidCanvas: android.graphics.Canvas) {
                super.onDraw(androidCanvas)
                stats.onFrame(System.nanoTime())
            }
        }
        mapView.isClickable = false
        mapView.mapScaleBar.isVisible = false
        mapView.setBuiltInZoomControls(false)
        mapView.setMapViewCenterY(SpikeRenderer.RIDER_Y)

        val model = mapView.model
        // The frame buffer is a square of the view's diagonal × overdraw. With the rider 3/4 down
        // and the map turned, the default 1.2 leaves a corner blank; 1.5 covers a phone screen.
        model.frameBufferModel.overdrawFactor = OVERDRAW
        // The tile layer (and the label store, sized from this cache) wants every tile under that
        // square at once; turned by 45° the square's bounding box is √2 wider. With less, tiles and
        // labels are evicted before they are drawn and re-rendered forever. Memory only, like VTM:
        // Mapsforge's usual two-level cache copies tiles between memory and files under a lock that
        // stalled drawing for ~0.45 s, and a tile left only in the file cache never came back.
        val metrics = activity.resources.displayMetrics
        val tileSize = model.displayModel.tileSize
        val turned = hypot(metrics.widthPixels.toDouble(), metrics.heightPixels.toDouble()) * OVERDRAW * sqrt(2.0)
        val perSide = ceil(turned / tileSize).toInt() + 2
        val cache = InMemoryTileCache(perSide * perSide)
        val counting = CountingTileCache(cache, stats)
        val tiles = TileRendererLayer(
            counting, MapFile(mapFile), model.mapViewPosition,
            false, false, true, AndroidGraphicFactory.INSTANCE, // opaque; labels on their own layer, cached
        )
        tiles.setXmlRenderTheme(theme?.let { ExternalRenderTheme(it) } ?: MapsforgeThemes.DEFAULT)

        val width = SpikeRenderer.ROUTE_WIDTH_DP * activity.resources.displayMetrics.density
        this.route = route.map { LatLong(it.lat, it.lon) }
        ridden = Polyline(paint(SpikeRenderer.ROUTE_RIDDEN, width), AndroidGraphicFactory.INSTANCE)
        ahead = Polyline(paint(SpikeRenderer.ROUTE_AHEAD, width), AndroidGraphicFactory.INSTANCE)
        ahead.setPoints(this.route)

        val layers = mapView.layerManager.layers
        layers.add(tiles)
        layers.add(ridden)
        layers.add(ahead)
        layers.add(ThreadedLabelLayer(AndroidGraphicFactory.INSTANCE, tiles.labelStore))
        layers.add(FrameCounter(stats, counting))
        return mapView
    }

    override fun show(lat: Double, lon: Double, headingDeg: Double) {
        val rotation = Rotation(-headingDeg.toFloat(), mapView.width * 0.5f, mapView.height * SpikeRenderer.RIDER_Y)
        mapView.model.mapViewPosition.setMapPosition(MapPosition(LatLong(lat, lon), zoom.toDouble(), rotation), false)
    }

    override fun setProgress(index: Int) {
        val i = index.coerceIn(1, route.size - 1)
        ridden.setPoints(route.subList(0, i + 1))
        ahead.setPoints(route.subList(i, route.size))
        ahead.requestRedraw()
    }

    override fun onDestroy() {
        mapView.destroyAll()
        AndroidGraphicFactory.clearResourceMemoryCache()
    }

    private fun paint(color: Int, width: Float): Paint = AndroidGraphicFactory.INSTANCE.createPaint().apply {
        setColor(color)
        setStrokeWidth(width)
        setStyle(Style.STROKE)
        setStrokeCap(Cap.ROUND)
        setStrokeJoin(Join.ROUND)
    }

    private companion object {
        const val OVERDRAW = 1.5
    }
}

/** Counts finished tiles, and tiles the tile layer wanted but didn't have in its last pass. */
private class CountingTileCache(private val cache: TileCache, private val stats: SpikeStats) : TileCache by cache {
    @Volatile
    var hits = 0

    @Volatile
    var misses = 0

    override fun put(key: Job, bitmap: TileBitmap) {
        cache.put(key, bitmap)
        stats.onTileLoaded()
    }

    override fun getImmediately(key: Job): TileBitmap? = cache.getImmediately(key).also { if (it == null) misses++ else hits++ }
}

/**
 * The top layer, drawn after the tiles in each pass on Mapsforge's layer thread: counts redraws,
 * and reports a complete screen when the tile layer found every tile it needed.
 */
private class FrameCounter(private val stats: SpikeStats, private val cache: CountingTileCache) : Layer() {
    override fun draw(boundingBox: BoundingBox, zoomLevel: Byte, canvas: Canvas, topLeftPoint: Point, rotation: Rotation) {
        stats.onMapFrame()
        if (cache.hits > 0 && cache.misses == 0) stats.onScreenComplete(System.nanoTime())
        cache.hits = 0
        cache.misses = 0
    }
}
