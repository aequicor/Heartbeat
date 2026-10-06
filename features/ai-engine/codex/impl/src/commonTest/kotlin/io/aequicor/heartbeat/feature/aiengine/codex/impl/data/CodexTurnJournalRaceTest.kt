@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.feature.aiengine.codex.api.CodexLocalConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.NoAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOptionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestsPermissions
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResolvedToolPolicy
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolPolicyScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

class CodexTurnJournalRaceTest {
    @Test
    fun `policy change during durable begin is refused before native submission`() = runTest {
        val records = GatedTurnRecords()
        var policy = ResolvedToolPolicy()
        val tools = object : ProfileAgentTools by NoAgentTools {
            override suspend fun nativeToolsForExecution(scope: ToolPolicyScope): ResolvedToolPolicy = policy
        }
        val fixture = Fixture(this, turns = records, tools = tools)
        val session = fixture.open()
        records.beforeWrite = { if (it.active != null) records.gate.await() }
        val sending = async { assertFailsWith<EngineException> { session.feature(SendsPrompts).send(Prompt) } }
        runCurrent()
        policy = ResolvedToolPolicy(nativeOff = setOf("shell"), generation = 2)
        records.gate.complete(Unit)
        sending.await()
        runCurrent()
        assertFalse(fixture.wire.written.any { it.text("method") == "turn/start" })
        assertIs<TurnOutcome.Failed>(records.get(session.ref)?.last?.turn?.outcome)
        assertNull(records.get(session.ref)?.active)
    }

    @Test
    fun `missing ownership is rejected before accepting a native request`() = runTest {
        val fixture = Fixture(this, configuration = CodexLocalConfiguration())
        val session = fixture.open()
        assertFailsWith<EngineException> { session.feature(SendsPrompts).send(Prompt) }
        assertFalse(fixture.wire.written.any { it.text("method") == "turn/start" })
        assertNull(fixture.environment.turns.get(session.ref))
    }

    @Test
    fun `completion before response persists terminal and late response cannot revive it`() = runTest {
        val records = MemoryCodexTurnRecords()
        val fixture = Fixture(this, turns = records)
        var pending: JsonObject? = null
        fixture.onTurn = { pending = it }
        val session = fixture.open()
        val sending = async { session.feature(SendsPrompts).send(Prompt) }
        runCurrent()
        fixture.event("turn/completed", "turn" to native("native-turn"))
        runCurrent()
        val turn = sending.await()
        assertEquals(turn, records.get(session.ref)?.last?.turn?.id)
        assertEquals(TurnOutcome.Completed, records.get(session.ref)?.last?.turn?.outcome)
        fixture.wire.reply(checkNotNull(pending), json("turn" to json("id" to "native-turn".json())))
        runCurrent()
        assertNull(records.get(session.ref)?.active)
        assertEquals(TurnOutcome.Completed, records.get(session.ref)?.last?.turn?.outcome)
        assertIs<ActiveSessionState.Ready>(session.state.value)
    }

    @Test
    fun `tool event waits for durable acceptance before dispatching a hosted call`() = runTest {
        val records = GatedTurnRecords()
        val tools = HostedFixture()
        val fixture = Fixture(this, turns = records, tools = tools)
        val workspace = WorkspaceRef("project")
        fixture.workspacePaths[workspace] = "/project"
        fixture.onTurn = {}
        val session = fixture.open(workspace)
        records.beforeWrite = { if (it.active?.nativeId != null) records.gate.await() }
        val sending = async { session.feature(SendsPrompts).send(Prompt) }
        runCurrent()
        fixture.hostedCall("native-turn")
        runCurrent()
        assertFalse(sending.isCompleted)
        assertNull(tools.context)
        records.gate.complete(Unit)
        val turn = sending.await()
        runCurrent()
        assertEquals(turn, tools.context?.turn)
        assertEquals("native-turn", records.get(session.ref)?.active?.nativeId)
        assertIs<ActiveSessionState.AwaitingUserAction>(session.state.value)
    }

    @Test
    fun `terminal revokes hosted approval before waiting for durable completion`() = runTest {
        val records = GatedTurnRecords()
        val tools = HostedFixture()
        val fixture = Fixture(this, turns = records, tools = tools)
        val workspace = WorkspaceRef("project")
        fixture.workspacePaths[workspace] = "/project"
        val session = fixture.open(workspace)
        val turn = session.feature(SendsPrompts).send(Prompt)
        fixture.hostedCall("native-turn")
        runCurrent()
        val request = assertIs<ActiveSessionState.AwaitingUserAction>(session.state.value).requests.single()
        records.beforeWrite = { if (it.last != null) records.gate.await() }
        fixture.event("turn/completed", "turn" to native("native-turn"))
        runCurrent()
        assertFalse(checkNotNull(tools.context?.lifetime).isActive)
        assertEquals(0, tools.executions)
        assertNull(records.get(session.ref)?.last)
        assertFailsWith<EngineException> {
            session.feature(RequestsPermissions).respond(
                PermissionDecision(turn, request.id, PermissionOptionId("hosted.allow")),
            )
        }
        records.gate.complete(Unit)
        runCurrent()
        assertEquals(0, tools.executions)
        assertEquals(TurnOutcome.Completed, records.get(session.ref)?.last?.turn?.outcome)
    }

    @Test
    fun `late restored completion cannot clear the new turns hosted identity`() = runTest {
        val records = MemoryCodexTurnRecords()
        val manifests = MemoryCodexToolManifests()
        val workspace = WorkspaceRef("project")
        val first = Fixture(this, turns = records, tools = HostedFixture(), manifests = manifests)
        first.workspacePaths[workspace] = "/project"
        val original = first.open(workspace)
        original.feature(SendsPrompts).send(Prompt)
        first.event("turn/completed", "turn" to native("native-turn"))
        runCurrent()
        first.runtime.close()

        val tools = HostedFixture()
        val second = Fixture(this, turns = records, tools = tools, manifests = manifests)
        second.workspacePaths[workspace] = "/project"
        second.onTurn = { second.wire.reply(it, json("turn" to json("id" to "new-turn".json()))) }
        val session = second.runtime.attach(original.ref, ResumeSessionRequest(second.target, workspace))
        val turn = session.feature(SendsPrompts).send(Prompt.copy(id = RequestId("second")))
        second.event("turn/completed", "turn" to native("native-turn"))
        second.hostedCall("new-turn")
        runCurrent()
        val pending = assertIs<ActiveSessionState.AwaitingUserAction>(session.state.value)
        assertEquals(turn, pending.turn.id)
        assertEquals(turn, tools.context?.turn)
        val request = pending.requests.single()
        session.feature(RequestsPermissions).respond(
            PermissionDecision(turn, request.id, PermissionOptionId("hosted.allow")),
        )
        runCurrent()
        assertEquals(1, tools.executions)
    }

    private suspend fun Fixture.hostedCall(turn: String) = event(
        "item/tool/call",
        "turnId" to turn.json(),
        "tool" to "run_command".json(),
        "arguments" to JsonObject(emptyMap()),
        id = JsonPrimitive(80),
    )

    private fun native(id: String): JsonObject = json("id" to id.json(), "status" to "completed".json())
}
