package io.github.lrosa2.singletrail.ui.library

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** "Near me" (spec §8.1): one location request when asked for, never continuous. */
object OneShotLocation {
    private const val NETWORK_TIMEOUT_MS = 10_000L
    private const val GPS_TIMEOUT_MS = 30_000L

    /**
     * Where the phone is: a fresh network fix (seconds, where available), else a GPS fix with precise
     * permission, else the newest position any provider remembers; null when there is none.
     */
    @SuppressLint("MissingPermission") // Callers check for a location permission first.
    suspend fun get(context: Context): Here? {
        val manager = context.getSystemService(LocationManager::class.java)
        val precise = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val attempts = buildList {
            if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) add(LocationManager.NETWORK_PROVIDER to NETWORK_TIMEOUT_MS)
            if (precise && manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) add(LocationManager.GPS_PROVIDER to GPS_TIMEOUT_MS)
        }
        for ((provider, timeout) in attempts) {
            val location = withTimeoutOrNull(timeout) { current(context, manager, provider) }
            if (location != null) return Here(location.latitude, location.longitude)
        }
        return lastKnown(manager)?.let { Here(it.latitude, it.longitude) }
    }

    @SuppressLint("MissingPermission")
    private suspend fun current(context: Context, manager: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { cont ->
            val signal = CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            LocationManagerCompat.getCurrentLocation(manager, provider, signal, ContextCompat.getMainExecutor(context)) { location ->
                if (cont.isActive) cont.resume(location)
            }
        }

    @SuppressLint("MissingPermission")
    private fun lastKnown(manager: LocationManager): Location? = manager.getProviders(true).mapNotNull { provider ->
        try {
            manager.getLastKnownLocation(provider)
        } catch (_: SecurityException) {
            null // A provider this permission level can't read.
        }
    }.maxByOrNull { it.elapsedRealtimeNanos }
}
