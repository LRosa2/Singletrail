package io.github.lrosa2.singletrail.ui.ride

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.lrosa2.singletrail.R
import io.github.lrosa2.singletrail.maps.MapScene
import io.github.lrosa2.singletrail.nav.engine.NavState
import io.github.lrosa2.singletrail.ride.RideUi
import kotlinx.coroutines.delay
import java.io.File

/** After the rider drags the map, it follows again this long after the last drag (spec §4.5, D35). */
private const val FOLLOW_AGAIN_MS = 10_000L

/**
 * Map view (spec §4.1): heading-up by default, the route ahead in route colour and the ridden part
 * grey, turns and waypoints, the rider, and the map attribution (spec §7.4). Unlocked, the rider can
 * drag and zoom the map, switch to north-up and back, and bring it back to the rider.
 */
@Composable
fun RideMap(
    ui: RideUi,
    nav: NavState,
    mapFiles: List<File>,
    northUp: Boolean,
    locked: Boolean,
    onNorthUp: (Boolean) -> Unit,
    log: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    var following by remember { mutableStateOf(true) }
    var pannedAt by remember { mutableLongStateOf(0L) }
    val map = remember(mapFiles) {
        RideMapView(context, mapFiles, density, log) {
            following = false
            pannedAt = SystemClock.uptimeMillis()
        }
    }
    DisposableEffect(map) { onDispose { map.destroy() } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, map) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> map.onResume()
                Lifecycle.Event.ON_PAUSE -> map.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(pannedAt) {
        if (!following) {
            delay(FOLLOW_AGAIN_MS)
            following = true
        }
    }
    // The lock coming back ends a look around.
    LaunchedEffect(locked) { if (locked) following = true }

    val scene = MapScene.of(nav, ui.maneuvers, northUp)
    SideEffect { map.show(ui.course, ui.waypoints, nav.progressM, nav.skipped, scene, following) }

    val description = stringResource(R.string.ride_map)
    Box(modifier.clipToBounds().semantics { contentDescription = description }) {
        AndroidView({ map.view }, Modifier.fillMaxSize())
        if (following && scene.camera != null) {
            RiderArrow(scene, Modifier.fillMaxSize())
        }
        Text(
            stringResource(R.string.maps_attribution),
            Modifier.align(Alignment.BottomStart).padding(4.dp),
            fontSize = 11.sp,
            color = Color(0xFF333333),
            style = MaterialTheme.typography.labelSmall.copy(background = Color(0xB3FFFFFF)),
        )
        if (!locked) {
            Column(Modifier.align(Alignment.TopEnd).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MapButton(stringResource(if (northUp) R.string.ride_map_heading_up else R.string.ride_map_north_up)) { onNorthUp(!northUp) }
                if (!following) {
                    MapButton(stringResource(R.string.ride_map_follow)) { following = true }
                }
            }
        }
    }
}

/** The rider at the point the map follows: 3/4 down for heading-up, the centre for north-up. */
@Composable
private fun RiderArrow(scene: MapScene, modifier: Modifier) {
    val color = if (scene.riderInactive) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary
    val headingUp = scene.camera?.headingUp == true
    Canvas(modifier) {
        val center = androidx.compose.ui.geometry.Offset(size.width / 2, size.height * if (headingUp) 0.75f else 0.5f)
        val r = 14.dp.toPx()
        val degrees = scene.riderArrowDeg
        if (degrees == null) {
            drawCircle(Color.White, r * 0.8f, center)
            drawCircle(color, r * 0.6f, center)
            return@Canvas
        }
        val path = Path().apply {
            moveTo(center.x, center.y - r * 1.3f)
            lineTo(center.x + r, center.y + r)
            lineTo(center.x, center.y + r * 0.45f)
            lineTo(center.x - r, center.y + r)
            close()
        }
        rotate(degrees.toFloat(), center) {
            drawPath(path, color)
            drawPath(path, Color.White, style = Stroke(3.dp.toPx()))
        }
    }
}

@Composable
private fun MapButton(label: String, onClick: () -> Unit) {
    // Touch targets of at least 64 dp on the ride screen (spec §4.5).
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 2.dp,
        modifier = Modifier.heightIn(min = 64.dp).widthIn(min = 64.dp),
    ) {
        Box(Modifier.heightIn(min = 64.dp).padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
            Text(label, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        }
    }
}
