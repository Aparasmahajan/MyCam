package io.frame.core.retention

import io.frame.core.model.Mode
import io.frame.core.model.RetentionPolicy
import io.frame.core.model.RetentionSettings
import io.frame.core.model.SegmentState
import io.frame.core.model.StorageSettings
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Property-based checks of the three promises Smart Loop makes (test plan E3, E4, E7):
 * protected footage is never deleted, the active segment is never deleted, and the
 * reserve is met whenever there is anything eligible left to delete.
 */
class RetentionInvariantsTest {

    private data class SegSpec(
        val mode: Mode,
        val ageMinutes: Long,
        val size: Long,
        val protected: Boolean,
        val state: SegmentState,
    )

    private data class Scenario(
        val segs: List<SegSpec>,
        val totalBytes: Long,
        val extraFree: Long,
        val settings: RetentionSettings,
        val nextSegment: Long,
    )

    private val segArb = arbitrary {
        SegSpec(
            mode = Arb.element(Mode.entries).bind(),
            ageMinutes = Arb.long(0L..20_000L).bind(),
            size = Arb.long(1L..500L).bind(),
            protected = Arb.int(0..4).bind() == 0,
            state = Arb.element(
                SegmentState.COMPLETE, SegmentState.COMPLETE, SegmentState.COMPLETE,
                SegmentState.CORRUPT, SegmentState.DELETED, SegmentState.FINALIZING,
            ).bind(),
        )
    }

    private val scenarioArb = arbitrary {
        val segs = Arb.list(segArb, 0..60).bind()
        val used = segs.sumOf { it.size }
        val policies = Mode.entries.map { mode ->
            RetentionPolicy(
                mode = mode,
                maxAgeMinutes = Arb.element(0L, 12 * 60L, 24 * 60L, 3 * 24 * 60L, 7 * 24 * 60L).bind(),
                segmentLengthSeconds = 180,
                maxBytes = if (Arb.boolean().bind()) 0 else Arb.long(100L..5_000L).bind(),
                eventBeforeSeconds = 30,
                eventAfterSeconds = 30,
            )
        }
        Scenario(
            segs = segs,
            totalBytes = used + 2_000,
            extraFree = Arb.long(0L..2_000L).bind(),
            settings = RetentionSettings(
                policies = policies,
                storage = StorageSettings(reservePercent = Arb.element(5, 10, 15, 30).bind(), hardFloorBytes = 50),
            ),
            nextSegment = Arb.long(0L..300L).bind(),
        )
    }

    @Test
    fun `smart loop invariants hold for random recordings`() = runBlocking<Unit> {
        checkAll(2_000, scenarioArb) { sc ->
            val world = FakeWorld(totalBytes = sc.totalBytes, freeBytes = sc.extraFree + sc.segs.sumOf { it.size } + 200)
            sc.segs.forEachIndexed { i, s ->
                world.add("s$i", s.mode, s.ageMinutes, s.size, s.state, s.protected)
            }
            // exactly one active segment, always the newest and the biggest risk
            world.add("active", Mode.DASHCAM, ageMinutes = 0, size = 200, state = SegmentState.WRITING)
            val before = world.segs.associate { it.id to it.copy() }

            val result = RetentionEngine(world, world, world, world) { sc.settings }
                .runCleanup(CleanupTrigger.SEGMENT_DONE, sc.nextSegment)

            for (seg in world.segs) {
                val was = before.getValue(seg.id)
                if (was.protected || !was.state.isCleanupCandidate) {
                    seg.state shouldBe was.state // E3 + E4: never touched
                }
            }

            val eligibleLeft = world.segs.count { world.isEligible(it) }
            val reserve = sc.settings.storage.reserveBytes(sc.totalBytes)
            if (result.budgetMet) {
                (world.freeBytes >= reserve + sc.nextSegment) shouldBe true // E7
            } else {
                eligibleLeft shouldBe 0
            }

            for (policy in sc.settings.policies.filter { it.maxAgeMinutes > 0 }) {
                val cutoff = world.now - policy.maxAgeMillis
                world.segs.count { world.isEligible(it) && it.mode == policy.mode && it.endedAt!! < cutoff } shouldBe 0
            }
        }
    }
}
