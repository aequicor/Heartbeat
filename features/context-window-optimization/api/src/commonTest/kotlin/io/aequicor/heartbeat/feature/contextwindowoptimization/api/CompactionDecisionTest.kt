package io.aequicor.heartbeat.feature.contextwindowoptimization.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContextUsage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompactionDecisionTest {
    private val settings = ContextOptimizationSettings(thresholdPercent = 80)

    @Test
    fun `unknown usage changes nothing`() {
        val mark = CompactionMark(attempts = 1)
        assertEquals(CompactionDecision(null, mark), settings.decideCompaction(mark, null, Both))
    }

    @Test
    fun `below the threshold nothing is pending and low usage resets the attempts`() {
        val mark = CompactionMark(attempts = 1, isExhausted = true)
        assertEquals(CompactionDecision(null, mark), settings.decideCompaction(mark, usage(75), Both))
        assertEquals(CompactionDecision(null, CompactionMark()), settings.decideCompaction(mark, usage(60), Both))
    }

    @Test
    fun `auto strategy tries native, then rollover, then stops`() {
        val first = settings.decideCompaction(CompactionMark(), usage(90), Both)
        assertEquals(CompactionMethod.Native, first.pending?.method)
        val second = settings.decideCompaction(first.mark, usage(90), Both)
        assertEquals(CompactionMethod.Rollover, second.pending?.method)
        val third = settings.decideCompaction(second.mark, usage(90), Both)
        assertNull(third.pending)
        assertTrue(third.mark.isExhausted)
        assertNull(settings.decideCompaction(third.mark, usage(95), Both).pending)
    }

    @Test
    fun `unsupported methods are skipped`() {
        val nativeOnly = CompactionSupport(isNative = false, isRollover = true)
        val rollover = settings.decideCompaction(CompactionMark(), usage(90), nativeOnly)
        assertEquals(CompactionMethod.Rollover, rollover.pending?.method)
        val none = CompactionSupport(isNative = false, isRollover = false)
        assertTrue(settings.decideCompaction(CompactionMark(), usage(90), none).mark.isExhausted)
    }

    @Test
    fun `disabled automatic compaction never makes one pending`() {
        val off = settings.copy(isAutoCompactionEnabled = false)
        assertNull(off.decideCompaction(CompactionMark(), usage(99), Both).pending)
    }

    private companion object {
        val Both = CompactionSupport(isNative = true, isRollover = true)

        fun usage(percent: Int) = ContextUsage(percent.toLong(), 100)
    }
}
