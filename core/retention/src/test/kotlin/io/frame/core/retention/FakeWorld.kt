package io.frame.core.retention

import io.frame.core.model.Mode
import io.frame.core.model.SegmentState

/** In-memory disk + database used by the retention tests. */
class FakeWorld(
    var totalBytes: Long,
    var freeBytes: Long,
    var now: Long = 1_000_000_000_000L,
) : SegmentStore, StorageStats, FileDeleter, Clock {

    data class Seg(
        val id: String,
        val mode: Mode,
        val startedAt: Long,
        val endedAt: Long?,
        val size: Long,
        var state: SegmentState,
        var protected: Boolean = false,
    ) {
        val path get() = "/rec/$id.mp4"
    }

    val segs = mutableListOf<Seg>()
    val failingPaths = mutableSetOf<String>()
    /** Ids that get protected by "another thread" between listing and deleting. */
    val protectOnBegin = mutableSetOf<String>()
    var deleteCalls = 0

    fun add(
        id: String,
        mode: Mode = Mode.DASHCAM,
        ageMinutes: Long = 0,
        size: Long = 100,
        state: SegmentState = SegmentState.COMPLETE,
        protected: Boolean = false,
    ): Seg {
        val end = now - ageMinutes * 60_000
        val seg = Seg(id, mode, end - 180_000, if (state == SegmentState.WRITING) null else end, size, state, protected)
        segs += seg
        freeBytes -= size
        return seg
    }

    fun seg(id: String) = segs.first { it.id == id }

    fun isEligible(s: Seg) = s.state.isCleanupCandidate && !s.protected

    override suspend fun oldestEligible(mode: Mode?, endedBefore: Long, limit: Int): List<SegmentCandidate> =
        segs.filter { isEligible(it) && (mode == null || it.mode == mode) && it.endedAt!! < endedBefore }
            .sortedBy { it.endedAt }
            .take(limit)
            .map { SegmentCandidate(it.id, it.mode, it.path, it.endedAt!!, it.size) }

    override suspend fun bytesUsed(mode: Mode): Long =
        segs.filter { it.mode == mode && it.state != SegmentState.DELETED }.sumOf { it.size }

    override suspend fun beginDelete(id: String): Boolean {
        val s = seg(id)
        if (id in protectOnBegin) s.protected = true
        if (!isEligible(s)) return false
        s.state = SegmentState.DELETING
        return true
    }

    override suspend fun finishDelete(candidate: SegmentCandidate, reason: DeleteReason, success: Boolean) {
        seg(candidate.id).state = if (success) SegmentState.DELETED else SegmentState.COMPLETE
    }

    override fun delete(path: String): Boolean {
        deleteCalls++
        if (path in failingPaths) return false
        val s = segs.first { it.path == path }
        freeBytes += s.size
        return true
    }

    override fun freeBytes(): Long = freeBytes
    override fun totalBytes(): Long = totalBytes
    override fun nowMillis(): Long = now
}
