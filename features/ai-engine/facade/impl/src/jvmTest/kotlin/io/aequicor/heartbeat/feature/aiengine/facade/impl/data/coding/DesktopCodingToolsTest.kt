package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.coding

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolPermissions
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspace
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.toolCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.impl.data.DefaultAgentTools
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopCodingToolsTest {
    private val directory = Files.createTempDirectory("coding-approval").toRealPath()
    private val workspace = WorkspaceRef("project")

    @AfterTest
    fun cleanUp() {
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `a command with a hidden executable suffix is refused before asking or executing`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dispatchers = object : DispatcherProvider {
            override val main = dispatcher
            override val default = dispatcher
            override val io = dispatcher
        }
        val projects = object : LocalWorkspaces {
            override val isAvailable = true
            override fun observe() = flowOf(listOf(LocalWorkspace(workspace, "Project")))
            override suspend fun register(directory: String) = error("Registration is not part of tool execution")
            override suspend fun resolve(ref: WorkspaceRef): String? = directory.toString().takeIf { ref == workspace }
        }
        val contribution = DesktopCodingTools(projects, dispatchers)
        assertEquals(contribution.specifications(workspace).toolCatalog().toSet(), contribution.catalog.toSet())
        val tools = DefaultAgentTools(setOf(contribution))
        val suffix = if (System.getProperty("os.name").startsWith("Windows")) {
            "; Set-Content hidden-action.txt changed"
        } else {
            "; touch hidden-action.txt"
        }
        val arguments = buildJsonObject { put("command", "echo safe" + " ".repeat(MAX_CODING_COMMAND_CHARS) + suffix) }
        for (trust in TrustLevel.entries) {
            var approvals = 0
            val context = AgentToolContext(
                SessionRef(EngineId("test"), SessionSourceId("test"), "session"),
                workspace,
                TurnId(trust.name),
                trust = trust,
                permissions = AgentToolPermissions {
                    approvals++
                    true
                },
            )
            assertTrue(tools.execute(context, "run_command", arguments).isError)
            assertEquals(0, approvals)
            assertFalse(Files.exists(directory.resolve("hidden-action.txt")))
        }
        val spec = contribution.specifications(workspace).single { it.name == "run_command" }
        val shortCommand = buildJsonObject { put("command", "git status --short") }
        assertEquals("git status --short", contribution.approval(spec, shortCommand).description)
    }
}
