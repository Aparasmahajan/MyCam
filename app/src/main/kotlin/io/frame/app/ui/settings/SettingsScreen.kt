package io.frame.app.ui.settings

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.frame.app.AppContainer
import io.frame.app.auto.CarBluetooth
import io.frame.app.ui.components.ChoiceChips
import io.frame.app.ui.components.FrameTopBar
import io.frame.app.ui.components.SectionTitle
import io.frame.app.ui.theme.SafeGreen
import io.frame.app.ui.theme.Warn
import io.frame.core.data.settings.FrameSettings
import io.frame.core.data.settings.GSensitivity
import io.frame.core.data.settings.VideoQuality
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsStateWithLifecycle(FrameSettings())
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val batteryExempt = remember(refresh) { context.isIgnoringBatteryOptimizations() }

    fun update(transform: (FrameSettings) -> FrameSettings) = scope.launch { container.settings.update(transform) }

    var pickCar by remember { mutableStateOf(false) }
    val btPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pickCar = true
    }

    Scaffold(topBar = { FrameTopBar("Settings", onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp)) {
            item { SectionTitle("Video quality") }
            item {
                ChoiceChips(VideoQuality.entries.map { it to it.label }, settings.quality) { q -> update { it.copy(quality = q) } }
                Text(
                    "Falls back to a lower quality if the camera doesn't support it. 4K uses about 3x the storage of 1080p.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item { SectionTitle("Dashcam") }
            item {
                Text("G-sensor sensitivity (auto-protect on impact)")
                ChoiceChips(
                    listOf(GSensitivity.OFF to "Off", GSensitivity.LOW to "Low 3g", GSensitivity.MEDIUM to "Medium 2g", GSensitivity.HIGH to "High 1.2g"),
                    settings.dashcam.gSensitivity,
                ) { g -> update { it.copy(dashcam = it.dashcam.copy(gSensitivity = g)) } }
            }
            item {
                ToggleRow(
                    "Record audio in the car",
                    "Off by default. In some places, recording conversations needs everyone's consent.",
                    settings.dashcam.recordAudio,
                ) { on -> update { it.copy(dashcam = it.dashcam.copy(recordAudio = on)) } }
            }

            item {
                Text("Stop recording after the charger is unplugged", modifier = Modifier.padding(top = 8.dp))
                ChoiceChips(
                    listOf(0 to "Never", 30 to "30 s", 60 to "1 min", 300 to "5 min"),
                    settings.dashcam.stopAfterUnplugSeconds,
                ) { s -> update { it.copy(dashcam = it.dashcam.copy(stopAfterUnplugSeconds = s)) } }
                Text(
                    "Plugging back in before the time runs out keeps it recording.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Car Bluetooth")
                        Text(
                            settings.dashcam.carBluetoothName?.let { "Offer to start when connected to $it" } ?: "Not set",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (settings.dashcam.carBluetoothAddress != null) {
                        TextButton(onClick = {
                            update { it.copy(dashcam = it.dashcam.copy(carBluetoothAddress = null, carBluetoothName = null)) }
                        }) { Text("Clear") }
                    }
                    TextButton(onClick = {
                        val permission = CarBluetooth.permission
                        if (permission == null || CarBluetooth.hasPermission(context)) pickCar = true else btPermission.launch(permission)
                    }) { Text("Choose") }
                }
            }
            item {
                ToggleRow(
                    "Burn date, time and speed into the video",
                    "Can't be removed afterwards, which makes it useful as evidence.",
                    settings.overlay.dashcam,
                ) { on -> update { it.copy(overlay = it.overlay.copy(dashcam = on)) } }
            }

            item { SectionTitle("Sports") }
            item {
                ToggleRow("Burn the scoreboard and match clock into the video", null, settings.overlay.sports) { on ->
                    update { it.copy(overlay = it.overlay.copy(sports = on)) }
                }
            }
            item {
                ToggleRow("Record audio", null, settings.sports.recordAudio) { on ->
                    update { it.copy(sports = it.sports.copy(recordAudio = on)) }
                }
            }
            item {
                ToggleRow("Highlight every score change", "Each + on the scoreboard also saves a highlight.", settings.sports.autoHighlightOnScore) { on ->
                    update { it.copy(sports = it.sports.copy(autoHighlightOnScore = on)) }
                }
            }

            item { SectionTitle("Keep recording in the background") }
            item {
                Text(
                    if (batteryExempt) "Battery optimization is off for FRAME. Good."
                    else "Battery optimization is ON. Android may stop long recordings with the screen off.",
                    color = if (batteryExempt) SafeGreen else Warn,
                )
                OutlinedButton(
                    onClick = { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text("Open battery settings") }
                oemTip()?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
            }

            item { SectionTitle("What this phone supports") }
            item { Capabilities(context) }

            item { SectionTitle("Storage") }
            item {
                Text(
                    "Recordings are kept in FRAME's own folder (${container.files.root.path}). " +
                        "Uninstalling FRAME deletes them, including protected clips.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (pickCar) {
        val devices = remember { CarBluetooth.pairedDevices(context) }
        AlertDialog(
            onDismissRequest = { pickCar = false },
            confirmButton = { TextButton(onClick = { pickCar = false }) { Text("Cancel") } },
            title = { Text("Choose your car") },
            text = {
                if (devices.isEmpty()) {
                    Text("No paired Bluetooth devices. Pair the phone with your car first, in Android's Bluetooth settings.")
                } else {
                    Column {
                        for (d in devices) {
                            TextButton(onClick = {
                                update { it.copy(dashcam = it.dashcam.copy(carBluetoothAddress = d.address, carBluetoothName = d.name)) }
                                pickCar = false
                            }) { Text(d.name) }
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Capabilities(context: Context) {
    val sensors = context.getSystemService(SensorManager::class.java)
    val lines = listOf(
        "Device" to "${Build.MANUFACTURER} ${Build.MODEL}",
        "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        "G-sensor" to if (sensors?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION) != null) "Yes" else "No: impact detection unavailable",
        "Auto-start from background" to if (Build.VERSION.SDK_INT >= 34) "No (Android 14+): use the Quick Settings tile" else "Limited",
    )
    Column {
        for ((k, v) in lines) {
            Row(Modifier.padding(vertical = 2.dp)) {
                Text(k, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(v)
            }
        }
    }
}

private fun Context.isIgnoringBatteryOptimizations(): Boolean =
    getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true

/** OEM battery killers are the main cause of stopped recordings (docs/09). */
private fun oemTip(): String? = when (Build.MANUFACTURER.lowercase()) {
    "xiaomi", "redmi", "poco" -> "Xiaomi: also enable Autostart for FRAME and set Battery saver to 'No restrictions'."
    "samsung" -> "Samsung: add FRAME to 'Never sleeping apps' in Battery settings."
    "huawei", "honor" -> "Huawei/Honor: set FRAME to 'Manage manually' in App launch and allow background activity."
    "oppo", "realme", "oneplus", "vivo" -> "Allow FRAME to run in the background and disable battery optimization in App settings."
    else -> null
}
