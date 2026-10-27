package io.frame.core.retention

import io.frame.core.model.TimeWindow

/** Event-window maths shared by the event manager and its tests. */
object Protection {

    /** Merges overlapping or touching windows into the smallest set of disjoint windows. */
    fun merge(windows: List<TimeWindow>): List<TimeWindow> {
        if (windows.isEmpty()) return emptyList()
        val sorted = windows.sortedBy { it.start }
        val out = mutableListOf(sorted.first())
        for (w in sorted.drop(1)) {
            val last = out.last()
            if (w.start <= last.end) out[out.lastIndex] = TimeWindow(last.start, maxOf(last.end, w.end))
            else out += w
        }
        return out
    }

    /**
     * True if a segment covering [segmentStart, segmentEnd] must be protected by [window].
     * An unfinished segment (segmentEnd == null) is treated as running into the future.
     */
    fun covers(window: TimeWindow, segmentStart: Long, segmentEnd: Long?): Boolean =
        window.overlaps(segmentStart, segmentEnd ?: Long.MAX_VALUE)
}

/** Rough size of the next segment, so cleanup can make room before it starts. */
object SegmentSizeEstimator {
    /** 5% headroom for the container and audio track. */
    fun bytes(videoBitrate: Int, audioBitrate: Int, seconds: Int): Long =
        ((videoBitrate.toLong() + audioBitrate) / 8 * seconds * 105) / 100
}
