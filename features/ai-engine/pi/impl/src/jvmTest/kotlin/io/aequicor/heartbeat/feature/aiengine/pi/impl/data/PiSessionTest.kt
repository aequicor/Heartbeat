package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.OwnedScope
import io.aequicor.heartbeat.core.di.SavedBundle
import io.aequicor.heartbeat.core.di.ScopeFactory
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.di.ScopeSavedState
import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineLauncher
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineSpec
import io.aequicor.heartbeat.core.statemachine.MachineState
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.AccessFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ExecutionRoute
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionAnswer
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionChoice
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionInput
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOptionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestsPermissions
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.pi.api.PiEngineId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PiSessionTest {
    @Test
    fun `unknown thinking level is rejected before a native prompt`() = runTest {
        val fixture = fixture()
        val request = prompt("effort").copy(reasoningEffort = "turbo")
        val error = assertFailsWith<EngineException> { fixture.session.send(request) }
        assertEquals(EngineFailure.Request(RequestFailureReason.Invalid, request.id), error.failure)
        assertFalse("prompt" in fixture.connection.commands)
        fixture.session.shutdown()
    }

    @Test
    fun `selected thinking level is applied before the prompt and the native level restored later`() = runTest {
        val fixture = fixture()
        fixture.connection.promptAck.complete(JsonObject(emptyMap()))
        fixture.session.send(prompt("first").copy(reasoningEffort = "high"))
        fixture.connection.event(record("""{"type":"agent_settled"}"""))
        fixture.session.send(prompt("second").copy(reasoningEffort = "high"))
        fixture.connection.event(record("""{"type":"agent_settled"}"""))
        fixture.session.send(prompt("third"))
        fixture.connection.event(record("""{"type":"agent_settled"}"""))
        runCurrent()
        val levels = fixture.connection.commands.zip(fixture.connection.fields)
            .filter { it.first == "set_thinking_level" }
            .map { it.second.string("level") }
        assertEquals(listOf("high", "medium"), levels)
        val first = fixture.connection.commands.indexOf("set_thinking_level")
        assertEquals("prompt", fixture.connection.commands[first + 1])
        fixture.session.shutdown()
    }

    @Test
    fun `thinking levels follow the model catalog`() {
        fun model(json: String) = Json.parseToJsonElement(json).jsonObject.piThinkingLevels()
        assertEquals(emptyList(), model("""{"reasoning":false}"""))
        assertEquals(listOf("off", "minimal", "low", "medium", "high"), model("""{"reasoning":true}"""))
        assertEquals(
            listOf("off", "low", "medium", "high", "xhigh"),
            model("""{"reasoning":true,"thinkingLevelMap":{"minimal":null,"xhigh":"max"}}"""),
        )
    }

    @Test
    fun `caller cancellation keeps the native turn and close releases the process after it settles`() = runTest {
        val fixture = fixture()
        val send = async { fixture.session.send(prompt("first")) }
        runCurrent()
        send.cancel()
        fixture.connection.event(record("""{"type":"agent_start"}"""))
        assertIs<ActiveSessionState.Running>(fixture.session.state.value)
        fixture.session.close()
        assertEquals(ActiveSessionState.Closed, fixture.session.state.value)
        assertFalse(fixture.connection.closed)
        fixture.connection.promptAck.complete(JsonObject(emptyMap()))
        fixture.connection.event(record("""{"type":"agent_settled"}"""))
        runCurrent()
        assertTrue(fixture.connection.closed)
        assertEquals(listOf(fixture.session), fixture.released)
    }

    @Test
    fun `closing an idle session releases its process`() = runTest {
        val fixture = fixture()
        fixture.session.close()
        assertTrue(fixture.connection.closed)
        assertEquals(listOf(fixture.session), fixture.released)
    }

    @Test
    fun `synchronize after process loss restarts pi on the same transcript`() = runTest {
        val fixture = fixture()
        fixture.connection.promptAck.complete(JsonObject(emptyMap()))
        val turn = fixture.session.send(prompt("first"))
        fixture.connection.isOpen = false
        fixture.connection.failed(EngineFailure.Engine(EngineFailureReason.Crashed))
        assertIs<ActiveSessionState.Unavailable>(fixture.session.state.value)
        fixture.session.synchronize()
        val restarted = fixture.connections.last()
        assertEquals(2, fixture.connections.size)
        assertEquals(listOf("switch_session", "get_state"), restarted.commands)
        assertEquals("native.jsonl", restarted.fields.first().string("sessionPath"))
        val ready = assertIs<ActiveSessionState.Ready>(fixture.session.state.value)
        assertEquals(turn, ready.lastTurn?.id)
        assertEquals(TurnOutcome.Unknown, ready.lastTurn?.outcome)
        fixture.session.shutdown()
    }

    @Test
    fun `rejected model change keeps the session ready`() = runTest {
        val fixture = fixture()
        val failure = assertFailsWith<EngineException> { fixture.session.switchTo(ModelId("anthropic/missing")) }
        assertIs<EngineFailure.Request>(failure.failure)
        assertIs<ActiveSessionState.Ready>(fixture.session.state.value)
        fixture.session.shutdown()
    }

    @Test
    fun `validation failure before delivery is reported as a definite send failure`() = runTest {
        var checks = 0
        val fixture = fixture(validate = {
            checks++
            if (checks > 1) throw EngineException(EngineFailure.Access(AccessFailureReason.OperationNotAllowed))
        })
        val failure = assertFailsWith<EngineException> { fixture.session.send(prompt("blocked")) }
        assertIs<EngineFailure.Access>(failure.failure)
        assertFalse("prompt" in fixture.connection.commands)
        fixture.session.shutdown()
    }

    @Test
    fun overlappingSendIsRejectedAndAgentEndDoesNotPrematurelyFinishRetry() = runTest {
        val fixture = fixture()
        val send = async { fixture.session.send(prompt("first")) }
        runCurrent()
        fixture.connection.event(record("""{"type":"agent_start"}"""))
        assertFailsWith<EngineException> { fixture.session.send(prompt("second")) }
        fixture.connection.promptAck.complete(JsonObject(emptyMap()))
        runCurrent()
        send.await()
        fixture.connection.event(record("""{"type":"agent_end","willRetry":true}"""))
        assertIs<ActiveSessionState.Running>(fixture.session.state.value)
        fixture.connection.event(
            record("""{"type":"message_end","message":{"role":"assistant","content":[],"stopReason":"error"}}"""),
        )
        fixture.connection.event(record("""{"type":"agent_settled"}"""))
        val ready = assertIs<ActiveSessionState.Ready>(fixture.session.state.value)
        assertIs<TurnOutcome.Failed>(ready.lastTurn?.outcome)
        fixture.session.shutdown()
    }

    @Test
    fun quickCompletionBeforeAcceptanceResponseKeepsCompletedOutcome() = runTest {
        val fixture = fixture()
        val send = async { fixture.session.send(prompt("first")) }
        runCurrent()
        fixture.connection.event(record("""{"type":"agent_settled"}"""))
        fixture.connection.promptAck.complete(JsonObject(emptyMap()))
        runCurrent()
        assertEquals(send.await(), assertIs<ActiveSessionState.Ready>(fixture.session.state.value).lastTurn?.id)
        assertEquals(
            TurnOutcome.Completed,
            assertIs<ActiveSessionState.Ready>(fixture.session.state.value).lastTurn?.outcome,
        )
        fixture.session.shutdown()
    }

    @Test
    fun shutdownBeforeNativeAcceptanceReportsUnknownDelivery() = runTest {
        val fixture = fixture()
        val sending = async { assertFailsWith<EngineException> { fixture.session.send(prompt("unsent")) } }
        runCurrent()
        fixture.session.shutdown()
        val failure = assertIs<EngineFailure.Request>(sending.await().failure)
        assertEquals(RequestFailureReason.OutcomeUnknown, failure.reason)
        assertEquals(ActiveSessionState.Closed, fixture.session.state.value)
    }

    @Test
    fun finishingBeforeAbortResponseDoesNotPermitAbortToReachANewTurn() = runTest {
        val fixture = fixture()
        fixture.connection.promptAck.complete(JsonObject(emptyMap()))
        val first = fixture.session.send(prompt("first"))
        val cancelling = async { fixture.session.cancel(first) }
        runCurrent()
        fixture.connection.event(record("""{"type":"agent_settled"}"""))
        assertFailsWith<EngineException> { fixture.session.send(prompt("too-early")) }
        fixture.connection.abortAck.complete(JsonObject(emptyMap()))
        cancelling.await()
        assertEquals(1, fixture.connection.commands.count { it == "abort" })
        fixture.session.send(prompt("next"))
        assertIs<ActiveSessionState.Running>(fixture.session.state.value)
        fixture.session.shutdown()
    }

    @Test
    fun cancellingModelChangeCallerStillRecordsTheNativeModel() = runTest {
        val fixture = fixture()
        val changing = launch { fixture.session.switchTo(ModelId("anthropic/other")) }
        runCurrent()
        changing.cancel()
        fixture.connection.modelAck.complete(JsonObject(emptyMap()))
        changing.join()
        fixture.connection.promptAck.complete(JsonObject(emptyMap()))
        fixture.session.send(prompt("new-model"))
        val running = assertIs<ActiveSessionState.Running>(fixture.session.state.value)
        assertEquals(ModelId("anthropic/other"), running.turn.target.model)
        fixture.session.shutdown()
    }

    @Test
    fun `tool approval waits for the user and an allow answer reaches pi`() = runTest {
        val fixture = fixture()
        val turn = fixture.runningTurn()
        fixture.connection.event(approval("ui-1"))
        val awaiting = assertIs<ActiveSessionState.AwaitingUserAction>(fixture.session.state.value)
        assertEquals("bash: ls -la", awaiting.requests.single().title)
        val permissions = assertIs<FeatureAccess.Available<RequestsPermissions>>(
            fixture.session.features.resolve(RequestsPermissions),
        ).feature
        permissions.respond(PermissionDecision(turn, PermissionRequestId("ui-1"), PermissionOptionId("allow")))
        runCurrent()
        assertEquals(listOf(answer("ui-1", "confirmed", true)), fixture.connection.sent)
        assertIs<ActiveSessionState.Running>(fixture.session.state.value)
        fixture.session.shutdown()
    }

    @Test
    fun `denied tool approval is answered negatively`() = runTest {
        val fixture = fixture()
        val turn = fixture.runningTurn()
        fixture.connection.event(approval("ui-2"))
        fixture.session.respond(PermissionDecision(turn, PermissionRequestId("ui-2"), PermissionOptionId("deny")))
        runCurrent()
        assertEquals(listOf(answer("ui-2", "confirmed", false)), fixture.connection.sent)
        fixture.session.shutdown()
    }

    @Test
    fun `dialogs nobody can answer are dismissed so pi blocks the tool`() = runTest {
        val fixture = fixture()
        fixture.connection.event(approval("idle"))
        fixture.connection.event(
            record("""{"type":"extension_ui_request","id":"other","method":"input","title":"Name?"}"""),
        )
        fixture.runningTurn()
        fixture.connection.event(record("""{"type":"extension_ui_request","id":"n","method":"notify"}"""))
        assertEquals(
            listOf(answer("idle", "cancelled", true), answer("other", "cancelled", true)),
            fixture.connection.sent,
        )
        assertIs<ActiveSessionState.Running>(fixture.session.state.value)
        fixture.session.shutdown()
    }

    @Test
    fun `select dialog becomes a single choice and the chosen value reaches pi`() = runTest {
        val fixture = fixture()
        val turn = fixture.runningTurn()
        fixture.connection.event(
            record(
                """{"type":"extension_ui_request","id":"s","method":"select","title":"Pick",""" +
                    """"options":["red","blue"]}""",
            ),
        )
        val request = assertIs<ActiveSessionState.AwaitingUserAction>(fixture.session.state.value).requests.single()
        assertEquals(
            PermissionInput.SingleChoice(listOf(PermissionChoice("0", "red"), PermissionChoice("1", "blue"))),
            request.input,
        )
        fixture.session.respond(
            PermissionDecision(turn, request.id, PermissionOptionId("answer"), PermissionAnswer.Selected(listOf("1"))),
        )
        runCurrent()
        assertEquals(listOf(valueAnswer("s", "blue")), fixture.connection.sent)
        assertIs<ActiveSessionState.Running>(fixture.session.state.value)
        fixture.session.shutdown()
    }

    @Test
    fun `text dialogs send the typed value and skipping cancels them`() = runTest {
        val fixture = fixture()
        val turn = fixture.runningTurn()
        fixture.connection.event(
            record("""{"type":"extension_ui_request","id":"t","method":"editor","title":"Notes"}"""),
        )
        fixture.connection.event(
            record("""{"type":"extension_ui_request","id":"u","method":"input","title":"Name?"}"""),
        )
        val requests = assertIs<ActiveSessionState.AwaitingUserAction>(fixture.session.state.value).requests
        assertEquals(PermissionInput.FreeText(isMultiline = true), requests.first().input)
        fixture.session.respond(
            PermissionDecision(
                turn,
                PermissionRequestId("t"),
                PermissionOptionId("answer"),
                PermissionAnswer.Text("hi"),
            ),
        )
        runCurrent()
        fixture.session.respond(PermissionDecision(turn, PermissionRequestId("u"), PermissionOptionId("skip")))
        runCurrent()
        assertEquals(listOf(valueAnswer("t", "hi"), answer("u", "cancelled", true)), fixture.connection.sent)
        fixture.session.shutdown()
    }

    @Test
    fun `an undelivered dialog answer keeps the request pending in the session`() = runTest {
        val fixture = fixture()
        val turn = fixture.runningTurn()
        fixture.connection.event(
            record("""{"type":"extension_ui_request","id":"t","method":"input","title":"Name?"}"""),
        )
        val decision = PermissionDecision(
            turn,
            PermissionRequestId("t"),
            PermissionOptionId("answer"),
            PermissionAnswer.Text("hi"),
        )
        fixture.connection.sendFailure =
            EngineException(EngineFailure.Transport(TransportFailureReason.ServiceUnavailable))
        fixture.session.respond(decision)
        runCurrent()
        assertTrue(fixture.connection.sent.isEmpty())
        // Pi still waits for the dialog, so closing the session must still decline it.
        fixture.session.close()
        assertEquals(listOf(answer("t", "cancelled", true)), fixture.connection.sent)
        fixture.session.shutdown()
    }

    @Test
    fun `plain confirm dialog is answered with the chosen option`() = runTest {
        val fixture = fixture()
        val turn = fixture.runningTurn()
        fixture.connection.event(
            record("""{"type":"extension_ui_request","id":"c","method":"confirm","title":"Go?","message":"Sure"}"""),
        )
        val request = assertIs<ActiveSessionState.AwaitingUserAction>(fixture.session.state.value).requests.single()
        assertEquals("Sure", request.description)
        fixture.session.respond(PermissionDecision(turn, request.id, PermissionOptionId("yes")))
        runCurrent()
        assertEquals(listOf(answer("c", "confirmed", true)), fixture.connection.sent)
        fixture.session.shutdown()
    }

    @Test
    fun `cancelling a turn dismisses its pending approvals before aborting`() = runTest {
        val fixture = fixture()
        val turn = fixture.runningTurn()
        fixture.connection.event(approval("ui-3"))
        fixture.connection.abortAck.complete(JsonObject(emptyMap()))
        fixture.session.cancel(turn)
        assertEquals(listOf(answer("ui-3", "cancelled", true)), fixture.connection.sent)
        assertEquals("abort", fixture.connection.commands.last())
        fixture.session.shutdown()
    }

    @Test
    fun `close after a rejected prompt releases the process`() = runTest {
        val fixture = fixture()
        fixture.connection.promptAck.completeExceptionally(
            EngineException(EngineFailure.Request(RequestFailureReason.Invalid)),
        )
        assertFailsWith<EngineException> { fixture.session.send(prompt("rejected")) }
        fixture.session.close()
        assertTrue(fixture.connection.closed)
        assertEquals(listOf(fixture.session), fixture.released)
    }

    @Test
    fun `failed transcript reattachment never keeps the restarted process`() = runTest {
        val fixture = fixture { index, connection ->
            if (index == 1) {
                connection.switchFailure = EngineException(
                    EngineFailure.Request(RequestFailureReason.Invalid),
                )
            }
        }
        fixture.connection.isOpen = false
        fixture.connection.failed(EngineFailure.Engine(EngineFailureReason.Crashed))
        assertFailsWith<EngineException> { fixture.session.synchronize() }
        assertTrue(fixture.connections[1].closed)
        fixture.session.synchronize()
        assertEquals(3, fixture.connections.size)
        assertIs<ActiveSessionState.Ready>(fixture.session.state.value)
        fixture.session.shutdown()
    }

    @Test
    fun `a restarted process on another transcript is rejected`() = runTest {
        val fixture = fixture { index, connection -> if (index == 1) connection.sessionId = "other" }
        fixture.connection.isOpen = false
        fixture.connection.failed(EngineFailure.Engine(EngineFailureReason.Crashed))
        val failure = assertFailsWith<EngineException> { fixture.session.synchronize() }
        assertEquals(EngineFailure.Session(SessionFailureReason.Changed), failure.failure)
        fixture.session.shutdown()
    }

    @Test
    fun `callbacks of a replaced process are ignored`() = runTest {
        val fixture = fixture()
        val stale = fixture.connection
        stale.isOpen = false
        stale.failed(EngineFailure.Engine(EngineFailureReason.Crashed))
        fixture.session.synchronize()
        assertIs<ActiveSessionState.Ready>(fixture.session.state.value)
        stale.failed(EngineFailure.Engine(EngineFailureReason.Crashed))
        stale.event(record("""{"type":"agent_start"}"""))
        assertIs<ActiveSessionState.Ready>(fixture.session.state.value)
        fixture.session.shutdown()
    }

    @Test
    fun `closing while an approval is pending declines it and releases after the turn settles`() = runTest {
        val fixture = fixture()
        fixture.runningTurn()
        fixture.connection.event(approval("ui-4"))
        fixture.session.close()
        assertEquals(listOf(answer("ui-4", "cancelled", true)), fixture.connection.sent)
        assertFalse(fixture.connection.closed)
        fixture.connection.event(record("""{"type":"agent_settled"}"""))
        assertTrue(fixture.connection.closed)
    }

    @Test
    fun `approval text shows hidden characters and oversized commands are blocked`() = runTest {
        val fixture = fixture()
        fixture.runningTurn()
        fixture.connection.event(approval("ui-5", "ls\n‮rm -rf"))
        val awaiting = assertIs<ActiveSessionState.AwaitingUserAction>(fixture.session.state.value)
        assertEquals("bash: ls\\n\\u202erm -rf", awaiting.requests.single().title)
        fixture.connection.event(approval("ui-6", "x".repeat(4_001)))
        assertEquals(listOf(answer("ui-6", "cancelled", true)), fixture.connection.sent)
        fixture.session.shutdown()
    }

    private suspend fun TestScope.fixture(
        validate: suspend () -> Unit = {},
        configure: (Int, FakeConnection) -> Unit = { _, _ -> },
    ): Fixture {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dispatchers = object : DispatcherProvider {
            override val main: CoroutineDispatcher = dispatcher
            override val io: CoroutineDispatcher = dispatcher
            override val default: CoroutineDispatcher = dispatcher
        }
        val scope = FakeScope(backgroundScope)
        val scopes = object : ScopeFactory {
            override fun child(parent: ScopeHandle, name: String, restored: SavedBundle?): OwnedScope =
                FakeScope(backgroundScope)
        }
        val target = EngineTarget(PiEngineId, EngineBindingId("binding"), ModelId("anthropic/test"))
        val route = ExecutionRoute(PiEngineId, target.binding, AuthSourceId("source"), AuthRevision.Known("1"))
        val released = mutableListOf<PiSession>()
        val session = PiSession(
            CreateSessionRequest(target),
            route,
            PiSessionEnvironment(ReducerLauncher(), scopes, scope, dispatchers),
            validate,
            { released += it },
        )
        val connections = mutableListOf<FakeConnection>()
        session.start { event, failed ->
            FakeConnection().also {
                it.event = event
                it.failed = failed
                configure(connections.size, it)
                connections += it
            }
        }
        return Fixture(session, connections, released)
    }

    private suspend fun Fixture.runningTurn(): TurnId {
        connection.promptAck.complete(JsonObject(emptyMap()))
        val turn = session.send(prompt("tool"))
        connection.event(record("""{"type":"agent_start"}"""))
        return turn
    }

    private fun approval(id: String, target: String = "ls -la"): JsonObject {
        val message = JsonObject(
            mapOf(
                "toolCallId" to JsonPrimitive("c1"),
                "toolName" to JsonPrimitive("bash"),
                "target" to JsonPrimitive(target),
            ),
        )
        return JsonObject(
            mapOf(
                "type" to JsonPrimitive("extension_ui_request"),
                "id" to JsonPrimitive(id),
                "method" to JsonPrimitive("confirm"),
                "title" to JsonPrimitive("heartbeat.tool-approval"),
                "message" to JsonPrimitive(message.toString()),
            ),
        )
    }

    private fun answer(id: String, field: String, value: Boolean) = JsonObject(
        mapOf(
            "type" to JsonPrimitive("extension_ui_response"),
            "id" to JsonPrimitive(id),
            field to JsonPrimitive(value),
        ),
    )

    private fun valueAnswer(id: String, value: String) = JsonObject(
        mapOf(
            "type" to JsonPrimitive("extension_ui_response"),
            "id" to JsonPrimitive(id),
            "value" to JsonPrimitive(value),
        ),
    )

    private fun prompt(id: String) = PromptRequest(RequestId(id), listOf(ContentPart.Text("Hello")))
    private fun record(json: String) = Json.parseToJsonElement(json).jsonObject
    private data class Fixture(
        val session: PiSession,
        val connections: List<FakeConnection>,
        val released: List<PiSession>,
    ) {
        val connection: FakeConnection get() = connections.first()
    }
}

