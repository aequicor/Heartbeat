package io.aequicor.heartbeat.feature.aiengine.facade.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class EngineUsageTest {
    @Test
    fun `context categories belong to one snapshot and cannot exceed total usage`() {
        val partial = ContextBreakdown(instructions = 6400, history = 42880)
        assertEquals(49280L, partial.allocatedTokens)
        assertNull(partial.tools)
        assertEquals(partial, ContextUsage(78080, 128000, breakdown = partial).breakdown)
        assertFailsWith<IllegalArgumentException> { ContextBreakdown(instructions = -1) }
        assertFailsWith<IllegalArgumentException> { ContextBreakdown(history = Long.MAX_VALUE, tools = 1) }
        assertFailsWith<IllegalArgumentException> { ContextUsage(1, 100, breakdown = partial) }
    }

    @Test
    fun `native tokens remain known without a context limit`() {
        val usage = ContextUsage(4096, null)
        assertEquals(4096L, usage.usedTokens)
        assertNull(usage.capacityTokens)
        assertEquals(0L, ContextUsage(0, null).usedTokens)
    }

    @Test
    fun `invalid usage and nonpositive declared limits remain rejected`() {
        assertFailsWith<IllegalArgumentException> { ContextUsage(-1, null) }
        assertFailsWith<IllegalArgumentException> { ContextUsage(10, 0) }
        assertFailsWith<IllegalArgumentException> { ContextUsage(10, -1) }
    }
}
