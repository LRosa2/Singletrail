package io.github.lrosa2.singletrail.spike

import android.app.Activity
import android.view.View
import io.github.lrosa2.singletrail.nav.RoutePoint
import org.oscim.android.MapView
import org.oscim.core.GeoPoint
import org.oscim.core.MapPosition
import org.oscim.core.Tile
import org.oscim.layers.Layer
import org.oscim.layers.PathLayer
import org.oscim.layers.tile.MapTile
import org.oscim.layers.tile.TileManager
import org.oscim.layers.tile.TileSet
import org.oscim.layers.tile.vector.labeling.LabelLayer
import org.oscim.map.Map
import org.oscim.renderer.GLViewport
import org.oscim.renderer.LayerRenderer
import org.oscim.theme.ExternalRenderTheme
import org.oscim.theme.internal.VtmThemes
import org.oscim.tiling.source.mapfile.MapFileTileSource
import java.io.File

fun createRenderer(): SpikeRenderer = VtmRenderer()

/** VTM: vector tiles drawn with OpenGL ES on the GL thread; labels stay upright when the map turns. */
private class VtmRenderer : SpikeRenderer {
    override val name = "VTM"
    private lateinit var mapView: MapView
    private lateinit var map: Map
    private lateinit var ridden: PathLayer
    private lateinit var ahead: PathLayer
    private lateinit var route: List<GeoPoint>
    private val position = MapPosition()
    private var scale = 1.0

    override fun createView(activity: Activity, mapFile: File, theme: File?, route: List<RoutePoint>, zoom: Int, stats: SpikeStats): View {
        mapView = MapView(activity)
        map = mapView.map()
        val source = MapFileTileSource().apply { setMapFile(mapFile.absolutePath) }
        val base = map.setBaseMap(source)
        base.manager.events.bind(TileManager.Listener { event, _ ->
            if (event == TileManager.TILE_LOADED) stats.onTileLoaded()
        })
        map.setTheme(if (theme != null) ExternalRenderTheme(theme.absolutePath) else VtmThemes.DEFAULT)

        val density = activity.resources.displayMetrics.density
        val width = SpikeRenderer.ROUTE_WIDTH_DP * density
        this.route = route.map { GeoPoint(it.lat, it.lon) }
        ridden = PathLayer(map, SpikeRenderer.ROUTE_RIDDEN, width)
        ahead = PathLayer(map, SpikeRenderer.ROUTE_AHEAD, width)
        ahead.setPoints(this.route)

        map.layers().add(ridden)
        map.layers().add(ahead)
        map.layers().add(LabelLayer(map, base))
        map.layers().add(FrameCounter(map, stats, base.manager))

        // Pivot in [-1, 1] from the view centre: 0.5 puts the rider 3/4 down.
        map.viewport().setMapViewCenter(0f, (SpikeRenderer.RIDER_Y - 0.5f) * 2)
        // The same ground per pixel as Mapsforge at this zoom: 256 px tiles scaled by the density.
        scale = 256.0 * density * (1 shl zoom) / Tile.SIZE
        return mapView
    }

    override fun show(lat: Double, lon: Double, headingDeg: Double) {
        position.setPosition(lat, lon)
        position.setScale(scale)
        position.setBearing(-headingDeg.toFloat())
        position.setTilt(0f)
        map.setMapPosition(position)
    }

    override fun setProgress(index: Int) {
        val i = index.coerceIn(1, route.size - 1)
        ridden.setPoints(route.subList(0, i + 1))
        ahead.setPoints(route.subList(i, route.size))
    }

    override fun onResume() = mapView.onResume()
    override fun onPause() = mapView.onPause()
    override fun onDestroy() = mapView.onDestroy()
}

/**
 * A layer that draws nothing: its update runs once per GL frame. Until the first render is found
 * it also checks whether every visible tile is on the GPU.
 */
private class FrameCounter(map: Map, stats: SpikeStats, manager: TileManager) : Layer(map) {
    init {
        mRenderer = object : LayerRenderer() {
            private val visible = TileSet()
            private var found = false

            override fun update(viewport: GLViewport) {
                val now = System.nanoTime()
                stats.onFrame(now)
                stats.onMapFrame()
                if (found) return
                manager.getActiveTiles(visible)
                // Every tile has its data; tiles just outside the view keep NEW_DATA and are never
                // uploaded, so only the ones on screen reach READY.
                val tiles = (0 until visible.cnt).map { visible.tiles[it] }
                if (tiles.isNotEmpty() && tiles.all { it.state(LOADED) } && tiles.any { it.state(MapTile.State.READY.toInt()) }) {
                    stats.onScreenComplete(now)
                    if (stats.firstRender().first != 0L) {
                        found = true
                        visible.releaseTiles()
                    }
                }
            }

            override fun render(viewport: GLViewport) {}
        }
    }

    private companion object {
        val LOADED = MapTile.State.READY.toInt() or MapTile.State.NEW_DATA.toInt()
    }
}