private class FakeConnection : PiConnection {
    var event: suspend (JsonObject) -> Unit = {}
    var failed: suspend (EngineFailure) -> Unit = {}
    val promptAck = CompletableDeferred<JsonObject>()
    val abortAck = CompletableDeferred<JsonObject>()
    val modelAck = CompletableDeferred<JsonObject>()
    val commands = mutableListOf<String>()
    val fields = mutableListOf<JsonObject>()
    val sent = mutableListOf<JsonObject>()
    var closed = false
    var sessionId = "native"
    var switchFailure: EngineException? = null
    var sendFailure: EngineException? = null
    override var isOpen = true
    override suspend fun command(type: String, fields: JsonObject): JsonObject {
        commands += type
        this.fields += fields
        return when (type) {
            "get_state" -> state()

            "switch_session" -> switchFailure?.let { throw it } ?: JsonObject(emptyMap())

            "prompt" -> promptAck.await()

            "abort" -> abortAck.await()

            "set_model" -> when (fields.string("modelId")) {
                "other" -> modelAck.await()
                "missing" -> throw EngineException(EngineFailure.Request(RequestFailureReason.Invalid))
                else -> JsonObject(emptyMap())
            }

            else -> JsonObject(emptyMap())
        }
    }
    override suspend fun send(record: JsonObject) {
        sendFailure?.let {
            sendFailure = null
            throw it
        }
        sent += record
    }
    override fun close() {
        closed = true
        isOpen = false
    }

