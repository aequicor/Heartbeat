package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.NoAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResolvedToolPolicy
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolPolicyScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodexHostedInstructionsTest {
    private val workspace = WorkspaceRef("project")

    @Test
    fun `new hosted session explains write access separately from native sandbox restrictions`() = runTest {
        val fixture = Fixture(this, tools = InstructionTools())
        fixture.workspacePaths[workspace] = "/project"
        fixture.open(workspace)
        val params = fixture.wire.written.single { it.text("method") == "thread/start" }.obj("params")
        assertHostedAccess(params)
        assertTrue(checkNotNull(params.text("developerInstructions")).contains("Project-specific workflow"))
        fixture.runtime.close()
    }

    @Test
    fun `resumed hosted session receives access guidance even without contribution instructions`() = runTest {
        val manifests = MemoryCodexToolManifests()
        val initial = Fixture(this, tools = InstructionTools(), manifests = manifests)
        initial.workspacePaths[workspace] = "/project"
        val ref = initial.open(workspace).ref
        initial.runtime.close()
        val resumed = Fixture(this, tools = InstructionTools(""), manifests = manifests)
        resumed.workspacePaths[workspace] = "/project"
        resumed.runtime.attach(ref, ResumeSessionRequest(resumed.target, workspace))
        val params = resumed.wire.written.single { it.text("method") == "thread/resume" }.obj("params")
        assertHostedAccess(params)
        assertFalse("dynamicTools" in params)
        resumed.runtime.close()
    }

    @Test
    fun `workspace without hosted declarations is not promised hosted write access`() = runTest {
        val fixture = Fixture(this)
        fixture.workspacePaths[workspace] = "/project"
        fixture.open(workspace)
        val params = fixture.wire.written.single { it.text("method") == "thread/start" }.obj("params")
        assertFalse("developerInstructions" in params)
        fixture.runtime.close()
    }

    @Test
    fun `read only declarations never promise hosted edits or commands`() = runTest {
        val tools = ScopedInstructionTools()
        val fixture = Fixture(this, tools = tools)
        fixture.workspacePaths[workspace] = "/project"
        fixture.open(workspace)
        val params = fixture.wire.written.single { it.text("method") == "thread/start" }.obj("params")
        val instructions = checkNotNull(params.text("developerInstructions"))
        assertTrue(instructions.contains("currently available hosted tools provide read access"))
        assertFalse(instructions.contains("hosted tools can edit"))
        assertFalse(instructions.contains("hosted tools can run"))
        assertEquals(fixture.target, tools.specScopes.single().target)
        assertEquals(setOf("read_file"), tools.instructionScopes.single().declared)
        fixture.runtime.close()
    }

    @Test
    fun `resume uses session scope and intersects instructions with frozen declarations`() = runTest {
        val manifests = MemoryCodexToolManifests()
        val initial = Fixture(this, tools = ScopedInstructionTools(), manifests = manifests)
        initial.workspacePaths[workspace] = "/project"
        val ref = initial.open(workspace).ref
        initial.runtime.close()
        val tools = ScopedInstructionTools().apply {
            specs = specs + AgentToolSpec("new_edit", "Edit", JsonObject(emptyMap()), AgentToolAction.Edit)
        }
        val resumed = Fixture(this, tools = tools, manifests = manifests)
        resumed.workspacePaths[workspace] = "/project"
        resumed.runtime.attach(ref, ResumeSessionRequest(resumed.target, workspace))
        val scope = tools.instructionScopes.single()
        assertEquals(ref, scope.session)
        assertEquals(ref, tools.specScopes.single().session)
        assertEquals(setOf("read_file"), scope.declared)
        val params = resumed.wire.written.single { it.text("method") == "thread/resume" }.obj("params")
        assertFalse("dynamicTools" in params)
        assertFalse(checkNotNull(params.text("developerInstructions")).contains("hosted tools can edit"))
        resumed.runtime.close()
    }

    @Test
    fun `resume explicitly clears hosted instructions after all frozen tools become unavailable`() = runTest {
        val manifests = MemoryCodexToolManifests()
        val initial = Fixture(this, tools = ScopedInstructionTools(), manifests = manifests)
        initial.workspacePaths[workspace] = "/project"
        val ref = initial.open(workspace).ref
        initial.runtime.close()
        val tools = ScopedInstructionTools().apply { specs = emptyList() }
        val resumed = Fixture(this, tools = tools, manifests = manifests)
        resumed.workspacePaths[workspace] = "/project"
        resumed.runtime.attach(ref, ResumeSessionRequest(resumed.target, workspace))
        val params = resumed.wire.written.single { it.text("method") == "thread/resume" }.obj("params")
        assertEquals("", params.text("developerInstructions"))
        assertEquals(emptySet(), tools.instructionScopes.single().declared)
        resumed.runtime.close()
    }

    @Test
    fun `hosted search off filters declarations without switching off native search`() = runTest {
        val tools = ScopedInstructionTools().apply { policy = ResolvedToolPolicy(hostedDenied = setOf("web_search")) }
        val fixture = Fixture(this, tools = tools)
        fixture.open()
        val params = fixture.wire.written.single { it.text("method") == "thread/start" }.obj("params")
        val names = (params["dynamicTools"] as JsonArray).map { (it as JsonObject).text("name") }
        assertEquals(listOf("web_fetch"), names)
        assertEquals("live", params.obj("config").text("web_search"))
        assertEquals(fixture.target, tools.policyScopes.single().target)
        fixture.runtime.close()
    }

    @Test
    fun `session specific instructions force cold resume even when native policy content is unchanged`() = runTest {
        val tools = ScopedInstructionTools().apply {
            guidance = { if (it.session == null) "Initial guidance" else "Session guidance" }
        }
        val fixture = Fixture(this, tools = tools)
        fixture.workspacePaths[workspace] = "/project"
        val session = fixture.open(workspace)
        session.feature(SendsPrompts).send(Prompt)
        assertEquals(2, fixture.wire.peers.size)
        val resume = fixture.wire.written.single { it.text("method") == "thread/resume" }.obj("params")
        assertTrue(checkNotNull(resume.text("developerInstructions")).contains("Session guidance"))
        assertFalse(checkNotNull(resume.text("developerInstructions")).contains("Initial guidance"))
        assertFalse("dynamicTools" in resume)
        assertEquals(setOf("read_file"), tools.instructionScopes.last().declared)
        fixture.runtime.close()
    }

    @Test
    fun `policy reload clears stale guidance without advertising newly added tools outside the manifest`() = runTest {
        val tools = ScopedInstructionTools().apply { guidance = { "Old guidance" } }
        val fixture = Fixture(this, tools = tools)
        fixture.workspacePaths[workspace] = "/project"
        val session = fixture.open(workspace)
        tools.specs = listOf(AgentToolSpec("new_edit", "Edit", JsonObject(emptyMap()), AgentToolAction.Edit))
        tools.guidance = { "" }
        tools.policy = ResolvedToolPolicy(hostedDenied = setOf("read_file"), generation = 2)
        session.feature(SendsPrompts).send(Prompt)
        val resume = fixture.wire.written.single { it.text("method") == "thread/resume" }.obj("params")
        assertEquals("", resume.text("developerInstructions"))
        assertFalse("dynamicTools" in resume)
        assertEquals(emptySet(), tools.instructionScopes.last().declared)
        fixture.runtime.close()
    }

    private fun assertHostedAccess(params: JsonObject) {
        val instructions = checkNotNull(params.text("developerInstructions"))
        assertTrue(instructions.contains("read-only sandbox applies only to Codex's built-in tools"))
        assertTrue(instructions.contains("hosted tools can edit workspace files and run commands"))
        assertTrue(instructions.contains("Heartbeat applies the user's current approval mode"))
        assertTrue(instructions.contains("Call the appropriate hosted tool"))
        assertEquals("read-only", params.text("sandbox"))
        assertEquals("never", params.text("approvalPolicy"))
    }
}

