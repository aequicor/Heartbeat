package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.streaming.StreamFrame
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.CancelsTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOptionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestsPermissions
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallStatus
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class KoogCodingSessionTest {
    private val edit = RecordingTool("edit_file", isMutating = true)
    private val read = RecordingTool("read_file", isMutating = false)

    @Test
    fun `project session offers coding tools and instructions`() = runTest {
        val f = fixture()
        val session = f.codingSession()
        session.features.require(SendsPrompts).send(f.request())
        f.executor.complete()
        runCurrent()
        assertEquals(listOf("read_file", "edit_file"), f.executor.tools.single().map { it.name }.takeLast(2))
        val system = f.executor.prompts.single().messages.first()
        assertIs<Message.System>(system)
        assertEquals(INSTRUCTIONS, system.textContent())
    }

    @Test
    fun `session without project or with toggle off stays a plain chat`() = runTest {
        val f = fixture()
        f.isSearchEnabled = false
        f.session().features.require(SendsPrompts).send(f.request("plain"))
        f.executor.complete()
        runCurrent()
        f.isCodingEnabled = false
        f.codingSession().features.require(SendsPrompts).send(f.request("off"))
        f.executor.complete()
        runCurrent()
        assertEquals(listOf(emptyList(), emptyList<ToolDescriptor>()), f.executor.tools)
    }

    @Test
    fun `auto approve runs mutating tools without asking`() = runTest {
        val f = fixture()
        val session = f.codingSession()
        session.features.require(SendsPrompts).send(f.request())
        f.callTool("edit_file")
        runCurrent()
        f.executor.complete()
        runCurrent()
        assertEquals(1, edit.calls)
        assertEquals(TurnOutcome.Completed, assertIs<ActiveSessionState.Ready>(session.state.value).lastTurn?.outcome)
    }

    @Test
    fun `ask mode waits for the user and runs the tool once allowed`() = runTest {
        val f = fixture(autoApprove = false)
        val session = f.codingSession()
        session.features.require(SendsPrompts).send(f.request())
        f.callTool("read_file")
        f.callTool("edit_file")
        runCurrent()
        assertEquals(1, read.calls)
        val awaiting = assertIs<ActiveSessionState.AwaitingUserAction>(session.state.value)
        val request = awaiting.requests.single()
        assertEquals("edit target", request.title)
        assertEquals(0, edit.calls)
        assertEquals(ToolCallStatus.Pending, session.toolStatus("edit_file"))

        session.features.require(RequestsPermissions)
            .respond(PermissionDecision(request.turn, request.id, AllowOnce))
        runCurrent()
        val running = assertIs<ActiveSessionState.Running>(session.state.value)
        assertTrue(request.id in running.turn.resolvedPermissions)
        assertEquals(1, edit.calls)
        f.executor.complete()
        runCurrent()
        assertEquals(ToolCallStatus.Succeeded, session.toolStatus("edit_file"))
    }

    @Test
    fun `denied call is reported to the model and not run`() = runTest {
        val f = fixture(autoApprove = false)
        val session = f.codingSession()
        session.features.require(SendsPrompts).send(f.request())
        f.callTool("edit_file")
        runCurrent()
        val request = assertIs<ActiveSessionState.AwaitingUserAction>(session.state.value).requests.single()
        session.features.require(RequestsPermissions).respond(PermissionDecision(request.turn, request.id, Deny))
        f.executor.complete()
        runCurrent()
        assertEquals(0, edit.calls)
        assertEquals(ToolCallStatus.Failed, session.toolStatus("edit_file"))
        val result = f.executor.prompts.last().messages.flatMap { it.parts }
            .filterIsInstance<MessagePart.Tool.Result>().single()
        assertEquals("Denied by the user", result.output)
        assertTrue(result.isError)
    }

    @Test
    fun `allow for session skips later approvals of that tool`() = runTest {
        val f = fixture(autoApprove = false)
        val session = f.codingSession()
        session.features.require(SendsPrompts).send(f.request())
        f.callTool("edit_file", "call-1")
        runCurrent()
        val request = assertIs<ActiveSessionState.AwaitingUserAction>(session.state.value).requests.single()
        session.features.require(RequestsPermissions)
            .respond(PermissionDecision(request.turn, request.id, AllowForSession))
        runCurrent()
        f.callTool("edit_file", "call-2")
        runCurrent()
        f.executor.complete()
        runCurrent()
        assertEquals(2, edit.calls)
        assertIs<ActiveSessionState.Ready>(session.state.value)
    }

    @Test
    fun `stale or unknown decisions are refused`() = runTest {
        val f = fixture(autoApprove = false)
        val session = f.codingSession()
        session.features.require(SendsPrompts).send(f.request())
        f.callTool("edit_file")
        runCurrent()
        val request = assertIs<ActiveSessionState.AwaitingUserAction>(session.state.value).requests.single()
        val permissions = session.features.require(RequestsPermissions)
        assertFailsWith<EngineException> {
            permissions.respond(PermissionDecision(request.turn, request.id, PermissionOptionId("other")))
        }
        permissions.respond(PermissionDecision(request.turn, request.id, AllowOnce))
        assertFailsWith<EngineException> {
            permissions.respond(PermissionDecision(request.turn, request.id, AllowOnce))
        }
    }

    @Test
    fun `cancelling a turn waiting for approval never runs the tool`() = runTest {
        val f = fixture(autoApprove = false)
        val session = f.codingSession()
        val turn = session.features.require(SendsPrompts).send(f.request())
        f.callTool("edit_file")
        runCurrent()
        assertIs<ActiveSessionState.AwaitingUserAction>(session.state.value)
        session.features.require(CancelsTurns).cancel(turn)
        runCurrent()
        assertEquals(0, edit.calls)
        assertIs<ActiveSessionState.Ready>(session.state.value)
    }

    @Test
    fun `closing the last lease while awaiting approval ends the turn`() = runTest {
        val f = fixture(autoApprove = false)
        val session = f.codingSession()
        session.features.require(SendsPrompts).send(f.request())
        f.callTool("edit_file")
        runCurrent()
        assertIs<ActiveSessionState.AwaitingUserAction>(session.state.value)
        session.close()
        runCurrent()
        val resumed = f.runtime().attach(session.ref, ResumeSessionRequest(f.target, WorkspaceRef("project")))
        assertIs<ActiveSessionState.Ready>(resumed.state.value)
        assertEquals(0, edit.calls)
    }

    @Test
    fun `too long target is refused without asking`() = runTest {
        val f = fixture(autoApprove = false)
        f.workspace = KoogWorkspace(listOf(RecordingTool("edit_file", true, "x".repeat(APPROVAL_TARGET_LIMIT + 1))), "")
        val session = f.codingSession()
        session.features.require(SendsPrompts).send(f.request())
        f.callTool("edit_file")
        runCurrent()
        assertEquals(ToolCallStatus.Failed, session.toolStatus("edit_file"))
        val refusal = session.features.require(SessionHistory).page().items.filterIsInstance<SessionItem.ToolResult>()
            .last().parts.filterIsInstance<ContentPart.Text>().single().text
        assertTrue(refusal.startsWith("Refused"), refusal)
    }

    private fun TestScope.fixture(autoApprove: Boolean = true) = KoogTestFixture(this).apply {
        isAutoApprove = autoApprove
        workspace = KoogWorkspace(listOf(read, edit), INSTRUCTIONS)
    }

    private suspend fun KoogTestFixture.codingSession(): ActiveSession =
        runtime().create(CreateSessionRequest(target, WorkspaceRef("project")))

    private fun KoogTestFixture.callTool(name: String, id: String = "call-$name") {
        executor.frames.trySend(StreamFrame.ToolCallComplete(id, name, "{}", 0))
        executor.frames.trySend(StreamFrame.End("tool_calls"))
    }

    private suspend fun ActiveSession.toolStatus(name: String): ToolCallStatus =
        features.require(SessionHistory).page().items.filterIsInstance<SessionItem.ToolCall>()
            .last { it.name == name }.status

    private class RecordingTool(
        name: String,
        override val isMutating: Boolean,
        private val shownTarget: String = "edit target",
    ) : KoogTool {
        var calls = 0
        override val descriptor = ToolDescriptor(name, "Test tool", emptyList(), emptyList())

        override fun target(args: JsonObject) = if (isMutating) shownTarget else ""

        override suspend fun run(args: JsonObject): KoogToolResult {
            calls++
            return KoogToolResult("done", false)
        }
    }

    private companion object {
        const val INSTRUCTIONS = "You are a coding agent."
    }
}
