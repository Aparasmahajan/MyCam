package io.frame.app.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.frame.app.AppContainer
import io.frame.app.ui.components.formatBytes
import io.frame.app.ui.components.label
import io.frame.app.ui.theme.RecRed
import io.frame.app.ui.theme.Warn
import io.frame.core.model.Mode
import io.frame.core.recording.RecorderState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(
    container: AppContainer,
    onOpenRecorder: (Mode, Boolean) -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenStorage: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by container.engine.state.collectAsStateWithLifecycle()
    val recovery by container.lastRecovery.collectAsStateWithLifecycle()
    val free by produceState(-1L) {
        while (true) {
            value = withContext(Dispatchers.IO) { container.storageStats.freeBytes() }
            delay(5_000)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("FRAME", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text(
            "Camera · Recorder · Studio" + if (free >= 0) "  ·  ${formatBytes(free)} free" else "",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        (state as? RecorderState.Recording)?.let { rec ->
            Banner(
                icon = Icons.Default.FiberManualRecord,
                tint = RecRed,
                text = "Recording: ${rec.mode.label()} · segment ${rec.segmentSeq}. Tap to open.",
                onClick = { onOpenRecorder(rec.mode, false) },
            )
        }

        recovery?.let { report ->
            Banner(
                icon = Icons.Default.Warning,
                tint = Warn,
                text = "The last recording ended unexpectedly. Footage up to the last finished segment was kept" +
                    (if (report.corrupt > 0) "; ${report.corrupt} unfinished segment(s) could not be repaired." else "."),
                action = "Resume dashcam" to {
                    container.dismissRecovery()
                    onOpenRecorder(Mode.DASHCAM, true)
                },
                onDismiss = { container.dismissRecovery() },
            )
        }

        Spacer(Modifier.height(4.dp))
        ModeCard(Icons.Default.DirectionsCar, "Dashcam", "Loop recording, auto-delete, G-sensor protection") {
            onOpenRecorder(Mode.DASHCAM, false)
        }
        ModeCard(Icons.Default.SportsSoccer, "Sports", "Record a whole match, mark highlights, keep score") {
            onOpenRecorder(Mode.SPORTS, false)
        }
        ModeCard(Icons.Default.Videocam, "Camera", "Plain video recording, kept until you delete it") {
            onOpenRecorder(Mode.CAMERA, false)
        }
        ModeCard(Icons.Default.PhotoLibrary, "Library", "Recordings, protected clips and highlights", onClick = onOpenLibrary)
        ModeCard(Icons.Default.Storage, "Storage manager", "Retention policies, free space, cleanup history", onClick = onOpenStorage)
        ModeCard(Icons.Default.Settings, "Settings", "G-sensor, audio, quality, battery setup", onClick = onOpenSettings)
    }
}

@Composable
private fun ModeCard(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
            Column(Modifier.padding(start = 16.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Banner(
    icon: ImageVector,
    tint: Color,
    text: String,
    onClick: (() -> Unit)? = null,
    action: Pair<String, () -> Unit>? = null,
    onDismiss: (() -> Unit)? = null,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = tint)
                Text(text, modifier = Modifier.padding(start = 12.dp))
            }
            if (action != null || onDismiss != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    onDismiss?.let { TextButton(onClick = it) { Text("Dismiss") } }
                    action?.let { (label, run) -> TextButton(onClick = run) { Text(label) } }
                }
            }
        }
    }
}
