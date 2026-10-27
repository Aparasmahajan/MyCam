package io.frame.app.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.frame.app.AppContainer
import io.frame.app.ui.components.ChoiceChips
import io.frame.app.ui.components.FrameTopBar
import io.frame.app.ui.components.formatBytes
import io.frame.app.ui.components.formatDateTime
import io.frame.app.ui.components.formatElapsed
import io.frame.app.ui.components.label
import io.frame.app.ui.theme.RecRed
import io.frame.app.ui.theme.Warn
import io.frame.core.data.db.SessionSummary
import io.frame.core.model.Mode
import io.frame.core.model.StopReason

@Composable
fun LibraryScreen(container: AppContainer, onBack: () -> Unit, onOpenSession: (String) -> Unit) {
    var filter by remember { mutableStateOf<Mode?>(null) }
    val sessions by remember(filter) { container.library.sessions(filter) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val visible = sessions.filter { it.segmentCount > 0 || it.session.endedAt == null }

    Scaffold(topBar = { FrameTopBar("Library", onBack) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                ChoiceChips(
                    listOf<Pair<Mode?, String>>(null to "All") + Mode.entries.map { it to it.label() },
                    filter,
                ) { filter = it }
            }
            if (visible.isEmpty()) {
                item {
                    Text(
                        "No recordings yet. Start Dashcam, Sports or Camera from the home screen.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 24.dp),
                    )
                }
            }
            items(visible, key = { it.session.id }) { SessionCard(it) { onOpenSession(it.session.id) } }
        }
    }
}

@Composable
private fun SessionCard(summary: SessionSummary, onClick: () -> Unit) {
    val s = summary.session
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row {
                Text(s.title ?: s.mode.label(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                when {
                    s.endedAt == null -> Text("Recording", color = RecRed)
                    s.endReason == StopReason.CRASH -> Text("Ended unexpectedly", color = Warn)
                    s.endReason != null && s.endReason != StopReason.USER -> Text("Stopped: ${s.endReason!!.name.lowercase()}", color = Warn)
                }
            }
            Text(
                "${s.mode.label()} · ${formatDateTime(s.startedAt)}" +
                    (s.endedAt?.let { " · ${formatElapsed(it - s.startedAt)}" } ?: ""),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "${summary.segmentCount} segments · ${formatBytes(summary.totalBytes)}" +
                    if (summary.eventCount > 0) " · ${summary.eventCount} events" else "",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
