package io.aequicor.heartbeat.feature.aiengine.kimi.impl.data

import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpConnection
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpImplementation
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpInitialization
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpPermissionOption
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpPermissionOutcome
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpPermissionRequest
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpPromptResult
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpSession
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpSessionUpdate
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOptionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity
import io.aequicor.heartbeat.feature.aiengine.kimi.api.KimiEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class KimiRuntimeTest {
    @Test
    fun `prompt merges streamed text and completes turn`() = runTest {
        val connection = FakeConnection()
        val runtime = runtime(connection, this)
        val session = runtime.create(CreateSessionRequest(target))
        val turn = session.sender().send(PromptRequest(RequestId("request"), listOf(ContentPart.Text("Hello"))))
        connection.update("""{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"Hel"}}""")
        connection.update("""{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"lo"}}""")
        connection.prompt.complete(AcpPromptResult("end_turn"))
        testScheduler.advanceUntilIdle()

        assertEquals(TurnOutcome.Completed, session.current().lastTurn?.outcome)
        val history = session.history().page()
        val answer = history.items.filterIsInstance<SessionItem.Message>().last()
        assertEquals("Hello", (answer.parts.single() as ContentPart.Text).text)
        assertEquals(turn, session.current().lastTurn?.id)
    }

    @Test
    fun `permission waits for matching user decision`() = runTest {
        val connection = FakeConnection()
        val runtime = runtime(connection, this)
        val session = runtime.create(CreateSessionRequest(target))
        val turn = session.sender().send(PromptRequest(RequestId("request"), listOf(ContentPart.Text("Edit"))))
        val permission = async {
            runtime.requestPermission(
                AcpPermissionRequest(
                    session.ref.nativeId,
                    buildJsonObject {
                        put("toolCallId", "call")
                        put("title", "Edit file")
                    },
                    listOf(AcpPermissionOption("allow", "Allow", "allow_once")),
                ),
            )
        }
        testScheduler.advanceUntilIdle()
        assertIs<ActiveSessionState.AwaitingUserAction>(session.state.value)
        session.permissions().respond(
            PermissionDecision(
                turn,
                io.aequicor.heartbeat.feature.aiengine.facade.api
                    .PermissionRequestId("call"),
                PermissionOptionId("allow"),
            ),
        )
        assertEquals(AcpPermissionOutcome.Selected("allow"), permission.await())
        connection.prompt.complete(AcpPromptResult("end_turn"))
        testScheduler.advanceUntilIdle()
        assertEquals(TurnOutcome.Completed, session.current().lastTurn?.outcome)
    }

    @Test
    fun `cancel uses native session and keeps terminal outcome`() = runTest {
        val connection = FakeConnection()
        val runtime = runtime(connection, this)
        val session = runtime.create(CreateSessionRequest(target))
        val turn = session.sender().send(PromptRequest(RequestId("request"), listOf(ContentPart.Text("Work"))))
        session.canceller().cancel(turn)
        assertEquals(session.ref.nativeId, connection.cancelled)
        connection.prompt.complete(AcpPromptResult("cancelled"))
        testScheduler.advanceUntilIdle()
        assertEquals(TurnOutcome.Cancelled, session.current().lastTurn?.outcome)
    }

    private fun runtime(connection: FakeConnection, scope: kotlinx.coroutines.CoroutineScope): KimiRuntime {
        val runtime = KimiRuntime(
            RuntimeIdentity(KimiEngine.Id, KimiEngine.AuthSource, AuthRevision.Known("one")),
            connection,
            scope,
            AcpInitialization(1),
        ) { "/workspace" }
        connection.runtime = runtime
        return runtime
    }

    private val target = EngineTarget(KimiEngine.Id, EngineBindingId("binding"), ModelId("kimi-for-coding"))
}

private class FakeConnection : AcpConnection {
    val prompt = CompletableDeferred<AcpPromptResult>()
    var cancelled: String? = null
    private lateinit var handler: io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpClientHandler
    lateinit var runtime: KimiRuntime

    override suspend fun initialize(client: AcpImplementation) = AcpInitialization(1)
    override suspend fun authenticate(methodId: String) = Unit
    override suspend fun newSession(cwd: String) = AcpSession("native", JsonObject(emptyMap()))
    override suspend fun loadSession(sessionId: String, cwd: String) = AcpSession(sessionId, JsonObject(emptyMap()))
    override suspend fun prompt(sessionId: String, text: String) = prompt.await()
    override suspend fun cancel(sessionId: String) {
        cancelled = sessionId
    }
    override suspend fun close() = Unit

    suspend fun update(update: String) {
        val json = kotlinx.serialization.json.Json.parseToJsonElement(update).jsonObject
        runtime.onSessionUpdate(AcpSessionUpdate("native", json))
    }
}

private fun io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession.sender() = (
    features.resolve(io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts) as
        io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess.Available
).feature

private fun io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession.canceller() = (
    features.resolve(io.aequicor.heartbeat.feature.aiengine.facade.api.CancelsTurns) as
        io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess.Available
).feature

private fun io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession.permissions() = (
    features.resolve(io.aequicor.heartbeat.feature.aiengine.facade.api.RequestsPermissions) as
        io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess.Available
).feature

private fun io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession.history() = (
    features.resolve(SessionHistory) as
        io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess.Available
).feature

private fun io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession.current() =
    state.value as ActiveSessionState.Ready
