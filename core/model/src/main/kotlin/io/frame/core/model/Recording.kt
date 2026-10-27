package io.frame.core.model

import kotlinx.serialization.Serializable

/** A recording profile: same engine, different defaults and UI. */
@Serializable
enum class Mode { DASHCAM, SPORTS, CAMERA }

/**
 * Lifecycle of one segment file. "Protected" is deliberately not a state: it is derived from
 * the manual flag plus links to events, so deleting an event can never leave a stale state behind.
 */
enum class SegmentState {
    WRITING,
    FINALIZING,
    COMPLETE,
    ORPHANED,
    CORRUPT,
    DELETING,
    DELETED;

    /** States that cleanup is allowed to consider (still subject to protection). */
    val isCleanupCandidate: Boolean get() = this == COMPLETE || this == CORRUPT
}

enum class EventType { MANUAL, IMPACT, HIGHLIGHT_GOAL, HIGHLIGHT_WICKET, HIGHLIGHT_POINT, HIGHLIGHT_CUSTOM }

enum class EventSource { SCREEN, VOLUME_KEY, BT_REMOTE, NOTIFICATION, G_SENSOR, SCORE_CHANGE, WEAR }

enum class StopReason { USER, CHARGER_OFF, BLUETOOTH_OFF, STORAGE_FULL, THERMAL, BATTERY_LOW, CAMERA_ERROR, CRASH }

/** Inclusive time window in epoch milliseconds. */
data class TimeWindow(val start: Long, val end: Long) {
    init {
        require(end >= start) { "window end ($end) is before start ($start)" }
    }

    fun overlaps(otherStart: Long, otherEnd: Long): Boolean = otherStart <= end && otherEnd >= start
}
