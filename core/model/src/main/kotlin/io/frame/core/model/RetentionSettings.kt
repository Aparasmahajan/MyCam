package io.frame.core.model

import kotlinx.serialization.Serializable

/** What to do when free space cannot be kept above the reserve without touching protected footage. */
@Serializable
enum class BudgetAction { WARN_THEN_STOP, STOP }

/** Retention rules for one mode. See docs/02-smart-loop-engine.md. */
@Serializable
data class RetentionPolicy(
    val mode: Mode,
    /** Delete unprotected footage older than this. 0 = never expire. */
    val maxAgeMinutes: Long,
    val segmentLengthSeconds: Int,
    /** Cap on bytes used by this mode. 0 = no cap. */
    val maxBytes: Long,
    val eventBeforeSeconds: Int,
    val eventAfterSeconds: Int,
) {
    val maxAgeMillis: Long get() = maxAgeMinutes * 60_000
    val segmentLengthMillis: Long get() = segmentLengthSeconds * 1_000L

    fun eventWindow(eventTime: Long): TimeWindow =
        TimeWindow(eventTime - eventBeforeSeconds * 1_000L, eventTime + eventAfterSeconds * 1_000L)
}

/** Device-wide storage rules that apply on top of every mode's policy. */
@Serializable
data class StorageSettings(
    val reservePercent: Int = 10,
    /** Below this much free space recording stops cleanly, even if that means stopping a session. */
    val hardFloorBytes: Long = 500L * MB,
    val onBudgetExhausted: BudgetAction = BudgetAction.WARN_THEN_STOP,
) {
    fun reserveBytes(totalBytes: Long): Long = totalBytes * reservePercent / 100
}

@Serializable
data class RetentionSettings(
    val policies: List<RetentionPolicy> = DEFAULT_POLICIES,
    val storage: StorageSettings = StorageSettings(),
) {
    fun policy(mode: Mode): RetentionPolicy =
        policies.firstOrNull { it.mode == mode } ?: DEFAULT_POLICIES.first { it.mode == mode }

    fun withPolicy(policy: RetentionPolicy): RetentionSettings =
        copy(policies = policies.filterNot { it.mode == policy.mode } + policy)

    companion object {
        val DEFAULT_POLICIES = listOf(
            RetentionPolicy(
                mode = Mode.DASHCAM,
                maxAgeMinutes = 3 * 24 * 60,
                segmentLengthSeconds = 180,
                maxBytes = 32L * GB,
                eventBeforeSeconds = 30,
                eventAfterSeconds = 30,
            ),
            RetentionPolicy(
                mode = Mode.SPORTS,
                maxAgeMinutes = 0,
                segmentLengthSeconds = 300,
                maxBytes = 0,
                eventBeforeSeconds = 20,
                eventAfterSeconds = 10,
            ),
            RetentionPolicy(
                mode = Mode.CAMERA,
                maxAgeMinutes = 0,
                segmentLengthSeconds = 600,
                maxBytes = 0,
                eventBeforeSeconds = 10,
                eventAfterSeconds = 10,
            ),
        )
    }
}

const val MB: Long = 1024L * 1024
const val GB: Long = 1024L * MB

/** The plain-language summary shown under the policy editor. */
fun RetentionPolicy.describe(storage: StorageSettings): String {
    val keep = if (maxAgeMinutes == 0L) "Keep unprotected recordings until space is needed"
    else "Keep unprotected recordings for ${formatDuration(maxAgeMinutes)}"
    val split = "split footage into ${formatDuration(segmentLengthSeconds / 60L)} files"
    val reserve = "keep ${storage.reservePercent}% of storage free"
    return "$keep, $split, and $reserve. Protected recordings are never deleted automatically."
}

fun formatDuration(minutes: Long): String = when {
    minutes <= 0 -> "under a minute"
    minutes % (24 * 60) == 0L -> plural(minutes / (24 * 60), "day")
    minutes % 60 == 0L -> plural(minutes / 60, "hour")
    else -> plural(minutes, "minute")
}

private fun plural(n: Long, unit: String) = if (n == 1L) "1 $unit" else "$n ${unit}s"
