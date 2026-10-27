package io.frame.core.retention

import io.frame.core.model.Mode
import io.frame.core.model.RetentionSettings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class CleanupTrigger { RECORDING_START, SEGMENT_DONE, PERIODIC, LOW_SPACE, APP_START, USER }

enum class DeleteReason { EXPIRED, MODE_BUDGET, LOW_SPACE }

data class Deletion(val candidate: SegmentCandidate, val reason: DeleteReason)

data class CleanupResult(
    val trigger: CleanupTrigger,
    val deleted: List<Deletion>,
    val failed: List<Deletion>,
    /** False when free space is still below reserve + next segment and nothing eligible is left. */
    val budgetMet: Boolean,
    /** True when free space is below the hard floor: the recorder must finalize and stop. */
    val mustStop: Boolean,
    val freeBytesAfter: Long,
) {
    val bytesFreed: Long get() = deleted.sumOf { it.candidate.sizeBytes }
}

/**
 * Smart Loop cleanup. Rules (see docs/02-smart-loop-engine.md):
 *  1. delete expired eligible segments, per mode;
 *  2. delete oldest eligible segments while a mode is over its byte cap;
 *  3. delete oldest eligible segments (any mode) while free space < reserve + next segment.
 * Protected and unfinished segments are never eligible; that rule lives in [SegmentStore]
 * and is re-checked by [SegmentStore.beginDelete] right before each delete.
 */
class RetentionEngine(
    private val store: SegmentStore,
    private val stats: StorageStats,
    private val deleter: FileDeleter,
    private val clock: Clock,
    private val settings: suspend () -> RetentionSettings,
) {
    private val mutex = Mutex()

    suspend fun runCleanup(trigger: CleanupTrigger, nextSegmentBytes: Long = 0): CleanupResult = mutex.withLock {
        val config = settings()
        val now = clock.nowMillis()
        val run = Run()

        for (policy in config.policies) {
            if (policy.maxAgeMinutes <= 0) continue
            val cutoff = now - policy.maxAgeMillis
            while (true) {
                val next = run.next(policy.mode, cutoff) ?: break
                run.delete(next, DeleteReason.EXPIRED)
            }
        }

        for (policy in config.policies) {
            if (policy.maxBytes <= 0) continue
            while (store.bytesUsed(policy.mode) > policy.maxBytes) {
                val next = run.next(policy.mode, Long.MAX_VALUE) ?: break
                run.delete(next, DeleteReason.MODE_BUDGET)
            }
        }

        val reserve = config.storage.reserveBytes(stats.totalBytes())
        var budgetMet = true
        while (stats.freeBytes() < reserve + nextSegmentBytes) {
            val next = run.next(null, Long.MAX_VALUE)
            if (next == null) {
                budgetMet = false
                break
            }
            run.delete(next, DeleteReason.LOW_SPACE)
        }

        val free = stats.freeBytes()
        CleanupResult(
            trigger = trigger,
            deleted = run.deleted,
            failed = run.failed,
            budgetMet = budgetMet,
            mustStop = free < config.storage.hardFloorBytes,
            freeBytesAfter = free,
        )
    }

    /** State for one cleanup run; remembers failures so a stuck file can't loop forever. */
    private inner class Run {
        val deleted = mutableListOf<Deletion>()
        val failed = mutableListOf<Deletion>()
        private val skip = mutableSetOf<String>()

        suspend fun next(mode: Mode?, endedBefore: Long): SegmentCandidate? =
            store.oldestEligible(mode, endedBefore, limit = skip.size + 1)
                .firstOrNull { it.id !in skip }

        suspend fun delete(candidate: SegmentCandidate, reason: DeleteReason) {
            skip += candidate.id
            if (!store.beginDelete(candidate.id)) return
            val ok = runCatching { deleter.delete(candidate.filePath) }.getOrDefault(false)
            store.finishDelete(candidate, reason, ok)
            if (ok) deleted += Deletion(candidate, reason) else failed += Deletion(candidate, reason)
        }
    }
}
