package io.aequicor.heartbeat.feature.aiengine.facade.api

import kotlin.test.Test
import kotlin.test.assertEquals

class HostedToolNameTest {
    @Test
    fun `both Heartbeat MCP prefixes are dropped`() {
        assertEquals("plan_read", hostedToolName("mcp__heartbeat_tools__plan_read"))
        assertEquals("web_search", hostedToolName("mcp__heartbeat_search__web_search"))
    }

    @Test
    fun `native names and tools of other MCP servers stay as they are`() {
        assertEquals("plan_read", hostedToolName("plan_read"))
        assertEquals("mcp__github__create_issue", hostedToolName("mcp__github__create_issue"))
        assertEquals("mcp__heartbeat_toolsx__run", hostedToolName("mcp__heartbeat_toolsx__run"))
        assertEquals("heartbeat_search__web_search", hostedToolName("heartbeat_search__web_search"))
    }
}
