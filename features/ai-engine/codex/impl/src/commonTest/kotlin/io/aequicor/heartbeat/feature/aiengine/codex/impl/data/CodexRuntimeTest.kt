@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.aequicor.heartbeat.feature.aiengine.codex.impl.data
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.CancelsTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCheckpoint
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOptionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestsPermissions
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionEvent
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CodexRuntimeTest {
    @Test
    fun `send waits for acceptance and close only releases its lease`() = runTest {
        val fixture = Fixture(this)
        val session = fixture.open()
        val turn = session.feature(SendsPrompts).send(Prompt)
        assertIs<ActiveSessionState.Running>(session.state.value)
        session.close()
        assertEquals(ActiveSessionState.Closed, session.state.value)
        assertFalse(fixture.wire.closed)
        val second = fixture.runtime.attach(session.ref, ResumeSessionRequest(fixture.target))
        assertEquals(turn, assertIs<ActiveSessionState.Running>(second.state.value).turn.id)
        fixture.event("turn/completed", "turn" to json("id" to "native-turn".json(), "status" to "completed".json()))
        runCurrent()
        assertEquals(TurnOutcome.Completed, assertIs<ActiveSessionState.Ready>(second.state.value).lastTurn?.outcome)
    }

    @Test
    fun `concurrent prompts are rejected and cancellation waits for native confirmation`() = runTest {
        val fixture = Fixture(this)
        val session = fixture.open()
        val turn = session.feature(SendsPrompts).send(Prompt)
        val failure = assertFailsWith<EngineException> { session.feature(SendsPrompts).send(Prompt) }
        assertEquals(EngineFailure.Session(SessionFailureReason.Busy), failure.failure)
        session.feature(CancelsTurns).cancel(turn)
        runCurrent()
        assertIs<ActiveSessionState.Interrupting>(session.state.value)
        fixture.event("turn/completed", "turn" to json("id" to "native-turn".json(), "status" to "interrupted".json()))
        runCurrent()
        assertEquals(TurnOutcome.Cancelled, assertIs<ActiveSessionState.Ready>(session.state.value).lastTurn?.outcome)
    }

    @Test
    fun `permission decision stays pending until acknowledgement and cannot be replayed`() = runTest {
        val fixture = Fixture(this)
        val session = fixture.open()
        val turn = session.feature(SendsPrompts).send(Prompt)
        fixture.event(
            "item/commandExecution/requestApproval",
            "turnId" to "native-turn".json(),
            "command" to "test".json(),
            id = JsonPrimitive(42),
        )
        runCurrent()
        val pending = assertIs<ActiveSessionState.AwaitingUserAction>(session.state.value)
        val decision = PermissionDecision(turn, pending.requests.single().id, PermissionOptionId("decline"))
        session.feature(RequestsPermissions).respond(decision)
        runCurrent()
        assertIs<ActiveSessionState.AwaitingUserAction>(session.state.value)
        assertFailsWith<EngineException> { session.feature(RequestsPermissions).respond(decision) }
        fixture.event("serverRequest/resolved", "requestId" to JsonPrimitive(42))
        runCurrent()
        assertIs<ActiveSessionState.Running>(session.state.value)
        assertFailsWith<EngineException> { session.feature(RequestsPermissions).respond(decision) }
    }

    @Test
    fun `account switch prevents sending and never falls back to another login`() = runTest {
        val fixture = Fixture(this)
        val session = fixture.open()
        fixture.account = json("type" to "chatgpt".json(), "email" to "changed@example.invalid".json())
        val failure = assertFailsWith<EngineException> { session.feature(SendsPrompts).send(Prompt) }
        assertEquals("auth.SourceChanged", failure.failure.code)
        assertFalse(fixture.wire.written.any { it.text("method") == "turn/start" })
    }

    @Test
    fun `completion before response cannot accept the next submission`() = runTest {
        val fixture = Fixture(this)
        val pending = mutableListOf<JsonObject>()
        fixture.onTurn = { pending += it }
        val session = fixture.open()
        val first = async { session.feature(SendsPrompts).send(Prompt) }
        runCurrent()
        fixture.event("turn/started", "turn" to json("id" to "first".json()))
        fixture.event("turn/completed", "turn" to json("id" to "first".json(), "status" to "completed".json()))
        runCurrent()
        val firstId = first.await()
        val second = async { session.feature(SendsPrompts).send(Prompt.copy(id = RequestId("second"))) }
        runCurrent()
        fixture.wire.reply(pending[0], json("turn" to json("id" to "first".json())))
        runCurrent()
        assertFalse(second.isCompleted)
        fixture.wire.reply(pending[1], json("turn" to json("id" to "second".json())))
        assertNotEquals(firstId, second.await())
    }

    @Test
    fun `transport loss after send reports unknown acceptance`() = runTest {
        val fixture = Fixture(this)
        fixture.onTurn = { fixture.wire.incoming.close() }
        val session = fixture.open()
        val failure = assertFailsWith<EngineException> { session.feature(SendsPrompts).send(Prompt) }
        assertIs<ActiveSessionState.Unavailable>(session.state.value)
        assertTrue(failure.failure is EngineFailure.Request || failure.failure is EngineFailure.Engine)
    }

    @Test
    fun `late error from completed turn cannot fail the next turn`() = runTest {
        val fixture = Fixture(this)
        val pending = mutableListOf<JsonObject>()
        fixture.onTurn = { pending += it }
        val session = fixture.open()
        val first = async { session.feature(SendsPrompts).send(Prompt) }
        runCurrent()
        fixture.event("turn/started", "turn" to json("id" to "first".json()))
        fixture.event("turn/completed", "turn" to json("id" to "first".json(), "status" to "completed".json()))
        runCurrent()
        first.await()
        val second = async { session.feature(SendsPrompts).send(Prompt.copy(id = RequestId("second"))) }
        runCurrent()
        fixture.wire.incoming.send(
            json("id" to checkNotNull(pending[0]["id"]), "error" to json("code" to JsonPrimitive(-1))),
        )
        runCurrent()
        assertIs<ActiveSessionState.Submitting>(session.state.value)
        assertFalse(second.isCompleted)
        fixture.wire.reply(pending[1], json("turn" to json("id" to "second".json())))
        second.await()
        assertIs<ActiveSessionState.Running>(session.state.value)
    }

    @Test
    fun `closed lease stops history observation and denies retained capabilities`() = runTest {
        val fixture = Fixture(this)
        val session = fixture.open()
        val history = session.feature(SessionHistory)
        val events = mutableListOf<SessionEvent>()
        val checkpoint = history.page().checkpoint
        val watching = launch { history.watch(checkpoint).collect { events += it } }
        runCurrent()
        session.close()
        runCurrent()
        assertTrue(watching.isCompleted)
        assertIs<FeatureAccess.Unavailable>(session.features.resolve(SendsPrompts))
        assertFailsWith<EngineException> { history.page() }
    }

    @Test
    fun `failed transport retires runtime and keeps ambiguous request correlation`() = runTest {
        val fixture = Fixture(this)
        fixture.onTurn = { fixture.wire.incoming.close() }
        val session = fixture.open()
        val failure = assertFailsWith<EngineException> { session.feature(SendsPrompts).send(Prompt) }
        assertEquals(EngineFailure.Request(RequestFailureReason.OutcomeUnknown, Prompt.id), failure.failure)
        runCurrent()
        assertTrue(fixture.runtime.isClosed)
    }

    @Test
    fun `invalid history checkpoint emits invalidation and ends lease subscription`() = runTest {
        val session = Fixture(this).open()
        val events = session.feature(SessionHistory).watch(HistoryCheckpoint("foreign:0")).toList()
        assertIs<SessionEvent.HistoryInvalidated>(events.single())
    }

    @Test
    fun `profile shutdown finishes pending submission and all observations synchronously`() = runTest {
        val fixture = Fixture(this)
        fixture.onTurn = { }
        val session = fixture.open()
        val history = session.feature(SessionHistory)
        val checkpoint = history.page().checkpoint
        val watching = launch { history.watch(checkpoint).collect { } }
        val sending = async {
            assertFailsWith<EngineException> { session.feature(SendsPrompts).send(Prompt) }
        }
        runCurrent()
        fixture.profile.close()
        runCurrent()
        assertEquals(EngineFailure.Request(RequestFailureReason.OutcomeUnknown, Prompt.id), sending.await().failure)
        assertTrue(watching.isCompleted)
        assertTrue(fixture.runtime.isClosed)
        assertIs<ActiveSessionState.Unavailable>(session.state.value)
    }
}
