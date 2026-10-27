package io.frame.core.recording

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.math.sqrt

/**
 * Impact detection (docs/03-dashcam-mode.md): |linear acceleration| above the threshold for
 * at least 2 consecutive samples at ~50 Hz, with a cooldown so bumpy roads don't flood events.
 * Emits the peak g of each impact.
 */
class GSensorDetector(private val context: Context) {

    fun impacts(thresholdG: Float, cooldownMs: Long = 10_000): Flow<Float> = callbackFlow {
        val manager = context.getSystemService(SensorManager::class.java)
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        if (manager == null || sensor == null) {
            close()
            return@callbackFlow
        }
        var above = 0
        var peak = 0f
        var lastImpact = 0L
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val (x, y, z) = event.values
                val g = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
                if (g < thresholdG) {
                    above = 0
                    peak = 0f
                    return
                }
                above++
                peak = maxOf(peak, g)
                val now = SystemClock.elapsedRealtime()
                if (above >= 2 && now - lastImpact > cooldownMs) {
                    lastImpact = now
                    trySend(peak)
                    above = 0
                    peak = 0f
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        manager.registerListener(listener, sensor, 20_000)
        awaitClose { manager.unregisterListener(listener) }
    }
}

/** Charger plugged in (true) / unplugged (false) transitions while the session runs. */
class PowerConnectionMonitor(private val context: Context) {

    fun changes(): Flow<Boolean> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_POWER_CONNECTED -> trySend(true)
                    Intent.ACTION_POWER_DISCONNECTED -> trySend(false)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        awaitClose { context.unregisterReceiver(receiver) }
    }
}

/** 1 Hz GPS fixes while recording. Emits nothing if location permission was not granted. */
class LocationTracker(private val context: Context) {

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // checked by hasPermission()
    fun updates(): Flow<LocationFix> = callbackFlow {
        if (!hasPermission()) {
            close()
            return@callbackFlow
        }
        val client = LocationServices.getFusedLocationProviderClient(context)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L).build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                trySend(
                    LocationFix(
                        time = loc.time,
                        lat = loc.latitude,
                        lon = loc.longitude,
                        speedKmh = if (loc.hasSpeed()) loc.speed * 3.6f else null,
                        bearing = if (loc.hasBearing()) loc.bearing else null,
                        accuracyM = if (loc.hasAccuracy()) loc.accuracy else null,
                    ),
                )
            }
        }
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        awaitClose { client.removeLocationUpdates(callback) }
    }
}
