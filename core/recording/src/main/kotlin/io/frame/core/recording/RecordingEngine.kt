package io.frame.core.recording

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import androidx.annotation.MainThread
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import io.frame.core.data.Ids
import io.frame.core.data.db.FrameDatabase
import io.frame.core.data.db.RecordingLogEntity
import io.frame.core.data.db.SegmentEntity
import io.frame.core.data.db.SessionEntity
import io.frame.core.data.db.TelemetryPointEntity
import io.frame.core.data.settings.FrameSettings
import io.frame.core.data.settings.SettingsRepository
import io.frame.core.data.settings.VideoQuality
import io.frame.core.data.storage.Mp4Inspector
import io.frame.core.data.storage.RecordingFiles
import io.frame.core.data.storage.Sidecars
import io.frame.core.evidence.Evidence
import io.frame.core.model.BudgetAction
import io.frame.core.model.EventSource
import io.frame.core.model.EventType
import io.frame.core.model.Mode
import io.frame.core.model.RetentionPolicy
import io.frame.core.model.SegmentState
import io.frame.core.model.StopReason
import io.frame.core.retention.CleanupResult
import io.frame.core.retention.CleanupTrigger
import io.frame.core.retention.Clock
import io.frame.core.retention.RetentionEngine
import io.frame.core.retention.SegmentSizeEstimator
import io.frame.core.retention.StorageStats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The Smart Loop recording engine (docs/02-smart-loop-engine.md).
 *
 * Writes video as a chain of fixed-length segments using CameraX (segment writer "Option A":
 * when a segment reaches its duration limit, the next one starts as soon as the first finalizes).
 * After every segment it runs retention cleanup. Lives as long as the process; the
 * [RecordingService] keeps the process in the foreground while a session runs.
 *
 * Threading: all session state is touched on the main thread only.
 */