private class InstructionTools(private val guidance: String = "Project-specific workflow") :
    ProfileAgentTools by NoAgentTools {
    override suspend fun specifications(scope: AgentToolScope): List<AgentToolSpec> = listOf(
        AgentToolSpec("edit_file", "Edit a project file", JsonObject(emptyMap()), AgentToolAction.Edit),
        AgentToolSpec("run_command", "Run a project command", JsonObject(emptyMap()), AgentToolAction.Command),
    )
    override suspend fun instructions(workspace: WorkspaceRef?): String = guidance
    override suspend fun instructions(scope: AgentToolScope): String = guidance
}

private class ScopedInstructionTools : ProfileAgentTools by NoAgentTools {
    var specs = listOf(AgentToolSpec("read_file", "Read", JsonObject(emptyMap()), AgentToolAction.Read))
    var policy = ResolvedToolPolicy()
    var guidance: (AgentToolScope) -> String = { "" }
    val specScopes = mutableListOf<AgentToolScope>()
    val instructionScopes = mutableListOf<AgentToolScope>()
    val policyScopes = mutableListOf<ToolPolicyScope>()
    override suspend fun specifications(scope: AgentToolScope): List<AgentToolSpec> {
        specScopes += scope
        return specs
    }
    override suspend fun instructions(scope: AgentToolScope): String {
        instructionScopes += scope
        return guidance(scope)
    }
    override suspend fun nativeToolsForExecution(scope: ToolPolicyScope): ResolvedToolPolicy {
        policyScopes += scope
        return policy
    }
}
