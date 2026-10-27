package io.frame.app.export

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import io.frame.app.ui.components.label
import io.frame.core.data.LibraryRepository
import io.frame.core.data.db.EventEntity
import io.frame.core.data.db.SegmentEntity
import io.frame.core.model.TimeWindow
import io.frame.core.retention.ClipPart
import io.frame.core.retention.ClipPlanner
import io.frame.core.retention.SegmentSpan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface ExportStatus {
    data object Idle : ExportStatus
    data class Running(val label: String, val percent: Int?) : ExportStatus
    data class Done(val label: String, val uris: List<Uri>, val mime: String = Gallery.VIDEO_MIME) : ExportStatus {
        val folder: String get() = if (mime == Gallery.VIDEO_MIME) Gallery.VIDEO_FOLDER else Gallery.DOCUMENT_FOLDER
    }
    data class Failed(val label: String, val message: String) : ExportStatus
}

/**
 * Saves footage to the gallery: whole segments are copied as-is; trims and event clips are cut
 * with Media3 Transformer (it only re-encodes when a cut doesn't fall on a keyframe).
 * One export at a time; runs in the app scope so leaving a screen doesn't cancel it.
 */
// Transformer, Composition and friends are @UnstableApi in Media3 1.5.
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ClipExporter(
    private val context: Context,
    private val library: LibraryRepository,
    private val scope: CoroutineScope,
) {
    private val _status = MutableStateFlow<ExportStatus>(ExportStatus.Idle)
    val status: StateFlow<ExportStatus> = _status.asStateFlow()
    private var job: Job? = null

    val busy: Boolean get() = job?.isActive == true

    fun exportSegment(segment: SegmentEntity) = launchExport("Segment ${segment.seq}") {
        listOf(copy(segment, "seg${segment.seq}"))
    }

    fun exportTrim(segment: SegmentEntity, startMs: Long, endMs: Long) = launchExport("Trimmed clip") { label ->
        val part = ClipPart(segment.id, segment.filePath, startMs, endMs)
        listOf(render(listOf(part), fileName(segment.startedAt + startMs, "trim"), label))
    }

    /** The event's exact before/after window, stitched across segment boundaries. */
    fun exportEvent(event: EventEntity) = launchExport("${event.type.label()} clip") { label ->
        val spans = library.playableSegments(event.sessionId).map { it.toSpan() }
        val parts = ClipPlanner.plan(spans, TimeWindow(event.windowStart, event.windowEnd))
        check(parts.isNotEmpty()) { "The footage for this event is no longer available." }
        val name = fileName(event.time, event.type.label().lowercase())
        val single = parts.singleOrNull()
        val span = single?.let { p -> spans.first { it.id == p.segmentId } }
        if (single != null && span != null && ClipPlanner.isWholeFile(single, span)) {
            listOf(withContext(Dispatchers.IO) { Gallery.save(context, File(single.path), name) })
        } else {
            listOf(render(parts, name, label))
        }
    }

    /** All of a session's events (sports highlights, dashcam incidents) joined into one video. */
    fun exportHighlightReel(sessionId: String) = launchExport("Highlight reel") { label ->
        val events = library.eventList(sessionId).filterNot { it.open }
        check(events.isNotEmpty()) { "This recording has no finished highlights yet." }
        val spans = library.playableSegments(sessionId).map { it.toSpan() }
        val parts = ClipPlanner.planReel(spans, events.map { TimeWindow(it.windowStart, it.windowEnd) })
        check(parts.isNotEmpty()) { "The footage for these highlights is no longer available." }
        listOf(render(parts, fileName(events.first().time, "reel"), label))
    }

    /** Copies protected footage out of app storage, e.g. before uninstalling. [sessionId] null = all. */
    fun exportProtected(sessionId: String?) = launchExport(if (sessionId == null) "All protected clips" else "Protected clips") { label ->
        val segments = library.protectedSegments(sessionId)
        check(segments.isNotEmpty()) { "There are no protected clips to export." }
        segments.mapIndexed { i, seg ->
            _status.value = ExportStatus.Running(label, i * 100 / segments.size)
            copy(seg, "protected_seg${seg.seq}")
        }
    }

    /** A PDF for insurers or the police: time, place, speed, g-force and the footage's hashes. */
    fun exportIncidentReport(event: EventEntity) = launchExport("Incident report", Gallery.PDF_MIME) {
        listOf(IncidentReport(context, library).write(event))
    }

    fun cancel() {
        job?.cancel()
    }

    fun dismiss() {
        if (!busy) _status.value = ExportStatus.Idle
    }

    private fun launchExport(
        label: String,
        mime: String = Gallery.VIDEO_MIME,
        block: suspend (label: String) -> List<Uri>,
    ) {
        if (busy) return
        job = scope.launch {
            _status.value = ExportStatus.Running(label, null)
            try {
                _status.value = ExportStatus.Done(label, block(label), mime)
            } catch (e: CancellationException) {
                _status.value = ExportStatus.Idle
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Export failed: %s", label)
                _status.value = ExportStatus.Failed(label, e.message ?: "Export failed")
            }
        }
    }

    private suspend fun copy(segment: SegmentEntity, suffix: String): Uri = withContext(Dispatchers.IO) {
        Gallery.save(context, File(segment.filePath), fileName(segment.startedAt, suffix))
    }

    private suspend fun render(parts: List<ClipPart>, displayName: String, label: String): Uri {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val out = File(dir, "render_${System.currentTimeMillis()}.mp4")
        try {
            transform(parts, out, label)
            return withContext(Dispatchers.IO) { Gallery.save(context, out, displayName) }
        } finally {
            out.delete()
        }
    }

    /** Transformer must be driven from a thread with a Looper; the main thread is used. */
    private suspend fun transform(parts: List<ClipPart>, out: File, label: String) = withContext(Dispatchers.Main) {
        val items = parts.map { p ->
            val clipping = MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(p.startMs)
                .setEndPositionMs(p.endMs)
                .build()
            val item = MediaItem.Builder().setUri(Uri.fromFile(File(p.path))).setClippingConfiguration(clipping).build()
            EditedMediaItem.Builder(item).build()
        }
        val composition = Composition.Builder(EditedMediaItemSequence(items)).build()
        val done = CompletableDeferred<Unit>()
        val transformer = Transformer.Builder(context)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    done.complete(Unit)
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    done.completeExceptionally(exportException)
                }
            })
            .build()
        transformer.start(composition, out.absolutePath)
        val progress = ProgressHolder()
        try {
            while (!done.isCompleted) {
                if (transformer.getProgress(progress) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    _status.value = ExportStatus.Running(label, progress.progress)
                }
                delay(PROGRESS_POLL_MS)
            }
            done.await()
        } finally {
            if (!done.isCompleted) transformer.cancel()
        }
    }

    private fun fileName(time: Long, suffix: String): String =
        "FRAME_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(time))}_$suffix.mp4"

    private fun SegmentEntity.toSpan() = SegmentSpan(
        id = id,
        path = filePath,
        startedAt = startedAt,
        durationMs = durationMs ?: ((endedAt ?: startedAt) - startedAt),
    )

    private companion object {
        const val PROGRESS_POLL_MS = 300L
    }
}
