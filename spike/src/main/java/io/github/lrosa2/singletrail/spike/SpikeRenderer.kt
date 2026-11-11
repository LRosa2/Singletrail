package io.github.lrosa2.singletrail.spike

import android.app.Activity
import android.view.View
import io.github.lrosa2.singletrail.nav.RoutePoint
import java.io.File

/** One map library behind the same test (spec §12.3). Each flavour supplies [createRenderer]. */
interface SpikeRenderer {
    val name: String

    /**
     * The map view: [mapFile] drawn with [theme] (null: the library's own default), the route on
     * top, at [zoom] (Mapsforge zoom levels; VTM is scaled to show the same ground per pixel).
     * Reports frames and loaded tiles to [stats].
     */
    fun createView(activity: Activity, mapFile: File, theme: File?, route: List<RoutePoint>, zoom: Int, stats: SpikeStats): View

    /** Puts the rider at [lat]/[lon], 3/4 down the screen, with [headingDeg] pointing up. */
    fun show(lat: Double, lon: Double, headingDeg: Double)

    /** Route points before [index] are ridden (grey), the rest lie ahead (route colour). */
    fun setProgress(index: Int)

    fun onResume() {}
    fun onPause() {}
    fun onDestroy() {}

    companion object {
        /** Where the rider sits: this far down the view (0 = top, 1 = bottom). */
        const val RIDER_Y = 0.75f
        const val ROUTE_AHEAD = 0xB3D500F9.toInt() // magenta, 70%: stands apart from the S0–S5 colours
        const val ROUTE_RIDDEN = 0x99808080.toInt()
        const val ROUTE_WIDTH_DP = 6f
    }
}
