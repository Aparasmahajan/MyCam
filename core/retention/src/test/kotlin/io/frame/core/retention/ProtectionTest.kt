package io.frame.core.retention

import io.frame.core.model.Mode
import io.frame.core.model.RetentionSettings
import io.frame.core.model.StorageSettings
import io.frame.core.model.TimeWindow
import io.frame.core.model.describe
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ProtectionTest {

    @Test
    fun `overlapping and touching windows merge`() {
        val merged = Protection.merge(
            listOf(TimeWindow(50, 70), TimeWindow(0, 10), TimeWindow(10, 20), TimeWindow(15, 30)),
        )
        merged shouldBe listOf(TimeWindow(0, 30), TimeWindow(50, 70))
    }

    @Test
    fun `a 30s-30s window protects every segment it touches, including the one being written`() {
        val policy = RetentionSettings().policy(Mode.DASHCAM)
        val event = 1_000_000L
        val window = policy.eventWindow(event)

        // 3-minute segments around the event
        Protection.covers(window, event - 400_000, event - 220_000) shouldBe false
        Protection.covers(window, event - 220_000, event - 40_000) shouldBe false
        Protection.covers(window, event - 40_000, event + 140_000) shouldBe true
        Protection.covers(window, event + 140_000, null) shouldBe false
        Protection.covers(window, event + 20_000, null) shouldBe true
    }

    @Test
    fun `segment size estimate includes headroom`() {
        // 12 Mbps video + 128 kbps audio for 3 minutes is about 273 MB
        SegmentSizeEstimator.bytes(12_000_000, 128_000, 180) shouldBe 286_524_000L
    }

    @Test
    fun `policy summary reads like the spec`() {
        val policy = RetentionSettings().policy(Mode.DASHCAM)
        policy.describe(StorageSettings(reservePercent = 10)) shouldBe
            "Keep unprotected recordings for 3 days, split footage into 3 minutes files, and keep 10% of " +
            "storage free. Protected recordings are never deleted automatically."
    }
}
