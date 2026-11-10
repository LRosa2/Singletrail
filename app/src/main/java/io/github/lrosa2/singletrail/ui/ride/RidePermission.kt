package io.github.lrosa2.singletrail.ui.ride

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import io.github.lrosa2.singletrail.R

data class RideRequest(val routeId: String, val routeName: String, val reversed: Boolean, val simulate: Boolean)

fun hasPreciseLocation(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

/**
 * Starts a ride once precise location is granted: first the reason (spec §9.3), then the system
 * prompt for location and, on Android 13+, notifications. Without precise location it explains
 * where to allow it.
 */
@Composable
fun rememberRideStarter(onStart: (RideRequest) -> Unit): (RideRequest) -> Unit {
    val context = LocalContext.current
    var explaining by remember { mutableStateOf<RideRequest?>(null) }
    var pending by remember { mutableStateOf<RideRequest?>(null) }
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val request = pending
        pending = null
        if (hasPreciseLocation(context)) request?.let(onStart) else denied = true
    }

    explaining?.let { request ->
        AlertDialog(
            onDismissRequest = { explaining = null },
            title = { Text(stringResource(R.string.location_rationale_title)) },
            text = { Text(stringResource(R.string.location_rationale_body)) },
            confirmButton = {
                TextButton(onClick = {
                    explaining = null
                    pending = request
                    launcher.launch(ridePermissions())
                }) { Text(stringResource(R.string.action_continue)) }
            },
            dismissButton = { TextButton(onClick = { explaining = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (denied) {
        AlertDialog(
            onDismissRequest = { denied = false },
            title = { Text(stringResource(R.string.location_denied_title)) },
            text = { Text(stringResource(R.string.location_denied_body)) },
            confirmButton = {
                TextButton(onClick = {
                    denied = false
                    openAppSettings(context)
                }) { Text(stringResource(R.string.action_open_settings)) }
            },
            dismissButton = { TextButton(onClick = { denied = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    return { request ->
        when {
            !hasPreciseLocation(context) -> explaining = request
            needsNotificationPermission(context) -> {
                // Location is there; ask for notifications on the way, and ride whatever the answer.
                pending = request
                launcher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            }
            else -> onStart(request)
        }
    }
}

private fun needsNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

private fun ridePermissions(): Array<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()
