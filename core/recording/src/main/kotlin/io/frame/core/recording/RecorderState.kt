package io.frame.core.recording

import io.frame.core.model.Mode
import io.frame.core.model.StopReason
import io.frame.core.retention.CleanupResult

sealed interface RecorderState {
    data object Idle : RecorderState

    data class Starting(val mode: Mode) : RecorderState

    data class Recording(
        val mode: Mode,
        val sessionId: String,
        val sessionStartedAt: Long,
        val segmentSeq: Int,
        val eventCount: Int = 0,
        val lastEventAt: Long? = null,
    ) : RecorderState

    data class Stopping(val mode: Mode) : RecorderState

    /** Recording ended for a reason other than the user tapping Stop. Shown once, then back to Idle. */
    data class Stopped(val reason: StopReason, val message: String) : RecorderState

    data class Failed(val message: String) : RecorderState
}

data class LocationFix(
    val time: Long,
    val lat: Double,
    val lon: Double,
    val speedKmh: Float?,
    val bearing: Float?,
    val accuracyM: Float?,
)

data class HealthStatus(
    val freeBytes: Long = 0,
    val totalBytes: Long = 0,
    val reserveBytes: Long = 0,
    val batteryPercent: Int? = null,
    val charging: Boolean = false,
    /** android.os.PowerManager.THERMAL_STATUS_* */
    val thermalStatus: Int = 0,
    val warnings: List<String> = emptyList(),
    val lastCleanup: CleanupResult? = null,
)
