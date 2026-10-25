package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.coding

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActionAnalysis
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActionEffect
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolPermissions
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspace
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.VirtualFileChange
import io.aequicor.heartbeat.feature.aiengine.facade.api.VirtualWorkspace
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.toolCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.impl.data.DefaultAgentTools
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DesktopCodingToolsTest {
    private val directory = Files.createTempDirectory("coding-approval").toRealPath()
    private val workspace = WorkspaceRef("project")

    @AfterTest
    fun cleanUp() {
        directory.toFile().deleteRecursively()
    }

    private fun TestScope.contribution(): DesktopCodingTools {
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
        return DesktopCodingTools(projects, dispatchers)
    }

    private fun TestScope.virtualContribution(sandbox: RevisionedWorkspace): DesktopCodingTools {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dispatchers = object : DispatcherProvider {
            override val main = dispatcher
            override val default = dispatcher
            override val io = dispatcher
        }
        val projects = object : LocalWorkspaces {
            override val isAvailable = true
            override fun observe() = flowOf(emptyList<LocalWorkspace>())
            override suspend fun register(directory: String) = error("Registration is not part of tool execution")
            override suspend fun resolve(ref: WorkspaceRef): String? = null
            override suspend fun virtual(ref: WorkspaceRef): VirtualWorkspace? = sandbox.takeIf { ref == sandbox.ref }
        }
        return DesktopCodingTools(projects, dispatchers)
    }

    @Test
    fun `virtual reads are known reads bound to the revision and arguments`() = runTest {
        val sandbox = RevisionedWorkspace()
        val contribution = virtualContribution(sandbox)
        val context = AgentToolContext(
            SessionRef(EngineId("test"), SessionSourceId("test"), "session"),
            sandbox.ref,
            TurnId("turn"),
        )
        val specs = contribution.specifications(sandbox.ref).associateBy { it.name }
        val read = buildJsonObject { put("path", "a.txt") }
        for (name in listOf("read_file", "list_dir", "glob", "grep")) {
            val known = assertIs<ActionAnalysis.Known>(contribution.analyze(context, specs.getValue(name), read))
            assertEquals(setOf(ActionEffect.Read), known.evidence.effects)
        }
        val spec = specs.getValue("read_file")
        val first = assertIs<ActionAnalysis.Known>(contribution.analyze(context, spec, read)).evidence.binding
        val other = buildJsonObject { put("path", "b.txt") }
        val otherPath = assertIs<ActionAnalysis.Known>(contribution.analyze(context, spec, other)).evidence.binding
        sandbox.revision = 2
        val later = assertIs<ActionAnalysis.Known>(contribution.analyze(context, spec, read)).evidence.binding
        assertNotEquals(first, otherPath)
        assertNotEquals(first, later)

        val edit = buildJsonObject {
            put("path", "a.txt")
            put("content", "changed")
        }
        for (name in listOf("write_file", "edit_file", "delete_file", "move_file")) {
            assertIs<ActionAnalysis.Unknown>(contribution.analyze(context, specs.getValue(name), edit))
        }
        val command = buildJsonObject { put("command", "git status") }
        val shell = AgentToolSpec("run_command", "", buildJsonObject {})
        assertIs<ActionAnalysis.Unknown>(contribution.analyze(context, shell, command))
    }

    private class RevisionedWorkspace : VirtualWorkspace {
        var revision = 1L
        override val ref = WorkspaceRef("sandbox")
        override val processDirectory = "unused"
        override suspend fun revision(): Long = revision
        override suspend fun paths(): List<String> = listOf("a.txt", "b.txt")
        override suspend fun read(path: String): String = "content"
        override suspend fun change(expectedRevision: Long, changes: List<VirtualFileChange>): Long =
            error("Analysis never changes the sandbox")

        override suspend fun move(expectedRevision: Long, path: String, destination: String): Long =
            error("Analysis never changes the sandbox")
    }

    @Test
    fun `instructions drop only the guidance of tools the session does not declare`() = runTest {
        val contribution = contribution()
        val names = contribution.specifications(workspace).map { it.name }.toSet()
        assertTrue("run_command" in contribution.instructions(AgentToolScope(workspace)))
        val withoutShell = contribution.instructions(AgentToolScope(workspace, declared = names - "run_command"))
        assertTrue("Read AGENTS.md and CLAUDE.md" in withoutShell)
        assertFalse("run_command" in withoutShell)
        assertEquals("", contribution.instructions(AgentToolScope(workspace, declared = emptySet())))
    }

    @Test
    fun `long commands are shown in full and an explicit decline prevents execution`() = runTest {
        val contribution = contribution()
        assertEquals(contribution.specifications(workspace).toolCatalog().toSet(), contribution.catalog.toSet())
        val tools = DefaultAgentTools(setOf(contribution))
        val suffix = if (System.getProperty("os.name").startsWith("Windows")) {
            "; Set-Content hidden-action.txt changed"
        } else {
            "; touch hidden-action.txt"
        }
        val arguments = buildJsonObject { put("command", "echo safe" + " ".repeat(8_000) + suffix) }
        for (trust in TrustLevel.entries) {
            var approvals = 0
            val context = AgentToolContext(
                SessionRef(EngineId("test"), SessionSourceId("test"), "session"),
                workspace,
                TurnId(trust.name),
                trust = trust,
                permissions = AgentToolPermissions {
                    approvals++
                    assertTrue(it.description.orEmpty().contains(suffix))
                    false
                },
            )
            assertTrue(tools.execute(context, "run_command", arguments).isError)
            assertEquals(1, approvals)
            assertFalse(Files.exists(directory.resolve("hidden-action.txt")))
        }
        val spec = contribution.specifications(workspace).single { it.name == "run_command" }
        val shortCommand = buildJsonObject { put("command", "git status --short") }
        assertEquals("git status --short", contribution.approval(spec, shortCommand).description)
    }
}