class RecordingEngine(
    private val context: Context,
    private val db: FrameDatabase,
    private val files: RecordingFiles,
    private val settings: SettingsRepository,
    private val retention: RetentionEngine,
    private val stats: StorageStats,
    private val clock: Clock,
    private val recoveryGate: Deferred<*>,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<RecorderState>(RecorderState.Idle)
    val state: StateFlow<RecorderState> = _state.asStateFlow()

    private val _health = MutableStateFlow(HealthStatus())
    val health: StateFlow<HealthStatus> = _health.asStateFlow()

    private val _location = MutableStateFlow<LocationFix?>(null)
    val location: StateFlow<LocationFix?> = _location.asStateFlow()

    private val events = EventManager(db)
    private val gSensor = GSensorDetector(context)
    private val locationTracker = LocationTracker(context)
    private val mainExecutor = ContextCompat.getMainExecutor(context)

    private val lifecycleOwner = EngineLifecycleOwner()
    private val preview = Preview.Builder().build()
    private var cameraProvider: ProcessCameraProvider? = null
    private var recorder: Recorder? = null
    private var boundQuality: VideoQuality? = null
    private var boundOverlay: Boolean? = null
    private val powerMonitor = PowerConnectionMonitor(context)
    private val hashMutex = Mutex()
    /** Set by the health loop when the phone is too hot; applied at the next segment boundary. */
    private var pendingQuality: VideoQuality? = null
    private var lastStepDownAt = 0L
    @Volatile private var unplugDeadline: Long? = null
    private var overlay: VideoOverlay? = null
    private var previewAttached = false

    // Read by the overlay thread on every frame.
    @Volatile private var overlayMode: Mode = Mode.DASHCAM
    @Volatile private var overlaySessionStart: Long? = null
    @Volatile private var overlayCaption: String? = null
    private val overlayTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private var session: SessionEntity? = null
    private var policy: RetentionPolicy? = null
    private var audio = false
    private var currentSegment: SegmentEntity? = null
    private var activeRecording: Recording? = null
    private var consecutiveErrors = 0
    private var finalizeSignal: CompletableDeferred<Unit>? = null
    private var sessionJob: Job? = null

    @Volatile private var running = false
    @Volatile private var nextSegmentBytes = 0L
    @Volatile private var lastProgressAt = 0L

    val isRecording: Boolean get() = running

    // ---------------------------------------------------------------- preview

    /** Screens attach their PreviewView here and pass null when they go away; recording is unaffected. */
    @MainThread
    fun attachPreview(surfaceProvider: Preview.SurfaceProvider?, mode: Mode = Mode.DASHCAM) {
        preview.setSurfaceProvider(surfaceProvider)
        previewAttached = surfaceProvider != null
        if (previewAttached) {
            scope.launch(Dispatchers.Main.immediate) {
                runCatching { ensureCamera(settings.current(), mode) }
                    .onFailure { Timber.e(it, "Camera unavailable") }
            }
        }
        updateLifecycle()
    }

    // ---------------------------------------------------------------- session

    suspend fun start(mode: Mode, title: String? = null): Result<String> = withContext(Dispatchers.Main.immediate) {
        val current = _state.value
        if (current is RecorderState.Starting || current is RecorderState.Recording || current is RecorderState.Stopping) {
            return@withContext Result.failure(IllegalStateException("Already recording"))
        }
        _state.value = RecorderState.Starting(mode)
        try {
            recoveryGate.await()
            val cfg = settings.current()
            val pol = cfg.retention.policy(mode)
            val wantAudio = wantsAudio(cfg, mode) && hasPermission(Manifest.permission.RECORD_AUDIO)
            nextSegmentBytes = SegmentSizeEstimator.bytes(
                cfg.quality.estimatedBitrate,
                if (wantAudio) AUDIO_BITRATE else 0,
                pol.segmentLengthSeconds,
            )

            val pre = withContext(Dispatchers.IO) { retention.runCleanup(CleanupTrigger.RECORDING_START, nextSegmentBytes) }
            publishCleanup(pre)
            check(!pre.mustStop) { "Storage is full. Free some space or unprotect old clips, then try again." }

            ensureCamera(cfg, mode)

            val now = clock.nowMillis()
            overlaySessionStart = now
            val id = Ids.newId(now)
            val dir = withContext(Dispatchers.IO) { files.newSessionDir(mode, id, now) }
            val newSession = SessionEntity(id, mode, now, null, null, dir.path, title, wantAudio)
            db.sessionDao().insert(newSession)

            session = newSession
            policy = pol
            audio = wantAudio
            consecutiveErrors = 0
            running = true
            updateLifecycle()

            startSegment(1)
            _state.value = RecorderState.Recording(mode, id, now, segmentSeq = 1)
            startSessionJobs(cfg, newSession)
            log(id, "SESSION_START", "mode=$mode quality=${cfg.quality} audio=$wantAudio")
            Result.success(id)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.e(e, "Could not start recording")
            running = false
            session = null
            updateLifecycle()
            _state.value = RecorderState.Failed(e.message ?: "Could not start recording")
            Result.failure(e)
        }
    }

    suspend fun stop(reason: StopReason = StopReason.USER) = withContext(Dispatchers.Main.immediate) {
        val st = _state.value as? RecorderState.Recording ?: return@withContext
        running = false
        _state.value = RecorderState.Stopping(st.mode)

        val signal = CompletableDeferred<Unit>()
        finalizeSignal = signal
        val recording = activeRecording
        if (recording != null) recording.stop() else signal.complete(Unit)
        withTimeoutOrNull(FINALIZE_TIMEOUT_MS) { signal.await() }
        finalizeSignal = null
        activeRecording = null

        sessionJob?.cancel()
        sessionJob = null
        _location.value = null
        overlaySessionStart = null
        unplugDeadline = null
        pendingQuality = null

        db.sessionDao().end(st.sessionId, clock.nowMillis(), reason)
        events.closeAll(st.sessionId)
        log(st.sessionId, "SESSION_STOP", reason.name)
        session = null
        policy = null
        updateLifecycle()

        _state.value = if (reason == StopReason.USER) RecorderState.Idle else RecorderState.Stopped(reason, messageFor(reason))
        if (reason != StopReason.USER) Notifications.alert(context, "Recording stopped", messageFor(reason))
        runCleanupAsync(CleanupTrigger.SEGMENT_DONE)
    }

    /** Clears a Stopped/Failed state after the UI has shown it. */
    fun acknowledge() {
        val st = _state.value
        if (st is RecorderState.Stopped || st is RecorderState.Failed) _state.value = RecorderState.Idle
    }

    /** Protects (dashcam) or highlights (sports) the moment, without interrupting the recording. */
    suspend fun markEvent(
        type: EventType,
        source: EventSource,
        peakG: Float? = null,
        label: String? = null,
    ): String? = withContext(Dispatchers.Main.immediate) {
        val st = _state.value as? RecorderState.Recording ?: return@withContext null
        val pol = policy ?: return@withContext null
        val now = clock.nowMillis()
        val id = events.create(st.sessionId, type, source, now, pol, peakG, _location.value, label)
        _state.update { if (it is RecorderState.Recording) it.copy(eventCount = it.eventCount + 1, lastEventAt = now) else it }
        log(st.sessionId, "EVENT", "$type from $source" + (peakG?.let { " peak=%.2fg".format(it) } ?: ""))
        id
    }

    // ---------------------------------------------------------------- segments

    @SuppressLint("MissingPermission") // audio is only enabled after hasPermission(RECORD_AUDIO)
    private suspend fun startSegment(seq: Int) {
        val s = session ?: return
        val pol = policy ?: return
        val rec = recorder ?: error("Camera is not ready")
        val now = clock.nowMillis()
        val file = files.segmentFile(File(s.folderPath), seq, now)
        val segment = SegmentEntity(
            id = Ids.newId(now),
            sessionId = s.id,
            mode = s.mode,
            seq = seq,
            filePath = file.path,
            startedAt = now,
            endedAt = null,
            durationMs = null,
            sizeBytes = null,
            state = SegmentState.WRITING,
        )
        db.segmentDao().insert(segment)
        events.onSegmentStarted(segment)
        if (!running) {
            // stop() ran while we were writing the row; never start a recording after a stop
            db.segmentDao().setState(segment.id, SegmentState.DELETED)
            return
        }

        val options = FileOutputOptions.Builder(file).setDurationLimitMillis(pol.segmentLengthMillis).build()
        var pending = rec.prepareRecording(context, options)
        if (audio && hasPermission(Manifest.permission.RECORD_AUDIO)) pending = pending.withAudioEnabled()
        currentSegment = segment
        lastProgressAt = SystemClock.elapsedRealtime()
        activeRecording = pending.start(mainExecutor) { event -> onRecordEvent(segment, event) }
        _state.update { if (it is RecorderState.Recording) it.copy(segmentSeq = seq) else it }
    }

    private fun onRecordEvent(segment: SegmentEntity, event: VideoRecordEvent) {
        when (event) {
            is VideoRecordEvent.Status -> lastProgressAt = SystemClock.elapsedRealtime()
            is VideoRecordEvent.Finalize -> scope.launch(Dispatchers.Main.immediate) { onFinalized(segment, event) }
            else -> Unit
        }
    }

    private suspend fun onFinalized(segment: SegmentEntity, event: VideoRecordEvent.Finalize) {
        if (currentSegment?.id == segment.id) {
            currentSegment = null
            activeRecording = null
        }
        val endedAt = clock.nowMillis()
        val error = event.error
        val rolledOver = error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED ||
            error == VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED

        // 1. Keep recording first: the gap between segments is what we minimize.
        val stepDown = pendingQuality
        if (running && stepDown != null) {
            pendingQuality = null
            switchQuality(stepDown, segment.seq + 1)
        } else if (running) {
            if (rolledOver) {
                consecutiveErrors = 0
                startNextSegment(segment.seq + 1)
            } else {
                consecutiveErrors++
                log(segment.sessionId, "SEGMENT_ERROR", "code=$error ${event.cause?.message.orEmpty()}")
                when {
                    error == VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE ->
                        scope.launch { stop(StopReason.STORAGE_FULL) }
                    consecutiveErrors <= MAX_RETRIES -> scope.launch(Dispatchers.Main.immediate) {
                        delay(1_000L * consecutiveErrors)
                        if (running && activeRecording == null) startNextSegment(segment.seq + 1)
                    }
                    else -> scope.launch { stop(StopReason.CAMERA_ERROR) }
                }
            }
        }

        // 2. Bookkeeping for the finished file.
        withContext(Dispatchers.IO) { finishSegment(segment, endedAt, event) }
        if (!running) finalizeSignal?.complete(Unit)

        // 3. Make room for the next segment.
        if (running) runCleanupAsync(CleanupTrigger.SEGMENT_DONE)
    }

    /** Thermal step-down: the finished segment closed at the old quality, the next one opens at [quality]. */
    private suspend fun switchQuality(quality: VideoQuality, nextSeq: Int) {
        val s = session ?: return
        try {
            ensureCamera(settings.current().copy(quality = quality), s.mode, force = true)
            nextSegmentBytes = SegmentSizeEstimator.bytes(
                quality.estimatedBitrate,
                if (audio) AUDIO_BITRATE else 0,
                policy?.segmentLengthSeconds ?: 180,
            )
            log(s.id, "QUALITY_DOWN", "now ${quality.label} to cool the phone")
            Notifications.alert(context, "Phone is hot", "Recording continues at ${quality.label} to let it cool down.")
            startNextSegment(nextSeq)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.e(e, "Could not switch to %s", quality)
            scope.launch { stop(StopReason.CAMERA_ERROR) }
        }
    }

    private suspend fun startNextSegment(seq: Int) {
        try {
            startSegment(seq)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.e(e, "Could not start segment %d", seq)
            scope.launch { stop(StopReason.CAMERA_ERROR) }
        }
    }

    private suspend fun finishSegment(segment: SegmentEntity, endedAt: Long, event: VideoRecordEvent.Finalize) {
        val file = File(segment.filePath)
        val cleanClose = event.error in CLEAN_CLOSE_CODES
        val playable = file.exists() && file.length() > 0 && (cleanClose || Mp4Inspector.hasMoov(file))
        val state = when {
            playable -> SegmentState.COMPLETE
            file.exists() -> SegmentState.CORRUPT
            else -> SegmentState.DELETED
        }
        val durationMs = event.recordingStats.recordedDurationNanos / 1_000_000
        db.segmentDao().finish(segment.id, state, endedAt, durationMs, if (file.exists()) file.length() else 0)
        events.onSegmentFinished(segment.sessionId, endedAt)

        // Tamper evidence: hash the file and link it to the previous segment's chain hash.
        // The mutex keeps segments chained in order even if two finish close together.
        if (state == SegmentState.COMPLETE) {
            hashMutex.withLock {
                runCatching {
                    val fileHash = Evidence.sha256(file)
                    val previous = db.segmentDao().previousChainHash(segment.sessionId, segment.seq) ?: Evidence.GENESIS
                    val link = Evidence.Link(segment.sessionId, segment.seq, segment.startedAt, endedAt)
                    db.segmentDao().setHashes(segment.id, fileHash, previous, Evidence.chain(previous, fileHash, link))
                }.onFailure { Timber.w(it, "Could not hash %s", file.name) }
            }
        }

        if (state != SegmentState.DELETED) {
            val finished = db.segmentDao().get(segment.id) ?: return
            val owner = db.sessionDao().get(segment.sessionId) ?: return
            val eventIds = events.eventsFor(segment.id)
            runCatching { Sidecars.write(owner, finished, eventIds, finished.protectedManually || eventIds.isNotEmpty()) }
                .onFailure { Timber.w(it, "Sidecar write failed") }
        }
        if (state != SegmentState.COMPLETE) log(segment.sessionId, "SEGMENT_$state", file.name)
    }

    // ---------------------------------------------------------------- per-session jobs

    private fun startSessionJobs(cfg: FrameSettings, s: SessionEntity) {
        sessionJob = scope.launch(Dispatchers.Main.immediate) {
            val threshold = cfg.dashcam.gSensitivity.thresholdG
            if (s.mode == Mode.DASHCAM && threshold != null) {
                launch {
                    gSensor.impacts(threshold).collect { peak ->
                        markEvent(EventType.IMPACT, EventSource.G_SENSOR, peakG = peak)
                    }
                }
            }
            if (s.mode == Mode.DASHCAM) {
                launch {
                    locationTracker.updates().collect { fix ->
                        _location.value = fix
                        db.telemetryDao().insert(
                            TelemetryPointEntity(
                                sessionId = s.id,
                                time = fix.time,
                                lat = fix.lat,
                                lon = fix.lon,
                                speedKmh = fix.speedKmh,
                                bearing = fix.bearing,
                                accuracyM = fix.accuracyM,
                            ),
                        )
                    }
                }
            }
            val unplugDelayMs = cfg.dashcam.stopAfterUnplugSeconds * 1_000L
            if (s.mode == Mode.DASHCAM && unplugDelayMs > 0) {
                launch {
                    var timer: Job? = null
                    powerMonitor.changes().collect { connected ->
                        timer?.cancel()
                        timer = null
                        unplugDeadline = null
                        if (!connected) {
                            unplugDeadline = SystemClock.elapsedRealtime() + unplugDelayMs
                            log(s.id, "CHARGER_OFF", "stopping in ${unplugDelayMs / 1000}s unless reconnected")
                            // stop() cancels this session job, so it must run outside it
                            timer = launch {
                                delay(unplugDelayMs)
                                scope.launch { stop(StopReason.CHARGER_OFF) }
                            }
                        }
                    }
                }
            }
            launch { healthLoop(s.id) }
        }
    }

    /** Watchdog every 5 s (docs/02-smart-loop-engine.md, "Recording health monitor"). */
    private suspend fun healthLoop(sessionId: String) {
        val power = context.getSystemService(PowerManager::class.java)
        while (currentCoroutineContext().isActive && running) {
            val cfg = settings.current()
            val total = stats.totalBytes()
            val free = stats.freeBytes()
            val reserve = cfg.retention.storage.reserveBytes(total)
            val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = battery?.let {
                val l = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (l >= 0 && scale > 0) l * 100 / scale else null
            }
            val charging = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
            val thermal = power?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE
            val stalled = SystemClock.elapsedRealtime() - lastProgressAt > STALL_MS && activeRecording != null
            val lowSpace = free < reserve + nextSegmentBytes

            val now = SystemClock.elapsedRealtime()
            val lowerQuality = boundQuality?.lower()
            val stepDown = thermal >= PowerManager.THERMAL_STATUS_SEVERE && lowerQuality != null &&
                pendingQuality == null && activeRecording != null && now - lastStepDownAt > STEP_DOWN_COOLDOWN_MS

            val warnings = buildList {
                if (thermal >= PowerManager.THERMAL_STATUS_SEVERE) add("Phone is hot. Recording stops if it gets hotter.")
                else if (thermal >= PowerManager.THERMAL_STATUS_MODERATE) add("Phone is warm.")
                boundQuality?.takeIf { it != cfg.quality }?.let { add("Recording at ${it.label} to cool the phone.") }
                unplugDeadline?.let { add("Charger disconnected. Stopping in ${((it - now) / 1000).coerceAtLeast(0)}s.") }
                if (level != null && level <= 15 && !charging) add("Battery $level% and not charging.")
                if (lowSpace) add("Storage below the reserve. Cleaning up old footage.")
                if (_health.value.lastCleanup?.budgetMet == false) add("Protected clips are filling the storage budget.")
                if (stalled) add("Recording stalled. Restarting the segment.")
            }
            _health.update {
                HealthStatus(free, total, reserve, level, charging, thermal, warnings, it.lastCleanup)
            }

            when {
                thermal >= PowerManager.THERMAL_STATUS_CRITICAL -> {
                    scope.launch { stop(StopReason.THERMAL) }
                    return
                }
                level != null && level <= 5 && !charging -> {
                    scope.launch { stop(StopReason.BATTERY_LOW) }
                    return
                }
            }
            if (lowSpace) withContext(Dispatchers.IO) { runCleanupNow(CleanupTrigger.LOW_SPACE) }
            if (stepDown) {
                // Finalize now; onFinalized sees pendingQuality and reopens the camera one step lower.
                pendingQuality = lowerQuality
                lastStepDownAt = now
                log(sessionId, "THERMAL_SEVERE", "stepping down from ${boundQuality?.label}")
                activeRecording?.stop()
            } else if (stalled) {
                log(sessionId, "STALL", "no progress for ${STALL_MS / 1000}s")
                lastProgressAt = SystemClock.elapsedRealtime()
                activeRecording?.stop() // finalize -> the error path starts a fresh segment
            }
            delay(HEALTH_INTERVAL_MS)
        }
    }

    // ---------------------------------------------------------------- cleanup

    private fun runCleanupAsync(trigger: CleanupTrigger) {
        scope.launch(Dispatchers.IO) { runCleanupNow(trigger) }
    }

    private suspend fun runCleanupNow(trigger: CleanupTrigger) {
        val result = runCatching { retention.runCleanup(trigger, if (running) nextSegmentBytes else 0) }
            .onFailure { Timber.e(it, "Cleanup failed") }
            .getOrNull() ?: return
        publishCleanup(result)
        if (!running) return
        val action = settings.current().retention.storage.onBudgetExhausted
        if (result.mustStop || (!result.budgetMet && action == BudgetAction.STOP)) {
            scope.launch { stop(StopReason.STORAGE_FULL) }
        } else if (!result.budgetMet) {
            Notifications.alert(
                context,
                "Storage almost full",
                "Only protected clips are left to delete. Recording stops when free space runs out.",
            )
        }
    }

    private fun publishCleanup(result: CleanupResult) {
        _health.update { it.copy(lastCleanup = result) }
        if (result.deleted.isNotEmpty() || result.failed.isNotEmpty()) {
            Timber.i(
                "Cleanup %s: deleted %d (%d bytes), failed %d, budgetMet=%s",
                result.trigger, result.deleted.size, result.bytesFreed, result.failed.size, result.budgetMet,
            )
        }
    }

    // ---------------------------------------------------------------- camera

    /** [force] rebinds mid-session; only the thermal step-down does that, between two segments. */
    private suspend fun ensureCamera(cfg: FrameSettings, mode: Mode, force: Boolean = false) {
        val provider = cameraProvider ?: awaitCameraProvider().also { cameraProvider = it }
        if (running && !force) return // never rebind in the middle of a session otherwise
        overlayMode = mode
        val wantOverlay = overlayWanted(cfg, mode)
        if (recorder != null && boundQuality == cfg.quality && boundOverlay == wantOverlay) return

        val newRecorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(cfg.quality.toCameraX(), FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)),
            )
            .build()
        val videoCapture = VideoCapture.withOutput(newRecorder)
        provider.unbindAll()
        overlay?.release()
        overlay = null
        val fx = if (wantOverlay) VideoOverlay(::overlayLines) else null
        try {
            bind(provider, videoCapture, fx)
            overlay = fx
        } catch (e: Exception) {
            if (fx == null) throw e
            // Some devices can't share one stream between preview, video and an effect.
            Timber.w(e, "Overlay effect not supported on this device; recording without it")
            fx.release()
            provider.unbindAll()
            bind(provider, videoCapture, null)
        }
        recorder = newRecorder
        boundQuality = cfg.quality
        boundOverlay = wantOverlay
    }

    private fun bind(provider: ProcessCameraProvider, videoCapture: VideoCapture<Recorder>, fx: VideoOverlay?) {
        val group = UseCaseGroup.Builder()
            .addUseCase(preview)
            .addUseCase(videoCapture)
            .apply { if (fx != null) addEffect(fx.effect) }
            .build()
        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, group)
    }

    private fun overlayWanted(cfg: FrameSettings, mode: Mode) = when (mode) {
        Mode.DASHCAM -> cfg.overlay.dashcam
        Mode.SPORTS -> cfg.overlay.sports
        Mode.CAMERA -> false
    }

    /** Called on the overlay thread for every frame: only reads volatile fields. */
    private fun overlayLines(): List<String> = buildList {
        add(overlayTimeFormat.format(Date()))
        when (overlayMode) {
            Mode.DASHCAM -> {
                val fix = _location.value
                add(fix?.speedKmh?.let { "%.0f km/h".format(Locale.US, it) } ?: "--- km/h")
                if (fix != null) add("%.5f, %.5f".format(Locale.US, fix.lat, fix.lon))
            }
            Mode.SPORTS -> {
                overlayCaption?.let { add(it) }
                overlaySessionStart?.let { add(formatClock(System.currentTimeMillis() - it)) }
            }
            Mode.CAMERA -> Unit
        }
    }

    /** Text the sports screen wants burned in (e.g. "Home 2 - 1 Away · P2"); null clears it. */
    fun setOverlayCaption(text: String?) {
        overlayCaption = text
    }

    private suspend fun awaitCameraProvider(): ProcessCameraProvider = suspendCancellableCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                try {
                    cont.resume(future.get())
                } catch (e: Exception) {
                    cont.resumeWithException(e)
                }
            },
            mainExecutor,
        )
    }

    /** Camera is open while a screen shows the preview or a session runs; closed otherwise. */
    private fun updateLifecycle() {
        lifecycleOwner.registry.currentState =
            if (previewAttached || running) Lifecycle.State.RESUMED else Lifecycle.State.CREATED
    }

    // ---------------------------------------------------------------- helpers

    private fun wantsAudio(cfg: FrameSettings, mode: Mode) = when (mode) {
        Mode.DASHCAM -> cfg.dashcam.recordAudio
        Mode.SPORTS -> cfg.sports.recordAudio
        Mode.CAMERA -> true
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun log(sessionId: String?, kind: String, detail: String?) {
        Timber.i("[%s] %s %s", sessionId, kind, detail.orEmpty())
        scope.launch(Dispatchers.IO) {
            runCatching {
                db.logDao().insertRecording(RecordingLogEntity(sessionId = sessionId, time = clock.nowMillis(), kind = kind, detail = detail))
            }
        }
    }

    private fun messageFor(reason: StopReason) = when (reason) {
        StopReason.USER -> "Stopped."
        StopReason.CHARGER_OFF -> "Charger disconnected."
        StopReason.BLUETOOTH_OFF -> "Car Bluetooth disconnected."
        StopReason.STORAGE_FULL -> "Storage is full. Protected clips were kept; free some space to keep recording."
        StopReason.THERMAL -> "The phone got too hot. Let it cool down, out of direct sunlight."
        StopReason.BATTERY_LOW -> "Battery is almost empty. Connect a charger."
        StopReason.CAMERA_ERROR -> "The camera stopped responding, or another app took it."
        StopReason.CRASH -> "Recording ended unexpectedly."
    }

    private class EngineLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.CREATED }
        override val lifecycle: Lifecycle get() = registry
    }

    private companion object {
        const val AUDIO_BITRATE = 128_000
        const val MAX_RETRIES = 3
        const val FINALIZE_TIMEOUT_MS = 5_000L
        const val HEALTH_INTERVAL_MS = 5_000L
        const val STALL_MS = 15_000L
        const val STEP_DOWN_COOLDOWN_MS = 2 * 60_000L
        val CLEAN_CLOSE_CODES = setOf(
            VideoRecordEvent.Finalize.ERROR_NONE,
            VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED,
            VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED,
        )
    }
}

private fun formatClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(Locale.US, h, m, s) else "%02d:%02d".format(Locale.US, m, s)
}

private fun VideoQuality.lower(): VideoQuality? = when (this) {
    VideoQuality.UHD -> VideoQuality.FHD
    VideoQuality.FHD -> VideoQuality.HD
    VideoQuality.HD -> null
}

private fun VideoQuality.toCameraX(): Quality = when (this) {
    VideoQuality.HD -> Quality.HD
    VideoQuality.FHD -> Quality.FHD
    VideoQuality.UHD -> Quality.UHD
}
