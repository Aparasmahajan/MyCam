package io.frame.app.ui.recorder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.frame.app.AppContainer
import io.frame.app.sports.SportPreset
import io.frame.app.ui.components.ChoiceChips
import io.frame.app.ui.components.formatBytes
import io.frame.app.ui.components.formatElapsed
import io.frame.app.ui.components.label
import io.frame.app.ui.components.rememberNow
import io.frame.app.ui.theme.RecRed
import io.frame.app.ui.theme.SafeGreen
import io.frame.app.ui.theme.Warn
import io.frame.core.model.Mode
import io.frame.core.recording.RecorderState
import io.frame.core.recording.RecordingEngine

private val Scrim = Color(0xAA000000)

@Composable
fun RecorderScreen(
    container: AppContainer,
    mode: Mode,
    autostart: Boolean,
    onBack: () -> Unit,
    onOpenLibrary: () -> Unit,
) {
    val vm: RecorderViewModel = viewModel(key = "recorder-${mode.name}") { RecorderViewModel(container, mode) }
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()

    var hasCamera by remember { mutableStateOf(context.granted(Manifest.permission.CAMERA)) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasCamera = context.granted(Manifest.permission.CAMERA)
    }
    LaunchedEffect(Unit) {
        val missing = requiredPermissions(mode).filterNot { context.granted(it) }
        if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray())
    }

    // Keep the display on while this screen is visible; recording itself doesn't need it.
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    // Auto-start once per visit (Quick Settings tile / resume), not every time this screen recomposes.
    var autostartDone by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(hasCamera, autostart) {
        if (autostart && !autostartDone && hasCamera && (state is RecorderState.Idle || state is RecorderState.Stopped)) {
            autostartDone = true
            vm.start(context)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hasCamera) {
            CameraPreview(container.engine, mode)
            RecorderOverlay(vm, onBack, onOpenLibrary)
        } else {
            PermissionNeeded(onGrant = { permissions.launch(requiredPermissions(mode).toTypedArray()) }, onBack = onBack)
        }
    }

    when (val st = state) {
        is RecorderState.Stopped -> AlertDialog(
            onDismissRequest = vm::acknowledge,
            confirmButton = { TextButton(onClick = vm::acknowledge) { Text("OK") } },
            title = { Text("Recording stopped") },
            text = { Text(st.message) },
        )
        is RecorderState.Failed -> AlertDialog(
            onDismissRequest = vm::acknowledge,
            confirmButton = { TextButton(onClick = vm::acknowledge) { Text("OK") } },
            title = { Text("Could not record") },
            text = { Text(st.message) },
        )
        else -> Unit
    }
}

/** The engine owns the camera; this view only lends it a surface while the screen is visible. */
@Composable
private fun CameraPreview(engine: RecordingEngine, mode: Mode) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var previewView by remember { mutableStateOf<PreviewView?>(null) }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            PreviewView(ctx).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }.also { previewView = it }
        },
    )

    DisposableEffect(previewView, lifecycleOwner) {
        val pv = previewView ?: return@DisposableEffect onDispose { }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> engine.attachPreview(pv.surfaceProvider, mode)
                Lifecycle.Event.ON_STOP -> engine.attachPreview(null)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) engine.attachPreview(pv.surfaceProvider, mode)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            engine.attachPreview(null)
        }
    }
}

