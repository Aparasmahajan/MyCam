package io.frame.core.data.storage

import androidx.room.withTransaction
import io.frame.core.data.db.FrameDatabase
import io.frame.core.data.db.RecordingLogEntity
import io.frame.core.data.db.SegmentEntity
import io.frame.core.data.db.SessionEntity
import io.frame.core.model.SegmentState
import io.frame.core.model.StopReason
import io.frame.core.retention.Clock
import timber.log.Timber
import java.io.File

data class RecoveryReport(
    val recovered: Int,
    val corrupt: Int,
    val missing: Int,
    val rebuilt: Int,
    val crashedSessions: List<String>,
)

/**
 * Runs once at app start, before any recording (docs/02-smart-loop-engine.md, "Crash recovery"):
 * repairs rows left mid-write by a crash and re-indexes files that have no database row.
 */
class RecoveryScanner(
    private val db: FrameDatabase,
    private val files: RecordingFiles,
    private val clock: Clock,
) {
    suspend fun recover(): RecoveryReport {
        var recovered = 0
        var corrupt = 0
        var missing = 0
        val segments = db.segmentDao()

        for (seg in segments.interrupted()) {
            val file = File(seg.filePath)
            when {
                seg.state == SegmentState.DELETING && !file.exists() -> segments.setState(seg.id, SegmentState.DELETED)
                seg.state == SegmentState.DELETING -> segments.setState(seg.id, SegmentState.COMPLETE)
                !file.exists() -> {
                    segments.setState(seg.id, SegmentState.DELETED)
                    missing++
                }
                Mp4Inspector.hasMoov(file) -> {
                    segments.finish(seg.id, SegmentState.COMPLETE, file.lastModified(), null, file.length())
                    recovered++
                }
                else -> {
                    segments.finish(seg.id, SegmentState.CORRUPT, file.lastModified(), null, file.length())
                    corrupt++
                }
            }
        }

        val crashed = db.sessionDao().unfinished()
        for (session in crashed) {
            val lastEnd = segments.liveForSession(session.id).mapNotNull { it.endedAt }.maxOrNull()
            db.sessionDao().end(session.id, lastEnd ?: session.startedAt, StopReason.CRASH)
            db.eventDao().closeAll(session.id)
            db.logDao().insertRecording(
                RecordingLogEntity(sessionId = session.id, time = clock.nowMillis(), kind = "CRASH_RECOVERED", detail = null),
            )
        }

        val rebuilt = reindexOrphans()
        val report = RecoveryReport(recovered, corrupt, missing, rebuilt, crashed.map { it.id })
        Timber.i("Recovery: %s", report)
        return report
    }

    /** Files on disk with no database row (e.g. after the database was cleared) are re-indexed from sidecars. */
    private suspend fun reindexOrphans(): Int {
        var count = 0
        for (video in files.allVideoFiles()) {
            if (db.segmentDao().byPath(video.path) != null) continue
            val meta = Sidecars.read(video) ?: continue
            db.withTransaction {
                if (db.sessionDao().get(meta.sessionId) == null) {
                    db.sessionDao().insert(
                        SessionEntity(
                            id = meta.sessionId,
                            mode = meta.mode,
                            startedAt = meta.sessionStartedAt,
                            endedAt = meta.endedAt ?: meta.startedAt,
                            endReason = StopReason.CRASH,
                            folderPath = video.parent ?: "",
                            title = null,
                            audioEnabled = meta.audio,
                        ),
                    )
                }
                val playable = Mp4Inspector.hasMoov(video)
                db.segmentDao().insert(
                    SegmentEntity(
                        id = meta.segmentId,
                        sessionId = meta.sessionId,
                        mode = meta.mode,
                        seq = meta.seq,
                        filePath = video.path,
                        startedAt = meta.startedAt,
                        endedAt = meta.endedAt ?: video.lastModified(),
                        durationMs = meta.durationMs,
                        sizeBytes = video.length(),
                        state = if (playable) SegmentState.COMPLETE else SegmentState.CORRUPT,
                        // event links are not in the sidecar's ids alone, so keep protection as a manual flag
                        protectedManually = meta.protected,
                        sha256 = meta.sha256,
                        prevChainHash = meta.prevChainHash,
                        chainHash = meta.chainHash,
                    ),
                )
            }
            count++
        }
        return count
    }
}
