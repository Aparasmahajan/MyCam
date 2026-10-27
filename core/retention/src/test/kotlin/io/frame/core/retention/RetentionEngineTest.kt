package io.frame.core.retention

import io.frame.core.model.GB
import io.frame.core.model.Mode
import io.frame.core.model.RetentionPolicy
import io.frame.core.model.RetentionSettings
import io.frame.core.model.SegmentState
import io.frame.core.model.StorageSettings
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class RetentionEngineTest {

    private val dashcam3Days = RetentionSettings(
        policies = listOf(
            RetentionPolicy(Mode.DASHCAM, maxAgeMinutes = 3 * 24 * 60, segmentLengthSeconds = 180,
                maxBytes = 0, eventBeforeSeconds = 30, eventAfterSeconds = 30),
        ),
        storage = StorageSettings(reservePercent = 10, hardFloorBytes = 50),
    )

    private fun engine(world: FakeWorld, settings: RetentionSettings = dashcam3Days) =
        RetentionEngine(world, world, world, world) { settings }

    private val fourDays = 4L * 24 * 60

    @Test
    fun `expired segments are deleted but protected ones are kept`() = runTest {
        val world = FakeWorld(totalBytes = 100_000, freeBytes = 100_000)
        repeat(70) { world.add("new$it", ageMinutes = 60) }
        repeat(30) { world.add("old$it", ageMinutes = fourDays + it, protected = it < 5) }

        val result = engine(world).runCleanup(CleanupTrigger.PERIODIC)

        result.deleted.size shouldBe 25
        result.deleted.map { it.reason }.toSet() shouldBe setOf(DeleteReason.EXPIRED)
        (0 until 5).forEach { world.seg("old$it").state shouldBe SegmentState.COMPLETE }
        world.segs.count { it.id.startsWith("new") && it.state == SegmentState.DELETED } shouldBe 0
    }

    @Test
    fun `the segment being written is never deleted, even when space is short`() = runTest {
        val world = FakeWorld(totalBytes = 1_000, freeBytes = 1_000)
        world.add("active", ageMinutes = fourDays * 2, size = 950, state = SegmentState.WRITING)

        val result = engine(world).runCleanup(CleanupTrigger.LOW_SPACE)

        result.deleted.shouldBeEmpty()
        world.seg("active").state shouldBe SegmentState.WRITING
        result.budgetMet shouldBe false
    }

    @Test
    fun `low space deletes oldest eligible first until the reserve is met`() = runTest {
        val world = FakeWorld(totalBytes = 1_000, freeBytes = 1_000)
        world.add("a", ageMinutes = 30, size = 300)
        world.add("b", ageMinutes = 20, size = 300)
        world.add("c", ageMinutes = 10, size = 300)
        // free = 100, reserve = 100, next segment = 150 -> need 250 free

        val result = engine(world).runCleanup(CleanupTrigger.SEGMENT_DONE, nextSegmentBytes = 150)

        result.deleted.map { it.candidate.id } shouldBe listOf("a")
        result.budgetMet shouldBe true
        world.freeBytes shouldBe 400
    }

    @Test
    fun `only protected footage left means warn and, below the hard floor, stop - never delete it`() = runTest {
        val world = FakeWorld(totalBytes = 1_000, freeBytes = 1_000)
        world.add("p1", size = 480, protected = true)
        world.add("p2", size = 480, protected = true)

        val result = engine(world).runCleanup(CleanupTrigger.LOW_SPACE)

        result.deleted.shouldBeEmpty()
        result.budgetMet shouldBe false
        result.mustStop shouldBe true
        world.segs.all { it.state == SegmentState.COMPLETE } shouldBe true
    }

    @Test
    fun `a mode over its byte cap trims only that mode`() = runTest {
        val settings = RetentionSettings(
            policies = listOf(
                RetentionPolicy(Mode.DASHCAM, 0, 180, maxBytes = 250, eventBeforeSeconds = 30, eventAfterSeconds = 30),
                RetentionPolicy(Mode.SPORTS, 0, 300, maxBytes = 0, eventBeforeSeconds = 20, eventAfterSeconds = 10),
            ),
            storage = StorageSettings(reservePercent = 0, hardFloorBytes = 0),
        )
        val world = FakeWorld(totalBytes = 10_000, freeBytes = 10_000)
        world.add("d1", ageMinutes = 30)
        world.add("d2", ageMinutes = 20)
        world.add("d3", ageMinutes = 10)
        world.add("s1", mode = Mode.SPORTS, ageMinutes = 99)

        val result = engine(world, settings).runCleanup(CleanupTrigger.SEGMENT_DONE)

        result.deleted.map { it.candidate.id } shouldBe listOf("d1")
        result.deleted.single().reason shouldBe DeleteReason.MODE_BUDGET
        world.seg("s1").state shouldBe SegmentState.COMPLETE
    }

    @Test
    fun `a file that cannot be deleted is skipped instead of looping forever`() = runTest {
        val world = FakeWorld(totalBytes = 1_000, freeBytes = 1_000)
        world.add("stuck", ageMinutes = 30, size = 460)
        world.add("ok", ageMinutes = 20, size = 460)
        world.failingPaths += world.seg("stuck").path

        val result = engine(world).runCleanup(CleanupTrigger.LOW_SPACE)

        result.failed.map { it.candidate.id } shouldBe listOf("stuck")
        result.deleted.map { it.candidate.id } shouldBe listOf("ok")
        world.seg("stuck").state shouldBe SegmentState.COMPLETE
    }

    @Test
    fun `a segment protected after it was listed is not deleted`() = runTest {
        val world = FakeWorld(totalBytes = 100_000, freeBytes = 100_000)
        world.add("racy", ageMinutes = fourDays)
        world.protectOnBegin += "racy"

        val result = engine(world).runCleanup(CleanupTrigger.PERIODIC)

        result.deleted.shouldBeEmpty()
        world.seg("racy").state shouldBe SegmentState.COMPLETE
        world.deleteCalls shouldBe 0
    }

    @Test
    fun `concurrent cleanups never delete the same file twice`() = runTest {
        val world = FakeWorld(totalBytes = 100_000, freeBytes = 100_000)
        repeat(40) { world.add("old$it", ageMinutes = fourDays + it) }
        val engine = engine(world)

        val results = (1..4).map { async { engine.runCleanup(CleanupTrigger.SEGMENT_DONE) } }.awaitAll()

        results.sumOf { it.deleted.size } shouldBe 40
        world.deleteCalls shouldBe 40
    }

    @Test
    fun `segments with sports never-expire policy survive time-based cleanup`() = runTest {
        val settings = RetentionSettings(storage = StorageSettings(reservePercent = 0, hardFloorBytes = 0))
        val world = FakeWorld(totalBytes = 100L * GB, freeBytes = 100L * GB)
        world.add("match", mode = Mode.SPORTS, ageMinutes = 365L * 24 * 60)
        world.add("drive", mode = Mode.DASHCAM, ageMinutes = 365L * 24 * 60)

        val result = engine(world, settings).runCleanup(CleanupTrigger.PERIODIC)

        result.deleted.map { it.candidate.id } shouldContainExactlyInAnyOrder listOf("drive")
    }
}
