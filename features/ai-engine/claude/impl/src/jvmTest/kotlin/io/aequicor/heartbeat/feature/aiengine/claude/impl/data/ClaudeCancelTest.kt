@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.CancelsTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionContextUsage
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionEvent
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Stop of a turn whose CLI accepted the prompt but has not emitted a stream-json event yet. */
class ClaudeCancelTest {
    @Test
    fun `cancel before the first CLI event stops the process and ends the turn as cancelled`() = runTest {
        val fixture = ClaudeFixture(backgroundScope)
        val started = CompletableDeferred<Unit>()
        var isStopped = false
        fixture.transport.generation = { _, _ ->
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                isStopped = true
            }
        }
        val runtime = fixture.runtime()
        val session = runtime.create(CreateSessionRequest(testTarget))
        val send = async {
            assertFailsWith<EngineException> { session.features.available(SendsPrompts).send(prompt()) }
        }
        started.await()
        val submitting = assertIs<ActiveSessionState.Submitting>(session.state.value)

        session.features.available(CancelsTurns).cancel(submitting.turn.id)

        assertTrue(isStopped, "the native process is stopped")
        val ready = assertIs<ActiveSessionState.Ready>(session.state.value)
        assertEquals(submitting.turn.id, ready.lastTurn?.id)
        assertEquals(TurnOutcome.Cancelled, ready.lastTurn?.outcome)
        assertEquals(TurnOutcome.Cancelled, fixture.catalog.find(session.ref)?.lastTurn?.outcome)
        send.await()
        // Cancel stays idempotent once the turn ended.
        session.features.available(CancelsTurns).cancel(submitting.turn.id)
        assertIs<ActiveSessionState.Ready>(session.state.value)
        runtime.close()
    }

    @Test
    fun `output racing with cancel before the first event is never delivered`() = runTest {
        val fixture = ClaudeFixture(backgroundScope)
        val started = CompletableDeferred<Unit>()
        fixture.transport.generation = { args, line ->
            val id = args.last().substringAfter('=')
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                // Frames the CLI had already written when the stop was requested. The real transport stops reading
                // once the job is cancelled; frames received between the stop request and that point (and duplex
                // frames) still reach receive.
                withContext(NonCancellable) {
                    line(initFrame(id))
                    line(usageFrame(id))
                    line(resultFrame(id))
                }
            }
        }
        val runtime = fixture.runtime()
        val session = runtime.create(CreateSessionRequest(testTarget))
        val history = session.features.available(SessionHistory)
        val checkpoint = history.page().checkpoint
        val send = async {
            assertFailsWith<EngineException> { session.features.available(SendsPrompts).send(prompt()) }
        }
        started.await()
        val turn = assertIs<ActiveSessionState.Submitting>(session.state.value).turn

        session.features.available(CancelsTurns).cancel(turn.id)
        send.await()

        val ready = assertIs<ActiveSessionState.Ready>(session.state.value)
        assertEquals(TurnOutcome.Cancelled, ready.lastTurn?.outcome)
        val assistant = history.page().items.filterIsInstance<SessionItem.Message>()
            .filter { it.role == MessageRole.Assistant }
        assertEquals(emptyList(), assistant)
        // The tokens the cancelled turn spent are still counted.
        assertEquals(15L, session.features.available(SessionContextUsage).state.value?.usedTokens)
        runtime.close()
        val finished = history.watch(checkpoint).toList().filterIsInstance<SessionEvent.TurnFinished>()
        assertEquals(listOf(TurnOutcome.Cancelled), finished.map { it.outcome })
    }

    @Test
    fun `cancel after the first event still stops the running turn`() = runTest {
        val fixture = ClaudeFixture(backgroundScope)
        fixture.transport.generation = { args, line ->
            val id = args.last().substringAfter('=')
            line(initFrame(id))
            line(assistantFrame(id))
            awaitCancellation()
        }
        val runtime = fixture.runtime()
        val session = runtime.create(CreateSessionRequest(testTarget))
        val send = async { session.features.available(SendsPrompts).send(prompt()) }
        runCurrent()
        val turn = send.await()
        assertIs<ActiveSessionState.Running>(session.state.value)

        session.features.available(CancelsTurns).cancel(turn)

        assertEquals(TurnOutcome.Cancelled, assertIs<ActiveSessionState.Ready>(session.state.value).lastTurn?.outcome)
        runtime.close()
    }

    @Test
    fun `cancel before the turn operation starts still ends the turn as cancelled`() = runTest {
        val fixture = ClaudeFixture(backgroundScope)
        var isInvoked = false
        fixture.transport.generation = { _, _ ->
            isInvoked = true
            awaitCancellation()
        }
        val runtime = fixture.runtime()
        val session = runtime.create(CreateSessionRequest(testTarget))
        // The prompt is submitted, but its operation is only queued on the session scope.
        val send = async(start = CoroutineStart.UNDISPATCHED) {
            assertFailsWith<EngineException> { session.features.available(SendsPrompts).send(prompt()) }
        }
        val turn = assertIs<ActiveSessionState.Submitting>(session.state.value).turn

        session.features.available(CancelsTurns).cancel(turn.id)

        val ready = assertIs<ActiveSessionState.Ready>(session.state.value)
        assertEquals(turn.id, ready.lastTurn?.id)
        assertEquals(TurnOutcome.Cancelled, ready.lastTurn?.outcome)
        assertEquals(TurnOutcome.Cancelled, fixture.catalog.find(session.ref)?.lastTurn?.outcome)
        send.await()
        assertFalse(isInvoked, "the CLI never received the prompt")
        runtime.close()
    }

    @Test
    fun `the next prompt after a cancel before the first event runs normally`() = runTest {
        val fixture = ClaudeFixture(backgroundScope)
        val started = CompletableDeferred<Unit>()
        val answer = fixture.transport.generation
        fixture.transport.generation = { _, _ ->
            started.complete(Unit)
            awaitCancellation()
        }
        val runtime = fixture.runtime()
        val session = runtime.create(CreateSessionRequest(testTarget))
        val send = async {
            assertFailsWith<EngineException> { session.features.available(SendsPrompts).send(prompt()) }
        }
        started.await()
        val cancelled = assertIs<ActiveSessionState.Submitting>(session.state.value).turn
        session.features.available(CancelsTurns).cancel(cancelled.id)
        send.await()
        fixture.transport.generation = answer

        val next = session.features.available(SendsPrompts).send(prompt("next"))
        runCurrent()

        val ready = assertIs<ActiveSessionState.Ready>(session.state.value)
        assertEquals(next, ready.lastTurn?.id)
        assertEquals(TurnOutcome.Completed, ready.lastTurn?.outcome)
        runtime.close()
    }
}

private fun usageFrame(id: String) = """{"type":"assistant","session_id":"$id","message":{"model":"claude-actual",
    "content":[{"type":"text","text":"Answer"}],"usage":{"input_tokens":10,"output_tokens":5}}}"""