    private fun state() = Json.parseToJsonElement(
        """{"sessionId":"$sessionId","sessionFile":"native.jsonl","isStreaming":false,"thinkingLevel":"medium",
           "model":{"provider":"anthropic","id":"test"}}""",
    ).jsonObject
}

private class ReducerLauncher : MachineLauncher {
    override fun <S : MachineState, I : MachineIntent, E : MachineEffect, O : MachineOutput> launch(
        spec: MachineSpec<S, I, E, O>,
        scope: ScopeHandle,
        effects: EffectHandler<E, I>,
    ): Machine<S, I, O> = object : Machine<S, I, O> {
        override val name = spec.name
        override val state = MutableStateFlow(spec.initial)
        override val outputs = MutableSharedFlow<O>()
        override suspend fun send(intent: I): SendResult {
            if (scope.isClosed) return SendResult.NotRunning
            val resolved = spec.resolve(state.value, intent) ?: return SendResult.Ignored
            state.value = resolved.to
            resolved.effects.forEach { effect ->
                scope.coroutineScope.launch {
                    effects.handle(
                        effect,
                        object : EffectScope<I> {
                            override suspend fun send(intent: I): SendResult = SendResult.Accepted
                        },
                    )
                }
            }
            return SendResult.Accepted
        }
    }
}

private class FakeScope(override val coroutineScope: CoroutineScope) : OwnedScope {
    override val name = "test"
    override var isClosed = false
    private val actions = mutableListOf<() -> Unit>()
    override fun close() {
        isClosed = true
        actions.asReversed().forEach { it() }
    }
    override fun onClose(action: () -> Unit): DisposableHandle {
        actions += action
        return DisposableHandle { actions -= action }
    }
    override val savedState = object : ScopeSavedState {
        override fun <T : Any> consume(key: String, serializer: KSerializer<T>): T? = null
        override fun <T : Any> register(key: String, serializer: KSerializer<T>, supplier: () -> T?) = Unit
        override fun unregister(key: String) = Unit
        override fun snapshot() = SavedBundle(emptyMap())
    }
}
