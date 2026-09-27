@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCoverage
import io.aequicor.heartbeat.feature.aiengine.facade.api.ReconcilesSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionEvent
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ClaudeRuntimeTest {
    @Test
    fun `a turn completes with actual model and partial observed history`() = runTest {
        val fixture = ClaudeFixture(backgroundScope)
        val runtime = fixture.runtime()
        val session = runtime.create(CreateSessionRequest(testTarget))
        val send = async { session.features.available(SendsPrompts).send(prompt()) }
        runCurrent()
        val turn = assertIs<ActiveSessionState.Ready>(session.state.value).lastTurn!!
        assertEquals(send.await(), turn.id)
        assertEquals(TurnOutcome.Completed, turn.outcome)
        assertEquals("claude-actual", turn.target.model.value)
        val history = session.features.available(SessionHistory).page()
        assertEquals(2, history.items.size)
        assertEquals(HistoryCoverage.Partial, history.coverage)
        runtime.close()
    }

    @Test
    fun `closing a lease never cancels an accepted turn`() = runTest {
        val fixture = ClaudeFixture(backgroundScope)
        val finish = CompletableDeferred<Unit>()
        fixture.transport.generation = { args, line ->
            val id = args.last().substringAfter('=')
            line(initFrame(id))
            line(assistantFrame(id))
            finish.await()
            line(resultFrame(id))
            0
        }
        val runtime = fixture.runtime()
        val first = runtime.create(CreateSessionRequest(testTarget))
        val send = async { first.features.available(SendsPrompts).send(prompt()) }
        runCurrent()
        send.await()
        first.close()
        val second = runtime.attach(first.ref, ResumeSessionRequest(testTarget))
        assertIs<ActiveSessionState.Running>(second.state.value)
        assertFailsWith<EngineException> { second.features.available(SendsPrompts).send(prompt("duplicate")) }
        finish.complete(Unit)
        runCurrent()
        assertIs<ActiveSessionState.Ready>(second.state.value)
        assertIs<ActiveSessionState.Closed>(first.state.value)
        runtime.close()
    }

    @Test
    fun `caller cancellation before acceptance leaves generation owned by runtime`() = runTest {
        val fixture = ClaudeFixture(backgroundScope)
        val accept = CompletableDeferred<Unit>()
        fixture.transport.generation = { args, line ->
            val id = args.last().substringAfter('=')
            line(initFrame(id))
            accept.await()
            line(assistantFrame(id))
            line(resultFrame(id))
            0
        }
        val runtime = fixture.runtime()
        val session = runtime.create(CreateSessionRequest(testTarget))
        val caller = async { session.features.available(SendsPrompts).send(prompt()) }
        runCurrent()
        assertIs<ActiveSessionState.Submitting>(session.state.value)
        caller.cancelAndJoin()
        accept.complete(Unit)
        runCurrent()
        assertIs<ActiveSessionState.Ready>(session.state.value)
        runtime.close()
    }

    @Test
    fun `parent cancellation invalidates idle handles`() = runTest {
        val parent = Job()
        val fixture = ClaudeFixture(CoroutineScope(coroutineContext + parent))
        val runtime = fixture.runtime()
        val session = runtime.create(CreateSessionRequest(testTarget))
        parent.cancelAndJoin()
        assertIs<ActiveSessionState.Unavailable>(session.state.value)
        assertTrue(runtime.isClosed)
    }

    @Test
    fun `changed CLI account and disabled toggle prevent submission`() = runTest {
        val fixture = ClaudeFixture(backgroundScope)
        val runtime = fixture.runtime()
        val session = runtime.create(CreateSessionRequest(testTarget))
        fixture.transport.account = "someone-else@example.test"
        val changed = assertFailsWith<EngineException> { session.features.available(SendsPrompts).send(prompt()) }
        assertEquals(
            AuthFailureReason.SourceChanged,
            assertIs<EngineFailure.Authentication>(changed.failure).reason.reason,
        )
        fixture.toggles.enabled = false
        assertFailsWith<EngineException> { session.features.available(SendsPrompts).send(prompt()) }
        assertTrue(fixture.transport.calls.all { it == listOf("auth", "status") })
        runtime.close()
    }

    @Test
    fun `failed resume retains native identity after reconciliation`() = runTest {
        val fixture = ClaudeFixture(backgroundScope)
        val runtime = fixture.runtime()
        val session = runtime.create(CreateSessionRequest(testTarget))
        val first = async { session.features.available(SendsPrompts).send(prompt()) }
        runCurrent()
        first.await()
        fixture.transport.generation = { _, _ -> 1 }
        val failed = async {
            assertFailsWith<EngineException> { session.features.available(SendsPrompts).send(prompt("second")) }
        }
        runCurrent()
        failed.await()
        session.features.available(ReconcilesSession).synchronize()
        val retried = async {
            assertFailsWith<EngineException> { session.features.available(SendsPrompts).send(prompt("third")) }
        }
        runCurrent()
        retried.await()
        assertTrue(fixture.transport.calls.last().any { it == "--resume=${session.ref.nativeId}" })
        runtime.close()
    }

    @Test
    fun `shutdown after result preserves terminal outcome without duplicate events`() = runTest {
        val fixture = ClaudeFixture(backgroundScope)
        fixture.transport.generation = { args, line ->
            val id = args.last().substringAfter('=')
            line(initFrame(id))
            line(resultFrame(id))
            kotlinx.coroutines.awaitCancellation()
        }
        val runtime = fixture.runtime()
        val session = runtime.create(CreateSessionRequest(testTarget))
        val history = session.features.available(SessionHistory)
        val checkpoint = history.page().checkpoint
        val send = async { session.features.available(SendsPrompts).send(prompt()) }
        runCurrent()
        send.await()
        runtime.close()
        val closed = assertIs<ActiveSessionState.Unavailable>(session.state.value)
        assertEquals(TurnOutcome.Completed, closed.lastTurn?.outcome)
        val events = history.watch(checkpoint).toList()
        assertEquals(1, events.filterIsInstance<SessionEvent.TurnFinished>().size)
    }

    @Test
    fun `stored session resumes only the same runtime route`() = runTest {
        val fixture = ClaudeFixture(backgroundScope)
        val runtime = fixture.runtime()
        val session = runtime.create(CreateSessionRequest(testTarget))
        session.close()
        val stored = runtime.stored(session.ref)
        val resumed = stored.features.available(ResumesSessions).resume(ResumeSessionRequest(testTarget))
        assertEquals(session.ref, resumed.ref)
        runtime.close()
    }
}
