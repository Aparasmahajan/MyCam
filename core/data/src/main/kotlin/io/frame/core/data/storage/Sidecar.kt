package io.frame.core.data.storage

import io.frame.core.data.db.SegmentEntity
import io.frame.core.data.db.SessionEntity
import io.frame.core.model.Mode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Per-segment metadata written next to each video. Enough to rebuild the database index.
 * The protected flag is a snapshot at finalize time; the database stays authoritative.
 */
@Serializable
data class SegmentSidecar(
    val schema: Int = 1,
    val segmentId: String,
    val sessionId: String,
    val sessionStartedAt: Long,
    val mode: Mode,
    val seq: Int,
    val startedAt: Long,
    val endedAt: Long?,
    val durationMs: Long?,
    val sizeBytes: Long?,
    val audio: Boolean,
    val events: List<String> = emptyList(),
    val protected: Boolean = false,
    val sha256: String? = null,
    val prevChainHash: String? = null,
    val chainHash: String? = null,
)

object Sidecars {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    fun write(session: SessionEntity, segment: SegmentEntity, events: List<String>, protected: Boolean) {
        val sidecar = SegmentSidecar(
            segmentId = segment.id,
            sessionId = session.id,
            sessionStartedAt = session.startedAt,
            mode = segment.mode,
            seq = segment.seq,
            startedAt = segment.startedAt,
            endedAt = segment.endedAt,
            durationMs = segment.durationMs,
            sizeBytes = segment.sizeBytes,
            audio = session.audioEnabled,
            events = events,
            protected = protected,
            sha256 = segment.sha256,
            prevChainHash = segment.prevChainHash,
            chainHash = segment.chainHash,
        )
        val file = RecordingFiles.sidecarFor(File(segment.filePath))
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(SegmentSidecar.serializer(), sidecar))
        tmp.renameTo(file)
    }

    fun read(video: File): SegmentSidecar? = runCatching {
        json.decodeFromString(SegmentSidecar.serializer(), RecordingFiles.sidecarFor(video).readText())
    }.getOrNull()
}
