package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ResolvedToolPolicy
import kotlin.test.Test
import kotlin.test.assertEquals

class PiToolGroupsTest {
    private val config = PiAgentConfig(tools = listOf("mcp__*", "codemode", "my_*", "read"))

    @Test
    fun `group names are policy entries and never reach the tools allowlist`() {
        val plan = PiLaunchPlan(
            ResolvedToolPolicy(nativeOn = setOf("read", "bash", "mcp", "codemode", "extension")),
            null,
            emptySet(),
        )

        assertEquals(setOf("read", "bash", "mcp__*", "codemode", "my_*"), plan.tools(config).split(",").toSet())
    }

    @Test
    fun `a group switched off by the policy drops its configured tools`() {
        val plan = PiLaunchPlan(ResolvedToolPolicy(nativeOn = setOf("read", "codemode")), null, emptySet())

        assertEquals(listOf("read", "codemode"), plan.tools(config).split(","))
    }

    @Test
    fun `without configuration the allowlist is the policy as before`() {
        val plan = PiLaunchPlan(ResolvedToolPolicy(nativeOn = setOf("read", "edit", "mcp")), null, emptySet())

        assertEquals(listOf("read", "edit"), plan.tools(PiAgentConfig()).split(","))
    }
}