@Composable
private fun RecorderOverlay(vm: RecorderViewModel, onBack: () -> Unit, onOpenLibrary: () -> Unit) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val state by vm.state.collectAsStateWithLifecycle()
    val health by vm.health.collectAsStateWithLifecycle()
    val location by vm.location.collectAsStateWithLifecycle()
    val match by vm.match.collectAsStateWithLifecycle()
    val now by rememberNow()
    val rec = state as? RecorderState.Recording
    val busy = state is RecorderState.Starting || state is RecorderState.Stopping

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        // ---- top bar
        Row(
            Modifier.fillMaxWidth().background(Scrim).padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
            Text(vm.mode.label(), color = Color.White, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            if (rec != null) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(RecRed))
                Text(
                    " REC ${formatElapsed(now - rec.sessionStartedAt)} · seg ${rec.segmentSeq}",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            IconButton(onClick = onOpenLibrary) { Icon(Icons.Default.PhotoLibrary, "Library", tint = Color.White) }
        }

        // ---- HUD
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (vm.mode == Mode.DASHCAM && rec != null) {
                val speed = location?.speedKmh
                Pill(if (speed != null) "%.0f km/h".format(speed) else "-- km/h", big = true)
            }
            if (vm.mode == Mode.SPORTS && rec != null) {
                match?.let { m ->
                    Scoreboard(
                        teamA = m.teamA,
                        teamB = m.teamB,
                        scoreA = m.scoreA,
                        scoreB = m.scoreB,
                        period = m.period,
                        clock = formatElapsed(now - rec.sessionStartedAt),
                        onScore = { a, d ->
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            vm.changeScore(a, d)
                        },
                        onNextPeriod = vm::nextPeriod,
                    )
                }
            }
            if (rec != null) {
                val eventsLabel = if (vm.mode == Mode.SPORTS) "highlights" else "protected"
                Pill("${rec.eventCount} $eventsLabel · ${formatBytes(health.freeBytes)} free")
            }
            for (warning in health.warnings) Pill(warning, color = Warn)
        }

        Spacer(Modifier.weight(1f))

        // ---- pre-start match setup
        if (vm.mode == Mode.SPORTS && rec == null && !busy) MatchSetup(vm)

        // ---- controls
        Row(
            Modifier.fillMaxWidth().background(Scrim).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = { if (rec != null) vm.stop() else vm.start(context) },
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = if (rec != null) Color.White else RecRed),
                modifier = Modifier.weight(1f).height(64.dp),
            ) {
                Text(
                    when {
                        state is RecorderState.Starting -> "Starting…"
                        state is RecorderState.Stopping -> "Stopping…"
                        rec != null -> "STOP"
                        vm.mode == Mode.SPORTS -> "START MATCH"
                        else -> "START"
                    },
                    color = if (rec != null) Color.Black else Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (vm.mode != Mode.CAMERA) {
                Button(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        vm.mark()
                    },
                    enabled = rec != null,
                    colors = ButtonDefaults.buttonColors(containerColor = if (vm.mode == Mode.SPORTS) MaterialTheme.colorScheme.primary else SafeGreen),
                    modifier = Modifier.weight(1f).height(64.dp),
                ) {
                    Icon(if (vm.mode == Mode.SPORTS) Icons.Default.Star else Icons.Default.Shield, null, tint = Color.Black)
                    Text(
                        if (vm.mode == Mode.SPORTS) " HIGHLIGHT" else " SAVE",
                        color = Color.Black,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun MatchSetup(vm: RecorderViewModel) {
    Column(
        Modifier.fillMaxWidth().background(Scrim).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Match setup", color = Color.White, style = MaterialTheme.typography.titleMedium)
        ChoiceChips(SportPreset.entries.map { it to it.label }, vm.sport) { vm.sport = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(vm.teamA, { vm.teamA = it }, label = { Text("Team A") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(vm.teamB, { vm.teamB = it }, label = { Text("Team B") }, singleLine = true, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun Scoreboard(
    teamA: String,
    teamB: String,
    scoreA: Int,
    scoreB: Int,
    period: Int,
    clock: String,
    onScore: (teamA: Boolean, delta: Int) -> Unit,
    onNextPeriod: () -> Unit,
) {
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).background(Scrim).padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TeamScore(teamA, scoreA, onPlus = { onScore(true, 1) }, onMinus = { onScore(true, -1) })
            Text("  –  ", color = Color.White, fontSize = 22.sp)
            TeamScore(teamB, scoreB, onPlus = { onScore(false, 1) }, onMinus = { onScore(false, -1) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Period $period · $clock", color = Color.White.copy(alpha = 0.8f))
            TextButton(onClick = onNextPeriod) { Text("Next period") }
        }
    }
}

@Composable
private fun TeamScore(name: String, score: Int, onPlus: () -> Unit, onMinus: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(120.dp)) {
        Text(name, color = Color.White, maxLines = 1, textAlign = TextAlign.Center)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onMinus) { Text("−", fontSize = 20.sp) }
            Text("$score", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = onPlus) { Text("+", fontSize = 20.sp) }
        }
    }
}

@Composable
private fun Pill(text: String, color: Color = Color.White, big: Boolean = false) {
    Text(
        text,
        color = color,
        fontSize = if (big) 32.sp else 14.sp,
        fontWeight = if (big) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Scrim).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun PermissionNeeded(onGrant: () -> Unit, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Camera access is needed to record.", color = Color.White, style = MaterialTheme.typography.titleMedium)
        Text(
            "Location adds speed and position to dashcam footage. The microphone is optional. " +
                "Notifications show recording controls and alerts.",
            color = Color.White.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 12.dp),
        )
        Button(onClick = onGrant) { Text("Grant access") }
        TextButton(onClick = onBack) { Text("Back") }
    }
}

private fun requiredPermissions(mode: Mode): List<String> = buildList {
    add(Manifest.permission.CAMERA)
    add(Manifest.permission.RECORD_AUDIO)
    if (mode == Mode.DASHCAM) {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}

private fun Context.granted(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
