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
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
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
    private suspend fun TestScope.fixture(validate: suspend () -> Unit = {}): Fixture {
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
                connections += it
            }
        }
        return Fixture(session, connections, released)
    }

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
    override var isOpen = true
    override suspend fun command(type: String, fields: JsonObject): JsonObject {
        commands += type
        this.fields += fields
        return when (type) {
            "get_state" -> state()

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
        sent += record
    }
    override fun close() {
        closed = true
        isOpen = false
    }

    private fun state() = Json.parseToJsonElement(
        """{"sessionId":"native","sessionFile":"native.jsonl","isStreaming":false,
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
