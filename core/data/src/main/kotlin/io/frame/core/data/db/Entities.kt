package io.frame.core.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import io.frame.core.model.EventSource
import io.frame.core.model.EventType
import io.frame.core.model.Mode
import io.frame.core.model.SegmentState
import io.frame.core.model.StopReason

// Schema documented in docs/08-data-model.md. Enums are stored by name.

@Entity(tableName = "recording_session", indices = [Index("mode", "startedAt")])
data class SessionEntity(
    @PrimaryKey val id: String,
    val mode: Mode,
    val startedAt: Long,
    val endedAt: Long?,
    val endReason: StopReason?,
    val folderPath: String,
    val title: String?,
    val audioEnabled: Boolean,
)

@Entity(
    tableName = "segment",
    indices = [Index("sessionId"), Index("state", "endedAt"), Index("mode", "state", "endedAt")],
)
data class SegmentEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val mode: Mode,
    /** 1-based position within the session ("index" is an SQL keyword). */
    val seq: Int,
    val filePath: String,
    val startedAt: Long,
    val endedAt: Long?,
    val durationMs: Long?,
    val sizeBytes: Long?,
    val state: SegmentState,
    val protectedManually: Boolean = false,
    val favorite: Boolean = false,
    /** Tamper evidence (io.frame.core.evidence.Evidence); kept after the file is deleted. */
    val sha256: String? = null,
    val prevChainHash: String? = null,
    val chainHash: String? = null,
)

@Entity(tableName = "event", indices = [Index("sessionId"), Index("time")])
data class EventEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val type: EventType,
    val source: EventSource,
    val time: Long,
    val windowStart: Long,
    val windowEnd: Long,
    /** True until recording has passed [windowEnd]; new segments are linked while open. */
    val open: Boolean,
    val peakG: Float? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val speedKmh: Float? = null,
    val label: String? = null,
)

@Entity(tableName = "event_segment", primaryKeys = ["eventId", "segmentId"], indices = [Index("segmentId")])
data class EventSegmentCrossRef(val eventId: String, val segmentId: String)

@Entity(tableName = "match")
data class MatchEntity(
    @PrimaryKey val sessionId: String,
    val sport: String,
    val teamA: String,
    val teamB: String,
    val scoreA: Int = 0,
    val scoreB: Int = 0,
    val period: Int = 1,
)

@Entity(tableName = "score_change", indices = [Index("sessionId", "time")])
data class ScoreChangeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val time: Long,
    val scoreA: Int,
    val scoreB: Int,
    val period: Int,
)

@Entity(tableName = "telemetry_point", indices = [Index("sessionId", "time")])
data class TelemetryPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val time: Long,
    val lat: Double,
    val lon: Double,
    val speedKmh: Float?,
    val bearing: Float?,
    val accuracyM: Float?,
)

@Entity(tableName = "cleanup_log", indices = [Index("time")])
data class CleanupLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val time: Long,
    val segmentId: String,
    val filePath: String,
    val sizeBytes: Long,
    /** EXPIRED, MODE_BUDGET, LOW_SPACE or USER. */
    val reason: String,
    val success: Boolean,
)

/** Health and lifecycle timeline of a session (start, rollovers, thermal, errors). */
@Entity(tableName = "recording_log", indices = [Index("sessionId", "time")])
data class RecordingLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String?,
    val time: Long,
    val kind: String,
    val detail: String?,
)

// ---- query results ----

data class EligibleSegmentRow(
    val id: String,
    val mode: Mode,
    val filePath: String,
    val endedAt: Long,
    val sizeBytes: Long,
)

data class SessionSummary(
    @Embedded val session: SessionEntity,
    val segmentCount: Int,
    val totalBytes: Long,
    val eventCount: Int,
)

data class SegmentWithProtection(
    @Embedded val segment: SegmentEntity,
    val isProtected: Boolean,
)

data class ModeUsage(val mode: Mode, val bytes: Long, val count: Int)
