package io.aequicor.heartbeat.feature.contextwindowoptimization.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class ContextLedgerTest {
    private val at = Instant.fromEpochSeconds(1)

    @Test
    fun `checkpoints append progress and replace the goal and plan`() {
        val first = null.updated(LedgerUpdate(goal = "Fix issues", done = listOf("#1"), next = listOf("#2", "#3")), at)
        val second = first.updated(LedgerUpdate(done = listOf("#2", "#1"), facts = listOf("CI is flaky")), at)

        assertEquals("Fix issues", second.goal)
        assertEquals(listOf("#1", "#2"), second.done)
        assertEquals(listOf("#2", "#3"), second.next)
        assertEquals(listOf("CI is flaky"), second.facts)
        assertEquals(1, second.version)
    }

    @Test
    fun `entries are trimmed and blank ones dropped`() {
        val ledger = null.updated(LedgerUpdate(done = listOf("  ", "a\nb", "x".repeat(1_000))), at)

        assertEquals(listOf("a b", "x".repeat(LEDGER_ENTRY_CHARS)), ledger.done)
    }

    @Test
    fun `the oldest done items are dropped to fit the budget`() {
        var ledger = null.updated(LedgerUpdate(goal = "Loop"), at)
        repeat(40) { ledger = ledger.updated(LedgerUpdate(done = listOf("issue $it " + "y".repeat(100))), at) }

        assertTrue(ledger.render().length <= LEDGER_MAX_CHARS)
        assertTrue(ledger.done.last().startsWith("issue 39"))
        assertFalse(ledger.done.any { it.startsWith("issue 0 ") })
    }

    @Test
    fun `ledger text never reaches toString`() {
        val ledger = null.updated(LedgerUpdate(goal = "secret goal"), at)

        assertFalse("secret" in ledger.toString())
    }
}
