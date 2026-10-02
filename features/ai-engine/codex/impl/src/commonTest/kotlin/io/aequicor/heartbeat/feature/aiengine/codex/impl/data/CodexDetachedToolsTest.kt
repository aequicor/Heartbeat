package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodexDetachedToolsTest {
    private val workspace = WorkspaceRef("project")

    @Test
    fun `chat without a project declares hosted tools only when its caller opted in`() = runTest {
        val plain = Fixture(this, tools = ScopedTools(listOf("remember")))
        plain.open()
        val plainParams = plain.wire.written.single { it.text("method") == "thread/start" }.obj("params")
        assertFalse("remember" in plainParams.declaredTools())
        assertFalse("developerInstructions" in plainParams)
        plain.runtime.close()

        val tools = ScopedTools(listOf("remember"))
        val fixture = Fixture(this, tools = tools)
        fixture.runtime.create(CreateSessionRequest(fixture.target, areDetachedToolsEnabled = true))
        val params = fixture.wire.written.single { it.text("method") == "thread/start" }.obj("params")
        assertTrue("remember" in params.declaredTools())
        // Without a project the project-access preamble would promise workspace edits.
        assertEquals("guidance", params.text("developerInstructions"))
        assertNull(tools.scopes.last().workspace)
        assertEquals(fixture.target, tools.scopes.last().target)
        fixture.runtime.close()
    }

    @Test
    fun `resumed thread tolerates added and removed tools and keeps its own declarations`() = runTest {
        val manifests = MemoryCodexToolManifests()
        val initial = Fixture(this, tools = ScopedTools(listOf("run_command")), manifests = manifests)
        initial.workspacePaths[workspace] = "/project"
        val ref = initial.open(workspace).ref
        initial.runtime.close()

        val extended = ScopedTools(listOf("run_command", "remember"))
        val added = Fixture(this, tools = extended, manifests = manifests)
        added.workspacePaths[workspace] = "/project"
        added.runtime.attach(ref, ResumeSessionRequest(added.target, workspace))
        assertEquals(setOf("run_command"), extended.scopes.last().declared)
        added.runtime.close()

        val removed = Fixture(this, tools = ScopedTools(emptyList()), manifests = manifests)
        removed.workspacePaths[workspace] = "/project"
        removed.runtime.attach(ref, ResumeSessionRequest(removed.target, workspace))
        assertTrue(removed.wire.written.any { it.text("method") == "thread/resume" })
        removed.runtime.close()
    }

    @Test
    fun `manifest compatibility compares shared declarations, version and workspace`() {
        val base = manifest("project", "run_command" to "Run")
        assertTrue(isManifestCompatible(base, workspace, manifest("project", "run_command" to "Run", "x" to "X")))
        assertTrue(isManifestCompatible(base, workspace, null))
        assertFalse(isManifestCompatible(base, workspace, manifest("project", "run_command" to "Changed")))
        assertFalse(isManifestCompatible(base, WorkspaceRef("other"), base))
        assertFalse(isManifestCompatible(base, null, base))
        assertTrue(isManifestCompatible(manifest(null, "remember" to "Remember"), null, null))
        assertFalse(isManifestCompatible("not json", workspace, base))
        val unnamed = """{"version":$MANIFEST_VERSION,"workspace":"project","tools":[{"description":"x"}]}"""
        assertTrue(isManifestCompatible(unnamed, workspace, base))
    }

    private fun JsonObject.declaredTools(): List<String> = (this["dynamicTools"] as? JsonArray).orEmpty().map {
        it.jsonObject.getValue("name").jsonPrimitive.content
    }

    private fun manifest(workspace: String?, vararg tools: Pair<String, String>): String = json(
        "version" to JsonPrimitive(MANIFEST_VERSION),
        "workspace" to (workspace?.json() ?: JsonNull),
        "tools" to JsonArray(tools.map { (name, text) -> json("name" to name.json(), "description" to text.json()) }),
    ).toString()
}

private class ScopedTools(private val names: List<String>) : ProfileAgentTools {
    val scopes = mutableListOf<AgentToolScope>()
    override suspend fun specifications(workspace: WorkspaceRef?): List<AgentToolSpec> =
        names.map { AgentToolSpec(it, it, JsonObject(emptyMap())) }
    override suspend fun instructions(workspace: WorkspaceRef?): String = "guidance"
    override suspend fun instructions(scope: AgentToolScope): String {
        scopes += scope
        return "guidance"
    }
    override suspend fun execute(context: AgentToolContext, name: String, arguments: JsonObject) =
        AgentToolResult("done")
}
