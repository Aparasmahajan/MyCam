package io.frame.core.data

import androidx.room.withTransaction
import io.frame.core.data.db.CleanupLogEntity
import io.frame.core.data.db.EventEntity
import io.frame.core.data.db.FrameDatabase
import io.frame.core.data.db.ModeUsage
import io.frame.core.data.db.SegmentWithProtection
import io.frame.core.data.db.SessionEntity
import io.frame.core.data.db.SessionSummary
import io.frame.core.data.storage.FsDeleter
import io.frame.core.model.Mode
import io.frame.core.model.SegmentState
import io.frame.core.evidence.Evidence
import io.frame.core.retention.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

/** Library and storage-manager reads, plus the user's own protect/delete actions. */
class LibraryRepository(
    private val db: FrameDatabase,
    private val clock: Clock,
) {
    fun sessions(mode: Mode?): Flow<List<SessionSummary>> = db.sessionDao().observeSummaries(mode?.name)

    fun session(id: String): Flow<SessionEntity?> = db.sessionDao().observe(id)

    fun segments(sessionId: String): Flow<List<SegmentWithProtection>> =
        db.segmentDao().observeForSession(sessionId)

    fun events(sessionId: String): Flow<List<EventEntity>> = db.eventDao().observeForSession(sessionId)

    fun usage(): Flow<List<ModeUsage>> = db.segmentDao().observeUsage()

    fun protectedBytes(): Flow<Long> = db.segmentDao().observeProtectedBytes()

    fun cleanupHistory(): Flow<List<CleanupLogEntity>> = db.logDao().observeCleanups()

    suspend fun playableSegments(sessionId: String) =
        db.segmentDao().liveForSession(sessionId).filter { it.state == SegmentState.COMPLETE }

    suspend fun protectedSegments(sessionId: String?) = db.segmentDao().protectedSegments(sessionId)

    suspend fun eventList(sessionId: String): List<EventEntity> = db.eventDao().listForSession(sessionId)

    /** Re-hashes every surviving file and checks the chain (see io.frame.core.evidence.Evidence). */
    suspend fun verifySession(sessionId: String): Evidence.Report = withContext(Dispatchers.IO) {
        val records = db.segmentDao().allForSession(sessionId)
            .filter { it.endedAt != null }
            .map {
                Evidence.Record(
                    link = Evidence.Link(it.sessionId, it.seq, it.startedAt, it.endedAt!!),
                    path = it.filePath,
                    fileHash = it.sha256,
                    prevChainHash = it.prevChainHash,
                    chainHash = it.chainHash,
                    deleted = it.state == SegmentState.DELETED || it.state == SegmentState.DELETING,
                )
            }
        Evidence.verify(records) { path -> File(path).takeIf { it.exists() }?.let(Evidence::sha256) }
    }

    suspend fun telemetry(sessionId: String, from: Long, to: Long) = db.telemetryDao().range(sessionId, from, to)

    suspend fun getSession(id: String): SessionEntity? = db.sessionDao().get(id)

    /**
     * Unprotecting also removes the segment's event links, otherwise it would stay protected.
     * The event itself is kept so it still shows in the session's timeline.
     */
    suspend fun setProtected(segmentId: String, protected: Boolean) = db.withTransaction {
        db.segmentDao().setProtectedManually(segmentId, protected)
        if (!protected) db.eventDao().unlinkSegment(segmentId)
    }

    suspend fun setFavorite(segmentId: String, favorite: Boolean) = db.segmentDao().setFavorite(segmentId, favorite)

    /** A user delete. The UI confirms twice for protected footage; the active segment can never be deleted. */
    suspend fun deleteSegment(segmentId: String): Boolean {
        val seg = db.segmentDao().get(segmentId) ?: return false
        if (!seg.state.isCleanupCandidate) return false
        db.segmentDao().setState(seg.id, SegmentState.DELETING)
        val ok = FsDeleter.delete(seg.filePath)
        db.withTransaction {
            db.segmentDao().setState(seg.id, if (ok) SegmentState.DELETED else seg.state)
            if (ok) db.eventDao().unlinkSegment(seg.id)
            db.logDao().insertCleanup(
                CleanupLogEntity(
                    time = clock.nowMillis(),
                    segmentId = seg.id,
                    filePath = seg.filePath,
                    sizeBytes = seg.sizeBytes ?: 0,
                    reason = "USER",
                    success = ok,
                ),
            )
        }
        return ok
    }

    suspend fun deleteSession(sessionId: String): Int {
        var deleted = 0
        for (seg in db.segmentDao().liveForSession(sessionId)) {
            if (deleteSegment(seg.id)) deleted++
        }
        return deleted
    }
}
