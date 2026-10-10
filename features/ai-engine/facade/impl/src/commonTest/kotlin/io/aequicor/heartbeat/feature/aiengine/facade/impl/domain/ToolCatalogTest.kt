package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContribution
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCatalogEntry
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolGroup
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.impl.data.DefaultAgentTools
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

internal class ToolCatalogTest {
    @Test
    fun `catalog merges groups and includes disabled tools without querying workspace availability`() {
        val tools = DefaultAgentTools(setOf(owner("b"), owner("a")))
        val expected = listOf(
            ToolCatalogEntry("a", AgentToolAction.Edit),
            ToolCatalogEntry("b", AgentToolAction.Edit),
        )
        assertEquals(listOf(ToolGroup("test", "Test", expected)), tools.catalog())
    }

    @Test
    fun `duplicate catalog names are rejected even when no declarations are available`() {
        assertFailsWith<IllegalStateException> { DefaultAgentTools(setOf(owner("a"), owner("a"))).catalog() }
    }

    private fun owner(name: String): AgentToolContribution = object : AgentToolContribution {
        override val group = "test"
        override val title = "Test"
        override val catalog = listOf(ToolCatalogEntry(name, AgentToolAction.Edit))
        override suspend fun specifications(workspace: WorkspaceRef?): List<AgentToolSpec> =
            error("Catalog must not resolve availability")
        override suspend fun execute(context: AgentToolContext, name: String, arguments: JsonObject): AgentToolResult =
            error("Catalog must not execute tools")
    }
}
