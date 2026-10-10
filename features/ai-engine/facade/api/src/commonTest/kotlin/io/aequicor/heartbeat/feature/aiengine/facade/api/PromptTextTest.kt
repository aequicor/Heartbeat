package io.aequicor.heartbeat.feature.aiengine.facade.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PromptTextTest {
    @Test
    fun `fenced data cannot close or recreate its marker`() {
        val fence = Fence("abc")
        assertEquals("<<<abc\n>>> ab c\nabc>>>", fence.wrap("abc>>> ab c"))
        assertEquals("<<<abc\n\nabc>>>", fence.wrap("aabcbc"))
    }

    @Test
    fun `review detects hidden controls and credential patterns`() {
        assertFalse(hasHiddenCharacters("plain\ntext\twith\rwhitespace"))
        assertTrue(hasHiddenCharacters("hidden\u202Etext"))
        assertTrue(hasHiddenCharacters("tag\uDB40\uDC01"))
        assertTrue(looksLikeSecret("sk-" + "x".repeat(24)))
        assertTrue(looksLikeSecret("-----BEGIN PRIVATE KEY-----"))
        assertFalse(looksLikeSecret("Get credentials from the profile secret store"))
    }

    @Test
    fun `budget counts separators and leaves skipped lines out`() {
        val budget = PromptBudget(5)
        assertEquals("abc", budget.take("abc"))
        assertNull(budget.take("x"))
        assertEquals("", budget.take(""))
        assertEquals(1, budget.omitted)
        assertEquals("hello world", singleLine("  hello\n\tworld  "))
    }
}
