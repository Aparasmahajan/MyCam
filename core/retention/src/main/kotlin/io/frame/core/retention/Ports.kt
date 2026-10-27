package io.frame.core.retention

import io.frame.core.model.Mode

/**
 * The retention engine only talks to the outside world through these interfaces, so the
 * deletion rules can be tested on the JVM with fake time and fake disks.
 */
interface Clock {
    fun nowMillis(): Long

    object System : Clock {
        override fun nowMillis(): Long = java.lang.System.currentTimeMillis()
    }
}

interface StorageStats {
    fun freeBytes(): Long
    fun totalBytes(): Long
}

interface FileDeleter {
    /** Returns true if the file is gone afterwards (including when it was already missing). */
    fun delete(path: String): Boolean
}

/** A finished segment that cleanup is allowed to delete. */
data class SegmentCandidate(
    val id: String,
    val mode: Mode,
    val filePath: String,
    val endedAt: Long,
    val sizeBytes: Long,
)

interface SegmentStore {
    /**
     * Oldest-first segments that are finished (COMPLETE or CORRUPT), not protected manually,
     * not linked to any event, and ended before [endedBefore]. [mode] null = all modes.
     */
    suspend fun oldestEligible(mode: Mode?, endedBefore: Long, limit: Int): List<SegmentCandidate>

    /** Bytes used by all non-deleted segments of [mode]. */
    suspend fun bytesUsed(mode: Mode): Long

    /**
     * Atomically moves the segment to DELETING, but only if it is still eligible
     * (it may have been protected since it was listed). Returns false if not.
     */
    suspend fun beginDelete(id: String): Boolean

    /** Marks the segment DELETED, or rolls it back if the file could not be deleted, and logs it. */
    suspend fun finishDelete(candidate: SegmentCandidate, reason: DeleteReason, success: Boolean)
}
