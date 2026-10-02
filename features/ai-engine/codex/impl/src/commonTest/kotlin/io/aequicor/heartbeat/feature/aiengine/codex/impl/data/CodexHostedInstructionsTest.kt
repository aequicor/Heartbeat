package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.NoAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.coroutines.test.runTest
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
    override suspend fun specifications(workspace: WorkspaceRef?): List<AgentToolSpec> = listOf(
        AgentToolSpec("edit_file", "Edit a project file", JsonObject(emptyMap()), AgentToolAction.Edit),
        AgentToolSpec("run_command", "Run a project command", JsonObject(emptyMap()), AgentToolAction.Command),
    )
    override suspend fun instructions(workspace: WorkspaceRef?): String = guidance
}
