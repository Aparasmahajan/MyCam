package io.frame.app.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.frame.app.AppContainer
import io.frame.app.ui.components.ExportStatusBar
import io.frame.app.ui.components.FrameTopBar
import io.frame.app.ui.components.SectionTitle
import io.frame.app.ui.components.formatBytes
import io.frame.app.ui.components.formatDateTime
import io.frame.app.ui.components.formatElapsed
import io.frame.app.ui.components.formatTime
import io.frame.app.ui.components.label
import io.frame.app.ui.theme.SafeGreen
import io.frame.app.ui.theme.Warn
import io.frame.core.data.db.EventEntity
import io.frame.core.data.db.SegmentEntity
import io.frame.core.data.db.SegmentWithProtection
import io.frame.core.evidence.Evidence
import io.frame.core.model.SegmentState
import kotlinx.coroutines.launch

@Composable
fun SessionScreen(
    container: AppContainer,
    sessionId: String,
    onBack: () -> Unit,
    onPlay: (segmentId: String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val exporter = container.exporter
    val session by remember(sessionId) { container.library.session(sessionId) }.collectAsStateWithLifecycle(null)
    val segments by remember(sessionId) { container.library.segments(sessionId) }.collectAsStateWithLifecycle(emptyList())
    val events by remember(sessionId) { container.library.events(sessionId) }.collectAsStateWithLifecycle(emptyList())

    var confirmDelete by remember { mutableStateOf<SegmentWithProtection?>(null) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var trimming by remember { mutableStateOf<SegmentEntity?>(null) }
    var verifying by remember { mutableStateOf(false) }
    var verification by remember { mutableStateOf<Evidence.Report?>(null) }
    val hasProtected = segments.any { it.isProtected && it.segment.state == SegmentState.COMPLETE }

    Scaffold(
        topBar = {
            FrameTopBar(session?.title ?: session?.mode?.label() ?: "Recording", onBack) {
                IconButton(onClick = {
                    verifying = true
                    scope.launch { verification = container.library.verifySession(sessionId) }
                }) { Icon(Icons.Default.VerifiedUser, "Verify integrity") }
                if (events.any { !it.open }) {
                    IconButton(onClick = { exporter.exportHighlightReel(sessionId) }) {
                        Icon(Icons.Default.Movie, "Export highlight reel")
                    }
                }
                if (hasProtected) {
                    IconButton(onClick = { exporter.exportProtected(sessionId) }) {
                        Icon(Icons.Default.Download, "Export protected clips")
                    }
                }
                IconButton(onClick = { confirmDeleteAll = true }, enabled = session?.endedAt != null) {
                    Icon(Icons.Default.DeleteSweep, "Delete recording")
                }
            }
        },
        bottomBar = { ExportStatusBar(exporter) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            session?.let { s ->
                item {
                    Text(
                        formatDateTime(s.startedAt) + (s.endedAt?.let { " · ${formatElapsed(it - s.startedAt)}" } ?: " · recording"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (events.isNotEmpty()) {
                item { SectionTitle("Events (${events.size})") }
                items(events, key = { it.id }) { event ->
                    EventRow(
                        event,
                        session?.startedAt ?: event.time,
                        onExport = { exporter.exportEvent(event) },
                        onReport = { exporter.exportIncidentReport(event) },
                    )
                }
            }
            item { SectionTitle("Segments (${segments.size})") }
            items(segments, key = { it.segment.id }) { item ->
                SegmentRow(
                    item = item,
                    onPlay = { onPlay(item.segment.id) },
                    onToggleProtect = { scope.launch { container.library.setProtected(item.segment.id, !item.isProtected) } },
                    onExport = { exporter.exportSegment(item.segment) },
                    onTrim = { trimming = item.segment },
                    onDelete = { confirmDelete = item },
                )
            }
        }
    }

    if (verifying) {
        VerifyDialog(verification) {
            verifying = false
            verification = null
        }
    }

    trimming?.let { seg ->
        TrimDialog(
            segment = seg,
            onDismiss = { trimming = null },
            onExport = { startMs, endMs ->
                exporter.exportTrim(seg, startMs, endMs)
                trimming = null
            },
        )
    }

    confirmDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(if (item.isProtected) "Delete protected clip?" else "Delete segment?") },
            text = {
                Text(
                    if (item.isProtected) "This segment is protected (saved or part of an event). It will be deleted permanently."
                    else "Segment ${item.segment.seq} will be deleted permanently.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { container.library.deleteSegment(item.segment.id) }
                    confirmDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }

    if (confirmDeleteAll) {
        val protectedCount = segments.count { it.isProtected }
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text("Delete this recording?") },
            text = {
                Text(
                    "All ${segments.size} segments will be deleted permanently" +
                        if (protectedCount > 0) ", including $protectedCount protected segment(s)." else ".",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        container.library.deleteSession(sessionId)
                        onBack()
                    }
                    confirmDeleteAll = false
                }) { Text("Delete all") }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteAll = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun EventRow(event: EventEntity, sessionStart: Long, onExport: () -> Unit, onReport: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                Text(
                    "${event.type.label()} at ${formatElapsed(event.time - sessionStart)} (${formatTime(event.time)})",
                    style = MaterialTheme.typography.titleSmall,
                )
                val details = listOfNotNull(
                    event.label,
                    event.peakG?.let { "peak %.1f g".format(it) },
                    event.speedKmh?.let { "%.0f km/h".format(it) },
                    "${(event.time - event.windowStart) / 1000}s before, ${(event.windowEnd - event.time) / 1000}s after",
                )
                Text(details.joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onReport, enabled = !event.open) { Icon(Icons.Default.Description, "Incident report (PDF)") }
            IconButton(onClick = onExport, enabled = !event.open) { Icon(Icons.Default.Download, "Export clip") }
        }
    }
}

@Composable
private fun SegmentRow(
    item: SegmentWithProtection,
    onPlay: () -> Unit,
    onToggleProtect: () -> Unit,
    onExport: () -> Unit,
    onTrim: () -> Unit,
    onDelete: () -> Unit,
) {
    val seg = item.segment
    val finished = seg.state == SegmentState.COMPLETE || seg.state == SegmentState.CORRUPT
    val playable = seg.state == SegmentState.COMPLETE
    var menu by remember { mutableStateOf(false) }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                Text("Segment ${seg.seq} · ${formatTime(seg.startedAt)}", style = MaterialTheme.typography.titleSmall)
                Text(
                    when (seg.state) {
                        SegmentState.WRITING, SegmentState.FINALIZING -> "Recording…"
                        SegmentState.CORRUPT -> "Damaged (recording was interrupted) · ${formatBytes(seg.sizeBytes ?: 0)}"
                        else -> "${formatElapsed(seg.durationMs ?: 0)} · ${formatBytes(seg.sizeBytes ?: 0)}"
                    },
                    color = if (seg.state == SegmentState.CORRUPT) Warn else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onPlay, enabled = playable) { Icon(Icons.Default.PlayArrow, "Play") }
            IconButton(onClick = onToggleProtect, enabled = finished) {
                if (item.isProtected) Icon(Icons.Filled.Shield, "Unprotect", tint = SafeGreen)
                else Icon(Icons.Outlined.Shield, "Protect")
            }
            Box {
                IconButton(onClick = { menu = true }, enabled = finished) { Icon(Icons.Default.MoreVert, "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Export to gallery") },
                        enabled = playable,
                        onClick = {
                            menu = false
                            onExport()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Trim and export…") },
                        enabled = playable,
                        onClick = {
                            menu = false
                            onTrim()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        onClick = {
                            menu = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrimDialog(segment: SegmentEntity, onDismiss: () -> Unit, onExport: (startMs: Long, endMs: Long) -> Unit) {
    val durationS = ((segment.durationMs ?: 0L) / 1000f).coerceAtLeast(1f)
    var range by remember { mutableStateOf(0f..durationS) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Trim segment ${segment.seq}") },
        text = {
            Column {
                RangeSlider(value = range, onValueChange = { range = it }, valueRange = 0f..durationS)
                Text(
                    "${formatElapsed((range.start * 1000).toLong())} to ${formatElapsed((range.endInclusive * 1000).toLong())} " +
                        "(${formatElapsed(((range.endInclusive - range.start) * 1000).toLong())})",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = range.endInclusive - range.start >= 1f,
                onClick = { onExport((range.start * 1000).toLong(), (range.endInclusive * 1000).toLong()) },
            ) { Text("Export") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun VerifyDialog(report: Evidence.Report?, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        title = { Text("Integrity check") },
        text = {
            when {
                report == null -> Text("Re-hashing every file…")
                report.intact -> Text(
                    "${report.verifiedCount} segment(s) are unchanged since they were recorded, and in their original order." +
                        report.results.count { it.status == Evidence.Status.DELETED }.let { if (it > 0) " $it older segment(s) were deleted by auto-delete; the chain still links across them." else "" },
                    color = SafeGreen,
                )
                else -> Column {
                    Text("Problems found:", color = Warn)
                    for (r in report.results.filter { it.status != Evidence.Status.OK && it.status != Evidence.Status.DELETED }) {
                        Text(
                            "Segment ${r.seq}: " + when (r.status) {
                                Evidence.Status.FILE_CHANGED -> "file was changed after recording"
                                Evidence.Status.CHAIN_BROKEN -> "out of order or replaced"
                                Evidence.Status.MISSING -> "file is missing"
                                Evidence.Status.UNHASHED -> "not hashed (recording was interrupted)"
                                else -> r.status.name.lowercase()
                            },
                        )
                    }
                }
            }
        },
    )
}
