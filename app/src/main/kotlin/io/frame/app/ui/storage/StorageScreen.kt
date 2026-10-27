package io.frame.app.ui.storage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.frame.app.AppContainer
import io.frame.app.ui.components.ChoiceChips
import io.frame.app.ui.components.ExportStatusBar
import io.frame.app.ui.components.FrameTopBar
import io.frame.app.ui.components.SectionTitle
import io.frame.app.ui.components.formatBytes
import io.frame.app.ui.components.formatDateTime
import io.frame.app.ui.components.label
import io.frame.app.ui.theme.Warn
import io.frame.core.data.db.CleanupLogEntity
import io.frame.core.data.settings.FrameSettings
import io.frame.core.model.GB
import io.frame.core.model.Mode
import io.frame.core.model.RetentionPolicy
import io.frame.core.model.describe
import io.frame.core.retention.CleanupTrigger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val RETENTION_OPTIONS = listOf(
    12L * 60 to "12 h",
    24L * 60 to "24 h",
    3L * 24 * 60 to "3 days",
    7L * 24 * 60 to "7 days",
    30L * 24 * 60 to "30 days",
    0L to "Never",
)
private val SEGMENT_OPTIONS = listOf(60 to "1 min", 180 to "3 min", 300 to "5 min", 600 to "10 min")
private val CAP_OPTIONS = listOf(0L to "No cap", 8L * GB to "8 GB", 16L * GB to "16 GB", 32L * GB to "32 GB", 64L * GB to "64 GB")
private val RESERVE_OPTIONS = listOf(5 to "5%", 10 to "10%", 15 to "15%", 30 to "30%")

@Composable
fun StorageScreen(container: AppContainer, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val settings by container.settings.settings.collectAsStateWithLifecycle(FrameSettings())
    val usage by remember { container.library.usage() }.collectAsStateWithLifecycle(emptyList())
    val protectedBytes by remember { container.library.protectedBytes() }.collectAsStateWithLifecycle(0L)
    val history by remember { container.library.cleanupHistory() }.collectAsStateWithLifecycle(emptyList())
    val disk by produceState(0L to 0L) {
        while (true) {
            value = withContext(Dispatchers.IO) { container.storageStats.freeBytes() to container.storageStats.totalBytes() }
            delay(5_000)
        }
    }
    var editing by remember { mutableStateOf(Mode.DASHCAM) }
    var confirmCleanup by remember { mutableStateOf(false) }

    val (free, total) = disk
    val storage = settings.retention.storage
    val reserve = storage.reserveBytes(total)
    val bitrate = settings.quality.estimatedBitrate
    val hoursLeft = if (bitrate > 0) ((free - reserve).coerceAtLeast(0) * 8.0 / bitrate / 3600) else 0.0

    Scaffold(
        topBar = { FrameTopBar("Storage manager", onBack) },
        bottomBar = { ExportStatusBar(container.exporter) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp)) {
            item {
                Text("${formatBytes(total - free)} of ${formatBytes(total)} used", style = MaterialTheme.typography.titleMedium)
                LinearProgressIndicator(
                    progress = { if (total > 0) (total - free).toFloat() / total else 0f },
                    modifier = Modifier.fillMaxWidth().height(10.dp).padding(vertical = 2.dp),
                )
                val byMode = Mode.entries.joinToString(" · ") { m -> "${m.label()} ${formatBytes(usage.firstOrNull { it.mode == m }?.bytes ?: 0)}" }
                Text(byMode, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "Protected ${formatBytes(protectedBytes)} · Reserve ${storage.reservePercent}% (${formatBytes(reserve)}) · Free ${formatBytes(free)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "About %.1f h of recording left at ${settings.quality.label} before cleanup starts.".format(hoursLeft),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            item { SectionTitle("Free-space reserve (all modes)") }
            item {
                ChoiceChips(RESERVE_OPTIONS, storage.reservePercent) { pct ->
                    scope.launch { container.settings.setStorage(storage.copy(reservePercent = pct)) }
                }
            }

            item { SectionTitle("Recording policy") }
            item { ChoiceChips(Mode.entries.map { it to it.label() }, editing) { editing = it } }
            item {
                PolicyEditor(settings.retention.policy(editing)) { updated ->
                    scope.launch { container.settings.setPolicy(updated) }
                }
            }
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    Text(settings.retention.policy(editing).describe(storage), modifier = Modifier.padding(12.dp))
                }
            }

            item {
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { confirmCleanup = true }) { Text("Clean up now") }
                    OutlinedButton(onClick = { container.exporter.exportProtected(null) }, enabled = protectedBytes > 0) {
                        Text("Export protected clips")
                    }
                }
                Text(
                    "Recordings live in FRAME's own storage and are deleted if the app is uninstalled. " +
                        "Export protected clips to Movies/FRAME to keep them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            item { SectionTitle("Cleanup history (30 days)") }
            if (history.isEmpty()) item { Text("Nothing deleted yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(history.take(100), key = { it.id }) { CleanupRow(it) }
        }
    }

    if (confirmCleanup) {
        AlertDialog(
            onDismissRequest = { confirmCleanup = false },
            title = { Text("Clean up now?") },
            text = { Text("Deletes unprotected footage that is past its retention period, or needed to keep the reserve free. Protected clips are never deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmCleanup = false
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { container.retention.runCleanup(CleanupTrigger.USER) }
                        snackbar.showSnackbar(
                            if (result.deleted.isEmpty()) "Nothing to delete."
                            else "Deleted ${result.deleted.size} files, ${formatBytes(result.bytesFreed)} freed.",
                        )
                    }
                }) { Text("Clean up") }
            },
            dismissButton = { TextButton(onClick = { confirmCleanup = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PolicyEditor(policy: RetentionPolicy, onChange: (RetentionPolicy) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Delete unprotected footage older than", modifier = Modifier.padding(top = 8.dp))
        ChoiceChips(RETENTION_OPTIONS, policy.maxAgeMinutes) { onChange(policy.copy(maxAgeMinutes = it)) }
        Text("Segment length", modifier = Modifier.padding(top = 8.dp))
        ChoiceChips(SEGMENT_OPTIONS, policy.segmentLengthSeconds) { onChange(policy.copy(segmentLengthSeconds = it)) }
        Text("Maximum storage for this mode", modifier = Modifier.padding(top = 8.dp))
        ChoiceChips(CAP_OPTIONS, policy.maxBytes) { onChange(policy.copy(maxBytes = it)) }
        Text(
            "Event buffer: ${policy.eventBeforeSeconds}s before, ${policy.eventAfterSeconds}s after",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun CleanupRow(entry: CleanupLogEntity) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.weight(1f)) {
            Text(File(entry.filePath).name, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${formatDateTime(entry.time)} · ${reasonLabel(entry.reason)}",
                style = MaterialTheme.typography.bodySmall,
                color = if (entry.success) MaterialTheme.colorScheme.onSurfaceVariant else Warn,
            )
        }
        Text(if (entry.success) formatBytes(entry.sizeBytes) else "failed", color = if (entry.success) MaterialTheme.colorScheme.onSurface else Warn)
    }
}

private fun reasonLabel(reason: String) = when (reason) {
    "EXPIRED" -> "expired"
    "MODE_BUDGET" -> "over mode storage cap"
    "LOW_SPACE" -> "low free space"
    "USER" -> "deleted by you"
    else -> reason.lowercase()
}
