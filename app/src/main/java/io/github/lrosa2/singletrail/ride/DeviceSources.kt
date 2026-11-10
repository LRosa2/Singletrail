package io.github.lrosa2.singletrail.ride

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import io.github.lrosa2.singletrail.nav.engine.Fix
import io.github.lrosa2.singletrail.nav.engine.HeadingSample
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * GPS fixes once a second from the GPS provider only (spec §6, D3: no Play Services), with the
 * number of satellites used from [GnssStatus].
 */
class GpsSource(private val context: Context) {
    private val manager = context.getSystemService(LocationManager::class.java)
    private val satellitesUsed = AtomicInteger(-1)
    private val inView = AtomicInteger(-1)

    /** The latest location, for the compass's magnetic declination. */
    val latest = AtomicReference<Location?>(null)

    /** Satellites the receiver can see, shown while waiting for the first fix; null before any status. */
    val satellitesInView: Int? get() = inView.get().takeIf { it >= 0 }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // Checked by hasPermission() before a ride starts.
    fun readings(): Flow<GpsReading> = callbackFlow {
        val main = Handler(Looper.getMainLooper())
        val gnss = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                inView.set(status.satelliteCount)
                satellitesUsed.set((0 until status.satelliteCount).count { status.usedInFix(it) })
            }
        }
        val listener = LocationListener { location ->
            latest.set(location)
            trySend(GpsReading(location.toFix(satellitesUsed.get().takeIf { it >= 0 }), location.time))
        }
        manager.registerGnssStatusCallback(gnss, main)
        manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, INTERVAL_MS, 0f, listener, Looper.getMainLooper())
        awaitClose {
            manager.removeUpdates(listener)
            manager.unregisterGnssStatusCallback(gnss)
        }
    }

    private fun Location.toFix(satellites: Int?) = Fix(
        elapsedNanos = elapsedRealtimeNanos,
        lat = latitude,
        lon = longitude,
        accuracyM = if (hasAccuracy()) accuracy else null,
        speedMps = if (hasSpeed()) speed else null,
        bearingDeg = if (hasBearing()) bearing else null,
        bearingAccDeg = if (hasBearingAccuracy()) bearingAccuracyDegrees else null,
        altitudeM = if (hasAltitude()) altitude else null,
        satellites = satellites,
    )

    private companion object {
        const val INTERVAL_MS = 1_000L
    }
}

/**
 * Compass heading from the rotation-vector sensor (spec §5.9, D30), remapped for a phone standing
 * upright on the handlebar and corrected from magnetic to true north, at most [MAX_RATE_HZ] a second.
 */
class CompassSource(context: Context, private val location: () -> Location?) {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val rotation: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    fun headings(): Flow<HeadingSample> {
        val sensor = rotation ?: return emptyFlow()
        return callbackFlow {
            val matrix = FloatArray(9)
            val upright = FloatArray(9)
            val angles = FloatArray(3)
            var lastNanos = 0L
            var accuracy = SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    if (event.timestamp - lastNanos < 1_000_000_000L / MAX_RATE_HZ) return
                    lastNanos = event.timestamp
                    SensorManager.getRotationMatrixFromVector(matrix, event.values)
                    // Upright on the bars: the back of the phone points the way the rider faces.
                    SensorManager.remapCoordinateSystem(matrix, SensorManager.AXIS_X, SensorManager.AXIS_Z, upright)
                    SensorManager.getOrientation(upright, angles)
                    val magnetic = Math.toDegrees(angles[0].toDouble())
                    val azimuth = ((magnetic + declination()) % 360 + 360) % 360
                    val reliable = accuracy >= SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
                    // Sensor timestamps are elapsed-realtime nanos on current devices; use the clock to be sure.
                    trySend(HeadingSample(SystemClock.elapsedRealtimeNanos(), azimuth.toFloat(), reliable))
                }

                override fun onAccuracyChanged(sensor: Sensor, value: Int) {
                    accuracy = value
                }
            }
            sensors.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
            awaitClose { sensors.unregisterListener(listener) }
        }
    }

    private fun declination(): Double {
        val l = location() ?: return 0.0
        return GeomagneticField(l.latitude.toFloat(), l.longitude.toFloat(), l.altitude.toFloat(), System.currentTimeMillis())
            .declination.toDouble()
    }

    private companion object {
        const val MAX_RATE_HZ = 4
    }
}

/** True on Android 13+ when the user has turned notifications off for the app. */
fun notificationsBlocked(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
