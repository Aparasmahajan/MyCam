package io.frame.app.ui.library

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import io.frame.app.AppContainer
import io.frame.app.ui.components.FrameTopBar
import java.io.File

/** Plays a whole session as one playlist of segments, starting at the chosen one. */
@Composable
fun PlayerScreen(container: AppContainer, sessionId: String, startSegmentId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val player = remember { ExoPlayer.Builder(context).build() }
    var speed by remember { mutableFloatStateOf(1f) }

    LaunchedEffect(sessionId, startSegmentId) {
        val segments = container.library.playableSegments(sessionId)
        val start = segments.indexOfFirst { it.id == startSegmentId }.coerceAtLeast(0)
        player.setMediaItems(segments.map { MediaItem.fromUri(Uri.fromFile(File(it.filePath))) }, start, 0L)
        player.prepare()
        player.playWhenReady = true
    }
    DisposableEffect(Unit) { onDispose { player.release() } }

    Scaffold(topBar = { FrameTopBar("Playback", onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).background(Color.Black)) {
            AndroidView(
                factory = { PlayerView(it).apply { this.player = player } },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (s in listOf(0.25f, 0.5f, 1f, 2f)) {
                    FilterChip(
                        selected = speed == s,
                        onClick = {
                            speed = s
                            player.setPlaybackSpeed(s)
                        },
                        label = { Text("${s}x".replace(".0x", "x")) },
                    )
                }
            }
        }
    }
}
