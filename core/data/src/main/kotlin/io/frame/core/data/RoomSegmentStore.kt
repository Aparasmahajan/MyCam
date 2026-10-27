package io.frame.core.data

import io.frame.core.data.db.CleanupLogEntity
import io.frame.core.data.db.FrameDatabase
import io.frame.core.model.Mode
import io.frame.core.model.SegmentState
import io.frame.core.retention.Clock
import io.frame.core.retention.DeleteReason
import io.frame.core.retention.SegmentCandidate
import io.frame.core.retention.SegmentStore
import java.util.concurrent.ConcurrentHashMap

/** [SegmentStore] backed by Room. Eligibility rules live in the SQL of [io.frame.core.data.db.SegmentDao]. */
class RoomSegmentStore(
    private val db: FrameDatabase,
    private val clock: Clock,
) : SegmentStore {

    /** State before DELETING, so a failed delete can be rolled back exactly. */
    private val previousState = ConcurrentHashMap<String, SegmentState>()

    override suspend fun oldestEligible(mode: Mode?, endedBefore: Long, limit: Int): List<SegmentCandidate> =
        db.segmentDao().oldestEligible(mode?.name, endedBefore, limit).map {
            SegmentCandidate(it.id, it.mode, it.filePath, it.endedAt, it.sizeBytes)
        }

    override suspend fun bytesUsed(mode: Mode): Long = db.segmentDao().bytesUsed(mode.name)

    override suspend fun beginDelete(id: String): Boolean {
        val before = db.segmentDao().get(id)?.state ?: return false
        if (db.segmentDao().beginDelete(id) == 0) return false
        previousState[id] = before
        return true
    }

    override suspend fun finishDelete(candidate: SegmentCandidate, reason: DeleteReason, success: Boolean) {
        val restore = previousState.remove(candidate.id) ?: SegmentState.COMPLETE
        db.segmentDao().setState(candidate.id, if (success) SegmentState.DELETED else restore)
        db.logDao().insertCleanup(
            CleanupLogEntity(
                time = clock.nowMillis(),
                segmentId = candidate.id,
                filePath = candidate.filePath,
                sizeBytes = candidate.sizeBytes,
                reason = reason.name,
                success = success,
            ),
        )
    }
}
