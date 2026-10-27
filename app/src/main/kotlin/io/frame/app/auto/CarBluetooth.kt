package io.frame.app.auto

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import io.frame.app.FrameApp
import io.frame.app.MainActivity
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * When the phone connects to the chosen car Bluetooth device, offer to start the dashcam.
 * ACL_CONNECTED is one of the broadcasts a manifest receiver may still get on Android 8+.
 * Starting the camera itself needs the user's tap (Android 14+), so this posts a notification
 * that opens the dashcam and starts it.
 */
class CarBluetoothReceiver : BroadcastReceiver() {

    @SuppressLint("MissingPermission") // CarBluetooth.hasPermission is checked first
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED || !CarBluetooth.hasPermission(context)) return
        val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
        val address = runCatching { device.address }.getOrNull() ?: return
        val container = (context.applicationContext as FrameApp).container
        val pending = goAsync()
        container.scope.launch {
            try {
                val dashcam = container.settings.current().dashcam
                if (dashcam.carBluetoothAddress == address && !container.engine.isRecording) {
                    CarBluetooth.promptToStart(context, dashcam.carBluetoothName ?: "Car")
                }
            } catch (e: Exception) {
                Timber.w(e, "Car Bluetooth check failed")
            } finally {
                pending.finish()
            }
        }
    }
}

data class PairedDevice(val name: String, val address: String)

object CarBluetooth {
    private const val CHANNEL = "car_autostart"
    private const val NOTIFICATION_ID = 10

    /** BLUETOOTH_CONNECT is a runtime permission from Android 12. */
    val permission: String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_CONNECT else null

    fun hasPermission(context: Context): Boolean =
        permission == null || ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun pairedDevices(context: Context): List<PairedDevice> {
        if (!hasPermission(context)) return emptyList()
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        return runCatching {
            adapter.bondedDevices.map { PairedDevice(it.name ?: it.address, it.address) }.sortedBy { it.name }
        }.getOrDefault(emptyList())
    }

    fun promptToStart(context: Context, carName: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Start dashcam in the car", NotificationManager.IMPORTANCE_HIGH),
        )
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_START_DASHCAM, true)
        val tap = PendingIntent.getActivity(context, NOTIFICATION_ID, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(io.frame.core.recording.R.drawable.ic_stat_record)
                .setContentTitle("Connected to $carName")
                .setContentText("Tap to start the dashcam.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                .setContentIntent(tap)
                .setAutoCancel(true)
                .setTimeoutAfter(10 * 60_000L)
                .build(),
        )
    }

    fun dismissPrompt(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }
}
