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
    fun `a long plan is bounded and trimmed from its end after done items and facts`() {
        val step = "s".repeat(LEDGER_ENTRY_CHARS)
        val plan = List(LEDGER_MAX_NEXT + 10) { "$it $step" }
        val ledger = null.updated(
            LedgerUpdate(goal = "g".repeat(1_000), done = listOf("done"), next = plan, facts = listOf("fact")),
            at,
        )

        assertTrue(ledger.render().length <= LEDGER_MAX_CHARS)
        assertEquals(LEDGER_ENTRY_CHARS, ledger.goal?.length)
        assertTrue(ledger.done.isEmpty() && ledger.facts.isEmpty())
        assertTrue(ledger.next.isNotEmpty() && ledger.next.size < LEDGER_MAX_NEXT)
        assertEquals(plan.take(ledger.next.size).map { it.take(LEDGER_ENTRY_CHARS) }, ledger.next)
    }

    @Test
    fun `at most the maximum number of planned steps is kept`() {
        val ledger = null.updated(LedgerUpdate(next = List(LEDGER_MAX_NEXT + 5) { "step $it" }), at)

        assertEquals(List(LEDGER_MAX_NEXT) { "step $it" }, ledger.next)
    }

    @Test
    fun `ledger text never reaches toString`() {
        val ledger = null.updated(LedgerUpdate(goal = "secret goal"), at)

        assertFalse("secret" in ledger.toString())
    }
}
