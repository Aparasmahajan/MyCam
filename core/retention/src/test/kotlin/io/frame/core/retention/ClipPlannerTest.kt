package io.frame.core.retention

import io.frame.core.model.TimeWindow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ClipPlannerTest {

    // Three 3-minute segments starting at t = 0, 180 s and 360 s (with a 0.5 s gap before the third).
    private val segs = listOf(
        SegmentSpan("s1", "/a.mp4", startedAt = 0, durationMs = 180_000),
        SegmentSpan("s2", "/b.mp4", startedAt = 180_000, durationMs = 180_000),
        SegmentSpan("s3", "/c.mp4", startedAt = 360_500, durationMs = 180_000),
    )

    @Test
    fun `a window inside one segment becomes one trimmed part`() {
        ClipPlanner.plan(segs, TimeWindow(30_000, 90_000)) shouldBe
            listOf(ClipPart("s1", "/a.mp4", 30_000, 90_000))
    }

    @Test
    fun `a window across a segment boundary is split into ordered parts`() {
        val parts = ClipPlanner.plan(segs.reversed(), TimeWindow(170_000, 200_000))
        parts shouldBe listOf(
            ClipPart("s1", "/a.mp4", 170_000, 180_000),
            ClipPart("s2", "/b.mp4", 0, 20_000),
        )
        parts.sumOf { it.lengthMs } shouldBe 30_000
    }

    @Test
    fun `the gap between segments is skipped`() {
        ClipPlanner.plan(segs, TimeWindow(359_000, 370_000)) shouldBe listOf(
            ClipPart("s2", "/b.mp4", 179_000, 180_000),
            ClipPart("s3", "/c.mp4", 0, 9_500),
        )
    }

    @Test
    fun `a window running past the end of the recording is clamped`() {
        ClipPlanner.plan(segs, TimeWindow(530_000, 600_000)) shouldBe
            listOf(ClipPart("s3", "/c.mp4", 169_500, 180_000))
    }

    @Test
    fun `slivers and windows outside the recording produce nothing`() {
        ClipPlanner.plan(segs, TimeWindow(179_950, 180_000)).shouldBeEmpty()
        ClipPlanner.plan(segs, TimeWindow(900_000, 960_000)).shouldBeEmpty()
    }

    @Test
    fun `a reel merges overlapping highlights so no footage repeats`() {
        val reel = ClipPlanner.planReel(
            segs,
            listOf(
                TimeWindow(400_000, 420_000), // third highlight, listed first
                TimeWindow(10_000, 40_000),
                TimeWindow(30_000, 60_000), // overlaps the one before
            ),
        )
        reel shouldBe listOf(
            ClipPart("s1", "/a.mp4", 10_000, 60_000),
            ClipPart("s3", "/c.mp4", 39_500, 59_500),
        )
        reel.sumOf { it.lengthMs } shouldBe 70_000
    }

    @Test
    fun `whole-file parts can be copied without re-encoding`() {
        val part = ClipPlanner.plan(segs, TimeWindow(-5_000, 185_000)).first()
        ClipPlanner.isWholeFile(part, segs[0]) shouldBe true
        ClipPlanner.isWholeFile(ClipPart("s1", "/a.mp4", 1_000, 180_000), segs[0]) shouldBe false
    }
}
