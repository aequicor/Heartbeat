package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClaudeToolNamesTest {
    @Test
    fun `search tools are named under their own server next to the host tools`() {
        val names = claudeToolNames(isHosted = true, isSearch = true)
        assertTrue("mcp__heartbeat_tools__<name>" in names)
        assertTrue("mcp__heartbeat_search__web_fetch" in names)
        assertTrue("never mcp__heartbeat_tools__web_fetch" in names)
    }

    @Test
    fun `only the servers a session has are named`() {
        assertFalse("heartbeat_search" in claudeToolNames(isHosted = true, isSearch = false))
        assertFalse("<name>" in claudeToolNames(isHosted = false, isSearch = true))
        assertEquals("", claudeToolNames(isHosted = false, isSearch = false))
    }
}
