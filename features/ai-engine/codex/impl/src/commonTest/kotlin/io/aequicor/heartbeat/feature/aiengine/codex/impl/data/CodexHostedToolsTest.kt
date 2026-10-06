@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolImage
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.CancelsTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOptionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestsPermissions
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResolvedResource
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceResolver
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CodexHostedToolsTest {
    @Test
    fun `hosted image reaches the dynamic tool response alongside text`() = runTest {
        val image = AgentToolImage("image/png", "AQID")
        val tools = object : ProfileAgentTools by HostedFixture() {
            override suspend fun execute(context: AgentToolContext, name: String, arguments: JsonObject) =
                AgentToolResult("geometry", images = listOf(image))
        }
        val fixture = Fixture(this, tools = tools)
        fixture.workspacePaths[workspace] = "/project"
        val session = fixture.open(workspace)
        session.feature(SendsPrompts).send(Prompt)
        fixture.callHosted()
        runCurrent()
        val result = fixture.wire.written.last { it["id"] == JsonPrimitive(80) }.obj("result")
        val content = result.getValue("contentItems") as JsonArray
        assertEquals("geometry", (content[0] as JsonObject).text("text"))
        assertEquals("inputImage", (content[1] as JsonObject).text("type"))
        assertEquals(image.dataUrl, (content[1] as JsonObject).text("imageUrl"))
        fixture.runtime.close()
    }

    private val workspace = WorkspaceRef("hosted-project")

    @Test
    fun `legacy workspace resume stays read only when no hosted manifest was registered`() = runTest {
        val tools = HostedFixture()
        val fixture = Fixture(this, tools = tools)
        fixture.workspacePaths[workspace] = "/project"
        val session = fixture.runtime.attach(fixture.storedRef(), ResumeSessionRequest(fixture.target, workspace))
        val params = fixture.wire.written.single { it.text("method") == "thread/resume" }.obj("params")
        assertFalse("developerInstructions" in params)
        session.feature(SendsPrompts).send(Prompt)
        fixture.callHosted()
        runCurrent()
        assertEquals(0, tools.executions)
        assertIs<ActiveSessionState.Running>(session.state.value)
        fixture.runtime.close()
    }

    @Test
    fun `a lost manifest of a new hosted session fails closed`() = runTest {
        val broken = object : CodexToolManifests {
            override suspend fun get(id: String): String? = null
            override suspend fun isRequired(id: String): Boolean = true
            override suspend fun save(id: String, manifest: String) = Unit
        }
        val fixture = Fixture(this, tools = HostedFixture(), manifests = broken)
        fixture.workspacePaths[workspace] = "/project"
        val error = assertFailsWith<EngineException> {
            fixture.runtime.attach(fixture.storedRef(), ResumeSessionRequest(fixture.target, workspace))
        }
        assertEquals("session.${SessionFailureReason.NotResumable}", error.failure.code)
        assertFalse(fixture.wire.written.any { it.text("method") == "thread/resume" })
        fixture.runtime.close()
    }

    @Test
    fun `hosted coding accepts a document with search off and waits for matching permission`() = runTest {
        val tools = HostedFixture()
        val fixture = Fixture(this, searchTools = false, tools = tools)
        fixture.isSearchEnabled = false
        fixture.workspacePaths[workspace] = "/project"
        val session = fixture.open(workspace)
        val start = fixture.wire.written.single { it.text("method") == "thread/start" }.obj("params")
        assertEquals(
            listOf("run_command"),
            (checkNotNull(start["dynamicTools"]) as JsonArray).map { (it as JsonObject).text("name") },
        )
        fixture.resources = ResourceResolver {
            ResolvedResource("source.md", "text/markdown", "Attached source".encodeToByteArray())
        }
        val original = ContentPart.Resource(ResourceRef("attachment:source", "text/markdown"))
        val prompt = Prompt.copy(parts = Prompt.parts + original, trust = TrustLevel.AutoEdits)
        val turn = session.feature(SendsPrompts).send(prompt)
        val native = fixture.wire.written.single { it.text("method") == "turn/start" }.obj("params")
        assertEquals("never", native.text("approvalPolicy"))
        assertEquals("readOnly", native.obj("sandboxPolicy").text("type"))
        fixture.event(
            "item/completed",
            "turnId" to "native-turn".json(),
            "item" to json(
                "id" to "user-input".json(),
                "type" to "userMessage".json(),
                "content" to native.getValue("input"),
            ),
        )
        runCurrent()
        val history = session.feature(SessionHistory).page()
        val user = history.items.filterIsInstance<SessionItem.Message>().single { it.role == MessageRole.User }
        assertEquals(prompt.parts, user.parts)
        fixture.callHosted()
        runCurrent()
        val request = assertIs<ActiveSessionState.AwaitingUserAction>(session.state.value).requests.single()
        assertEquals(0, tools.executions)
        assertEquals(workspace, tools.context?.workspace)
        assertEquals(turn, tools.context?.turn)
        assertEquals(Prompt.id, tools.context?.request)
        assertEquals(TrustLevel.AutoEdits, tools.context?.trust)
        session.feature(
            RequestsPermissions,
        ).respond(PermissionDecision(turn, request.id, PermissionOptionId("hosted.allow")))
        runCurrent()
        assertEquals(1, tools.executions)
        assertIs<ActiveSessionState.Running>(session.state.value)
        assertTrue(
            fixture.wire.written.last { it["id"] == JsonPrimitive(80) }.obj("result")["success"]?.toString() == "true",
        )
        assertFailsWith<EngineException> {
            session.feature(
                RequestsPermissions,
            ).respond(PermissionDecision(turn, request.id, PermissionOptionId("hosted.allow")))
        }
        fixture.runtime.close()
    }

    @Test
    fun `cancellation revokes a hosted permission without executing the command`() = runTest {
        val tools = HostedFixture()
        val fixture = Fixture(this, tools = tools)
        fixture.workspacePaths[workspace] = "/project"
        val session = fixture.open(workspace)
        val turn = session.feature(SendsPrompts).send(Prompt)
        fixture.callHosted()
        runCurrent()
        assertIs<ActiveSessionState.AwaitingUserAction>(session.state.value)
        session.feature(CancelsTurns).cancel(turn)
        runCurrent()
        assertEquals(0, tools.executions)
        assertFalse(checkNotNull(tools.context?.lifetime).isActive)
        fixture.runtime.close()
    }

    @Test
    fun `resume restores the same declarations and rejects schema changes`() = runTest {
        val manifests = MemoryCodexToolManifests()
        val initial = Fixture(this, tools = HostedFixture(), manifests = manifests)
        initial.workspacePaths[workspace] = "/project"
        val ref = initial.open(workspace).ref
        initial.runtime.close()
        val resumed = Fixture(this, tools = HostedFixture(), manifests = manifests)
        resumed.workspacePaths[workspace] = "/project"
        resumed.runtime.attach(ref, ResumeSessionRequest(resumed.target, workspace))
        assertFalse(
            "dynamicTools" in resumed.wire.written.single { it.text("method") == "thread/resume" }.obj("params"),
        )
        resumed.runtime.close()
        val incompatible = Fixture(this, tools = HostedFixture("changed"), manifests = manifests)
        incompatible.workspacePaths[workspace] = "/project"
        val failure = assertFailsWith<EngineException> {
            incompatible.runtime.attach(ref, ResumeSessionRequest(incompatible.target, workspace))
        }
        assertEquals("session.${SessionFailureReason.NotResumable}", failure.failure.code)
        assertFalse(incompatible.wire.written.any { it.text("method") == "thread/resume" })
        incompatible.runtime.close()
    }

    private suspend fun Fixture.callHosted() = event(
        "item/tool/call",
        "turnId" to "native-turn".json(),
        "tool" to "run_command".json(),
        "arguments" to JsonObject(emptyMap()),
        id = JsonPrimitive(80),
    )

    private fun Fixture.storedRef() = SessionRef(target.engine, environment.config.historySource, "thread")
}

private class HostedFixture(description: String = "Run command") : ProfileAgentTools {
    private val spec = AgentToolSpec("run_command", description, json("type" to "object".json()))
    var executions = 0
    var context: AgentToolContext? = null
    override suspend fun specifications(workspace: WorkspaceRef?): List<AgentToolSpec> = listOf(spec)
    override suspend fun instructions(workspace: WorkspaceRef?): String = "Use hosted commands"
    override suspend fun execute(context: AgentToolContext, name: String, arguments: JsonObject): AgentToolResult {
        this.context = context
        if (!context.permissions.request(AgentToolApproval(name, "Run command"))) return AgentToolResult("Denied", true)
        executions++
        return AgentToolResult("done")
    }
}
