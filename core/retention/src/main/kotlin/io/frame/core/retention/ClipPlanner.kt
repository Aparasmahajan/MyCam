package io.frame.core.retention

import io.frame.core.model.TimeWindow

/** A finished segment on the session timeline. */
data class SegmentSpan(
    val id: String,
    val path: String,
    val startedAt: Long,
    val durationMs: Long,
)

/** The part of one segment file that goes into an exported clip, in file-relative milliseconds. */
data class ClipPart(
    val segmentId: String,
    val path: String,
    val startMs: Long,
    val endMs: Long,
) {
    val lengthMs: Long get() = endMs - startMs
}

/**
 * Turns a wall-clock window (an event's before/after buffer, or a user's trim) into the
 * pieces of segment files that cover it, in order. Gaps between segments are simply skipped.
 */
object ClipPlanner {
    /** Slivers shorter than this (e.g. a window touching a segment edge) are dropped. */
    const val MIN_PART_MS = 200L

    fun plan(segments: List<SegmentSpan>, window: TimeWindow): List<ClipPart> =
        segments.sortedBy { it.startedAt }.mapNotNull { s ->
            val start = (window.start - s.startedAt).coerceIn(0, s.durationMs)
            val end = (window.end - s.startedAt).coerceIn(0, s.durationMs)
            if (end - start >= MIN_PART_MS) ClipPart(s.id, s.path, start, end) else null
        }

    /**
     * A highlight reel: every window in time order. Overlapping windows (two highlights close
     * together) are merged first, so no footage appears twice.
     */
    fun planReel(segments: List<SegmentSpan>, windows: List<TimeWindow>): List<ClipPart> =
        Protection.merge(windows).flatMap { plan(segments, it) }

    /** True when the part is (practically) the whole file, so it can be copied instead of re-encoded. */
    fun isWholeFile(part: ClipPart, span: SegmentSpan): Boolean =
        part.startMs == 0L && part.endMs >= span.durationMs - 250
}
