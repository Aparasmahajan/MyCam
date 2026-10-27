package io.frame.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.frame.core.model.EventType
import io.frame.core.model.Mode
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FrameTopBar(title: String, onBack: (() -> Unit)?, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            }
        },
        actions = { actions() },
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 20.dp, bottom = 8.dp),
    )
}

/** A row of single-choice chips. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChoiceChips(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((value, label) in options) {
            FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(label) })
        }
    }
}

/** Wall-clock time that recomposes twice a second, for running timers. */
@Composable
fun rememberNow(): State<Long> = produceState(System.currentTimeMillis()) {
    while (true) {
        value = System.currentTimeMillis()
        delay(500)
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(Locale.US, bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.0f MB".format(Locale.US, bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "%.0f KB".format(Locale.US, bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}

fun formatElapsed(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

fun formatDateTime(epochMs: Long): String = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(epochMs))

fun formatTime(epochMs: Long): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(epochMs))

fun Mode.label(): String = when (this) {
    Mode.DASHCAM -> "Dashcam"
    Mode.SPORTS -> "Sports"
    Mode.CAMERA -> "Camera"
}

fun EventType.label(): String = when (this) {
    EventType.MANUAL -> "Saved"
    EventType.IMPACT -> "Impact"
    EventType.HIGHLIGHT_GOAL -> "Goal"
    EventType.HIGHLIGHT_WICKET -> "Wicket"
    EventType.HIGHLIGHT_POINT -> "Score"
    EventType.HIGHLIGHT_CUSTOM -> "Highlight"
}
