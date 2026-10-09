package io.aequicor.heartbeat.feature.harness.impl.data.authoring

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineDescriptor
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFamily
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.HarnessNativeTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCatalogEntry
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolGroup
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolSwitch
import io.aequicor.heartbeat.feature.harness.api.ToolPolicySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ToolPolicyProblemTest {
    private val groups = listOf(
        ToolGroup("files", "Файлы", listOf(ToolCatalogEntry("read", AgentToolAction.Read))),
        ToolGroup("harness", "Харнессы", listOf(ToolCatalogEntry("harness_list", AgentToolAction.Read))),
    )
    private val codex = EngineDescriptor(
        EngineId("codex"),
        "Codex",
        EngineFamily.Vendor,
        emptySet(),
        HarnessNativeTools,
        nativeTools = listOf(NativeToolSpec("shell", AgentToolAction.Command, true, isGated = false)),
    )
    private val pi = EngineDescriptor(
        EngineId("pi"),
        "Pi",
        EngineFamily.BuiltIn,
        emptySet(),
        HarnessNativeTools,
        nativeTools = listOf(
            NativeToolSpec("read", AgentToolAction.Read, true, isGated = true),
            NativeToolSpec("grep", AgentToolAction.Read, false, isGated = true),
        ),
    )

    @Test
    fun `codex tools can only be turned off and gated tools can be enabled`() {
        assertNull(problem(native = mapOf("codex" to mapOf("shell" to ToolSwitch.Off))))
        val enabled = problem(native = mapOf("codex" to mapOf("shell" to ToolSwitch.On)))
        assertEquals("codex tool shell can only be turned off", enabled)
        assertNull(problem(native = mapOf("pi" to mapOf("grep" to ToolSwitch.On))))
    }

    @Test
    fun `unknown names, own harness tools and hosted collisions are refused`() {
        assertEquals("harness tools cannot be turned off by a harness", problem(hosted = setOf("harness_list")))
        assertEquals("unknown Heartbeat tools: nope; see harness_tools_catalog", problem(hosted = setOf("nope")))
        val engine = problem(native = mapOf("claude" to mapOf("Bash" to ToolSwitch.Off)))
        assertEquals("unknown or disabled engine claude", engine)
        val collision = problem(native = mapOf("pi" to mapOf("read" to ToolSwitch.Off)))
        assertEquals("read is also a Heartbeat tool; use hosted_off", collision)
    }

    private fun problem(hosted: Set<String> = emptySet(), native: Map<String, Map<String, ToolSwitch>> = emptyMap()) =
        toolPolicyProblem(ToolPolicySpec(hosted, native), groups, listOf(codex, pi))
}
