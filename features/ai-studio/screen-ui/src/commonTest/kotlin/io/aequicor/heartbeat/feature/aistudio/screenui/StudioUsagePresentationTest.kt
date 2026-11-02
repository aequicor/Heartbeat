package io.aequicor.heartbeat.feature.aistudio.screenui

import io.aequicor.heartbeat.feature.aistudio.screenmodel.ProviderUsageUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.UsageCreditsUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.UsageWindowUi
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class StudioUsagePresentationTest {
    @Test
    fun `named weekly quota keeps its period chip without numeric duration`() {
        val weekly = UsageWindowUi("quota", "Weekly", 40, null)
        assertEquals(MINUTES_PER_WEEK, weekly.chipPeriodMinutes())
        assertEquals(300L, weekly.copy(windowDurationMinutes = 300).chipPeriodMinutes())
        assertNull(weekly.copy(title = "Unknown quota").chipPeriodMinutes())
    }

    @Test
    fun `nearest exhaustion takes precedence over a nearer reset`() {
        val soon = UsageWindowUi("five", "five_hour", 45, Instant.fromEpochSeconds(1), 300)
        val full = UsageWindowUi("week", "seven_day", 100, Instant.fromEpochSeconds(2), 10080)
        val usage = ProviderUsageUi(persistentListOf(soon, full), null, null, null, false)
        assertEquals(full, usage.indicatorWindow())
        assertEquals(full, usage.copy(windows = persistentListOf(full, soon)).indicatorWindow())
    }

    @Test
    fun `equal consumption chooses nearest known reset consistently`() {
        val later = UsageWindowUi("later", "later", 80, Instant.fromEpochSeconds(2))
        val first = later.copy(id = "first", resetsAt = Instant.fromEpochSeconds(1))
        val unknown = later.copy(id = "unknown", resetsAt = null)
        val usage = ProviderUsageUi(persistentListOf(unknown, later, first), null, null, null, true)
        assertEquals(first, usage.indicatorWindow())
        assertNull(usage.copy(windows = persistentListOf()).indicatorWindow())
    }

    @Test
    fun `credit consumption is the inverse of balance and never invents an allowance`() {
        val credits = UsageCreditsUi(160.0, 500.0, null, false, null)
        assertEquals(68, credits.consumedPercent())
        assertEquals(100, credits.copy(remaining = 0.0).consumedPercent())
        assertEquals(0, credits.copy(remaining = 600.0).consumedPercent())
        assertNull(credits.copy(total = null).consumedPercent())
        assertNull(credits.copy(total = 0.0).consumedPercent())
        assertNull(credits.copy(remaining = null).consumedPercent())
        assertNull(credits.copy(remaining = Double.NaN).consumedPercent())
        assertNull(credits.copy(isUnlimited = true).consumedPercent())
    }
}
