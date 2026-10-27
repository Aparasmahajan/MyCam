package io.frame.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.frame.app.export.ClipExporter
import io.frame.app.export.ExportStatus
import io.frame.app.export.Gallery
import io.frame.app.ui.theme.Warn

/** Progress, result and Share action for the current export. Shows nothing when idle. */
@Composable
fun ExportStatusBar(exporter: ClipExporter) {
    val context = LocalContext.current
    val status by exporter.status.collectAsStateWithLifecycle()
    val st = status
    if (st is ExportStatus.Idle) return

    Card(
        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 4.dp)) {
            when (st) {
                is ExportStatus.Running -> {
                    Text("Exporting ${st.label}…" + (st.percent?.let { " $it%" } ?: ""))
                    if (st.percent != null) {
                        LinearProgressIndicator(
                            progress = { st.percent / 100f },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, end = 8.dp),
                        )
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp, end = 8.dp))
                    }
                    Actions { TextButton(onClick = exporter::cancel) { Text("Cancel") } }
                }
                is ExportStatus.Done -> {
                    Text("${st.label}: ${st.uris.size} file(s) saved to ${st.folder}")
                    Actions {
                        TextButton(onClick = exporter::dismiss) { Text("Dismiss") }
                        TextButton(onClick = { Gallery.share(context, st.uris, st.mime) }) { Text("Share") }
                    }
                }
                is ExportStatus.Failed -> {
                    Text("${st.label} failed: ${st.message}", color = Warn)
                    Actions { TextButton(onClick = exporter::dismiss) { Text("Dismiss") } }
                }
                ExportStatus.Idle -> Unit
            }
        }
    }
}

@Composable
private fun Actions(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.weight(1f))
        content()
    }
}
