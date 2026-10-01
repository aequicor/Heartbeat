@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCoverage
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ReconcilesSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResolvedResource
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceResolver
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionEvent
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ClaudeRuntimeTest {
    @Test
    fun `image-only stream input is accepted without an empty text block`() = runTest {
        val fixture = ClaudeFixture(backgroundScope)
        fixture.inputSupport = fixture.inputSupport.copy(imageMediaTypes = setOf("image/png"))
        fixture.resources = ResourceResolver { ResolvedResource("image.png", "image/png", byteArrayOf(1)) }
        val runtime = fixture.runtime()
        val session = runtime.create(CreateSessionRequest(testTarget))
        session.features.available(SendsPrompts).send(
            PromptRequest(
                RequestId("image-only"),
                listOf(
                    ContentPart.Image(ResourceRef("attachment:image", "image/png")),
                ),
            ),
        )
        runCurrent()
        val content = Json.parseToJsonElement(fixture.transport.inputs.last()).jsonObject["message"]!!
            .jsonObject["content"]!!.jsonArray
        assertEquals(1, content.size)
        assertEquals("image", content.single().jsonObject["type"]?.jsonPrimitive?.content)
        assertTrue("--input-format" in fixture.transport.calls.last())
        assertEquals(TurnOutcome.Completed, assertIs<ActiveSessionState.Ready>(session.state.value).lastTurn?.outcome)
        runtime.close()
    }

    @Test
    fun `selected effort is passed to the claude process`() = runTest {
        val fixture = ClaudeFixture(backgroundScope)
        val runtime = fixture.runtime()
        val session = runtime.create(CreateSessionRequest(testTarget))
        val send = async { session.features.available(SendsPrompts).send(prompt().copy(reasoningEffort = "xhigh")) }
        runCurrent()
        send.await()
        assertTrue("--effort=xhigh" in fixture.transport.calls.last())
        session.features.available(SendsPrompts).send(prompt("default"))
        runCurrent()
        assertTrue(fixture.transport.calls.last().none { it.startsWith("--effort") })
        runtime.close()
    }

    @Test
    fun `unknown reasoning effort is rejected before generation`() = runTest {
        val fixture = ClaudeFixture(backgroundScope)
        val runtime = fixture.runtime()
        val session = runtime.create(CreateSessionRequest(testTarget))
        val request = prompt().copy(reasoningEffort = "turbo")
        val error = assertFailsWith<EngineException> { session.features.available(SendsPrompts).send(request) }
        assertEquals(EngineFailure.Request(RequestFailureReason.Invalid, request.id), error.failure)
        assertIs<ActiveSessionState.Ready>(session.state.value)
        runtime.close()
    }

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
    fun `failed resume keeps resuming the native identity`() = runTest {
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
    fun `shutdown before process exit records one unknown outcome`() = runTest {
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
        assertEquals(TurnOutcome.Unknown, closed.lastTurn?.outcome)
        val events = history.watch(checkpoint).toList()
        assertEquals(1, events.filterIsInstance<SessionEvent.TurnFinished>().size)
    }

    @Test
    fun `a launch failure is not delivered and synchronize makes the session ready again`() = runTest {
        val fixture = ClaudeFixture(backgroundScope)
        val runtime = fixture.runtime()
        val session = runtime.create(CreateSessionRequest(testTarget))
        val history = session.features.available(SessionHistory)
        val checkpoint = history.page().checkpoint
        val missing = EngineFailure.Engine(EngineFailureReason.RequirementsNotMet)
        val generate = fixture.transport.generation
        fixture.transport.generation = { _, _ -> throw EngineException(missing) }
        val rejected = async {
            assertFailsWith<EngineException> { session.features.available(SendsPrompts).send(prompt()) }
        }
        runCurrent()
        assertEquals(missing, rejected.await().failure)
        assertIs<ActiveSessionState.Unavailable>(session.state.value)
        session.features.available(ReconcilesSession).synchronize()
        val failed = assertIs<ActiveSessionState.Ready>(session.state.value).lastTurn?.outcome
        assertEquals(TurnOutcome.Failed(missing), failed)

        fixture.transport.generation = generate
        val retried = async { session.features.available(SendsPrompts).send(prompt("retry")) }
        runCurrent()
        retried.await()
        assertTrue(fixture.transport.calls.last().any { it == "--session-id=${session.ref.nativeId}" })
        runtime.close()
        val finished = history.watch(checkpoint).toList().filterIsInstance<SessionEvent.TurnFinished>()
        assertEquals(listOf(TurnOutcome.Failed(missing), TurnOutcome.Completed), finished.map { it.outcome })
    }

    @Test
    fun `shutdown records the interrupted turn as the last one after an earlier turn`() = runTest {
        val fixture = ClaudeFixture(backgroundScope)
        val runtime = fixture.runtime()
        val session = runtime.create(CreateSessionRequest(testTarget))
        val first = async { session.features.available(SendsPrompts).send(prompt()) }
        runCurrent()
        first.await()
        fixture.transport.generation = { args, line ->
            val id = args.last().substringAfter('=')
            line(initFrame(id))
            line(assistantFrame(id))
            kotlinx.coroutines.awaitCancellation()
        }
        val second = async { session.features.available(SendsPrompts).send(prompt("second")) }
        runCurrent()
        val interrupted = second.await()
        runtime.close()
        val closed = assertIs<ActiveSessionState.Unavailable>(session.state.value)
        assertEquals(interrupted, closed.lastTurn?.id)
        assertEquals(TurnOutcome.Unknown, closed.lastTurn?.outcome)
    }

    @Test
    fun `an error result keeps its failure although the CLI exits nonzero`() = runTest {
        val fixture = ClaudeFixture(backgroundScope)
        fixture.transport.generation = { args, line ->
            val id = args.last().substringAfter('=')
            line(initFrame(id))
            line(errorResultFrame(id))
            1
        }
        val runtime = fixture.runtime()
        val session = runtime.create(CreateSessionRequest(testTarget))
        val send = async { session.features.available(SendsPrompts).send(prompt()) }
        runCurrent()
        send.await()
        val ready = assertIs<ActiveSessionState.Ready>(session.state.value)
        assertIs<TurnOutcome.Failed>(ready.lastTurn?.outcome)
        runtime.close()
    }

    @Test
    fun `a failure after a started process before any frame stays unresolved`() = runTest {
        val fixture = ClaudeFixture(backgroundScope)
        val runtime = fixture.runtime()
        val session = runtime.create(CreateSessionRequest(testTarget))
        fixture.transport.generation = { _, _ -> 1 }
        val rejected = async {
            assertFailsWith<EngineException> { session.features.available(SendsPrompts).send(prompt()) }
        }
        runCurrent()
        assertEquals(EngineFailure.Engine(EngineFailureReason.Crashed), rejected.await().failure)
        val cannotResume = assertFailsWith<EngineException> {
            session.features.available(ReconcilesSession).synchronize()
        }
        assertEquals(EngineFailure.Session(SessionFailureReason.NotResumable), cannotResume.failure)
        runtime.close()
    }

    @Test
    fun `a result followed by a nonzero exit is not reported as completed`() = runTest {
        val fixture = ClaudeFixture(backgroundScope)
        fixture.transport.generation = { args, line ->
            val id = args.last().substringAfter('=')
            line(initFrame(id))
            line(resultFrame(id))
            1
        }
        val runtime = fixture.runtime()
        val session = runtime.create(CreateSessionRequest(testTarget))
        val history = session.features.available(SessionHistory)
        val checkpoint = history.page().checkpoint
        val send = async { session.features.available(SendsPrompts).send(prompt()) }
        runCurrent()
        send.await()
        val unavailable = assertIs<ActiveSessionState.Unavailable>(session.state.value)
        assertEquals(TurnOutcome.Unknown, unavailable.lastTurn?.outcome)
        runtime.close()
        val finished = history.watch(checkpoint).toList().filterIsInstance<SessionEvent.TurnFinished>()
        assertEquals(listOf(TurnOutcome.Unknown), finished.map { it.outcome })
    }

    @Test
    fun `closing the runtime during submission leaves the prompt outcome unknown`() = runTest {
        val fixture = ClaudeFixture(backgroundScope)
        val started = CompletableDeferred<Unit>()
        fixture.transport.generation = { _, _ ->
            started.complete(Unit)
            kotlinx.coroutines.awaitCancellation()
        }
        val runtime = fixture.runtime()
        val session = runtime.create(CreateSessionRequest(testTarget))
        val send = async {
            assertFailsWith<EngineException> { session.features.available(SendsPrompts).send(prompt()) }
        }
        started.await()
        runtime.close()
        val failure = assertIs<EngineFailure.Request>(send.await().failure)
        assertEquals(RequestFailureReason.OutcomeUnknown, failure.reason)
    }

    @Test
    fun `result keeps the turn running until its process exits`() = runTest {
        val fixture = ClaudeFixture(backgroundScope)
        val exit = CompletableDeferred<Unit>()
        fixture.transport.generation = { args, line ->
            val id = args.last().substringAfter('=')
            line(initFrame(id))
            line(resultFrame(id))
            exit.await()
            0
        }
        val runtime = fixture.runtime()
        val session = runtime.create(CreateSessionRequest(testTarget))
        val first = async { session.features.available(SendsPrompts).send(prompt()) }
        runCurrent()
        first.await()
        assertIs<ActiveSessionState.Running>(session.state.value)
        exit.complete(Unit)
        runCurrent()
        assertIs<ActiveSessionState.Ready>(session.state.value)
        val second = async { session.features.available(SendsPrompts).send(prompt("second")) }
        runCurrent()
        second.await()
        assertTrue(fixture.transport.calls.last().any { it == "--resume=${session.ref.nativeId}" })
        runtime.close()
    }

    @Test
    fun `a result whose process never exits remains running and rejects a new prompt`() = runTest {
        val fixture = ClaudeFixture(backgroundScope)
        fixture.transport.generation = { args, line ->
            val id = args.last().substringAfter('=')
            line(initFrame(id))
            line(resultFrame(id))
            kotlinx.coroutines.awaitCancellation()
        }
        val runtime = fixture.runtime()
        val session = runtime.create(CreateSessionRequest(testTarget))
        val first = async { session.features.available(SendsPrompts).send(prompt()) }
        runCurrent()
        first.await()
        assertIs<ActiveSessionState.Running>(session.state.value)
        val busy = assertFailsWith<EngineException> {
            session.features.available(SendsPrompts).send(prompt("second"))
        }
        assertEquals(EngineFailure.Session(SessionFailureReason.Busy), busy.failure)
        runtime.close()
    }

    @Test
    fun `a crash after the init frame leaves the outcome unknown until synchronized`() = runTest {
        val fixture = ClaudeFixture(backgroundScope)
        val runtime = fixture.runtime()
        val session = runtime.create(CreateSessionRequest(testTarget))
        fixture.transport.generation = { args, line ->
            line(initFrame(args.last().substringAfter('=')))
            1
        }
        val send = async {
            assertFailsWith<EngineException> { session.features.available(SendsPrompts).send(prompt()) }
        }
        runCurrent()
        assertEquals(RequestFailureReason.OutcomeUnknown, assertIs<EngineFailure.Request>(send.await().failure).reason)
        assertIs<ActiveSessionState.Unavailable>(session.state.value)
        session.features.available(ReconcilesSession).synchronize()
        val ready = assertIs<ActiveSessionState.Ready>(session.state.value)
        assertEquals(TurnOutcome.Unknown, ready.lastTurn?.outcome)
        runtime.close()
    }

    @Test
    fun `a foreign session frame makes delivery ambiguous`() = runTest {
        val fixture = ClaudeFixture(backgroundScope)
        fixture.transport.generation = { _, line ->
            line(initFrame("forked"))
            0
        }
        val runtime = fixture.runtime()
        val session = runtime.create(CreateSessionRequest(testTarget))
        val send = async {
            assertFailsWith<EngineException> { session.features.available(SendsPrompts).send(prompt()) }
        }
        runCurrent()
        assertEquals(RequestFailureReason.OutcomeUnknown, assertIs<EngineFailure.Request>(send.await().failure).reason)
        assertIs<ActiveSessionState.Unavailable>(session.state.value)
        val cannotResume = assertFailsWith<EngineException> {
            session.features.available(ReconcilesSession).synchronize()
        }
        assertEquals(EngineFailure.Session(SessionFailureReason.NotResumable), cannotResume.failure)
        assertIs<ActiveSessionState.Unavailable>(session.state.value)
        runtime.close()
    }

    @Test
    fun `a result followed by transport failure never publishes completion`() = runTest {
        val fixture = ClaudeFixture(backgroundScope)
        val afterResult = CompletableDeferred<Unit>()
        fixture.transport.generation = { args, line ->
            val id = args.last().substringAfter('=')
            line(initFrame(id))
            line(resultFrame(id))
            afterResult.complete(Unit)
            throw EngineException(EngineFailure.Engine(EngineFailureReason.Unavailable))
        }
        val runtime = fixture.runtime()
        val session = runtime.create(CreateSessionRequest(testTarget))
        val history = session.features.available(SessionHistory)
        val checkpoint = history.page().checkpoint
        val send = async { session.features.available(SendsPrompts).send(prompt()) }
        runCurrent()
        send.await()
        afterResult.await()
        runCurrent()
        val unavailable = assertIs<ActiveSessionState.Unavailable>(session.state.value)
        assertEquals(TurnOutcome.Unknown, unavailable.lastTurn?.outcome)
        session.features.available(ReconcilesSession).synchronize()
        assertEquals(TurnOutcome.Unknown, assertIs<ActiveSessionState.Ready>(session.state.value).lastTurn?.outcome)
        runtime.close()
        val finished = history.watch(checkpoint).toList().filterIsInstance<SessionEvent.TurnFinished>()
        assertEquals(listOf(TurnOutcome.Unknown), finished.map { it.outcome })
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

    @Test
    fun `released sessions beyond the retention bound are dropped, recent ones stay resumable`() = runTest {
        val fixture = ClaudeFixture(backgroundScope)
        val runtime = fixture.runtime()
        val refs = List(MAX_RELEASED + 3) {
            val session = runtime.create(CreateSessionRequest(testTarget))
            session.close()
            session.ref
        }
        assertEquals(MAX_RELEASED, runtime.retainedSessions)
        val dropped = assertFailsWith<EngineException> {
            runtime.attach(refs.first(), ResumeSessionRequest(testTarget))
        }
        assertEquals(EngineFailure.Session(SessionFailureReason.NotResumable), dropped.failure)
        val reopened = runtime.attach(refs.last(), ResumeSessionRequest(testTarget))
        assertEquals(refs.last(), reopened.ref)
        runtime.close()
        assertEquals(0, runtime.retainedSessions)
    }

    @Test
    fun `leased sessions are never dropped`() = runTest {
        val fixture = ClaudeFixture(backgroundScope)
        val runtime = fixture.runtime()
        val held = List(MAX_RELEASED + 3) { runtime.create(CreateSessionRequest(testTarget)) }
        assertEquals(held.size, runtime.retainedSessions)
        held.first().close()
        assertEquals(held.size, runtime.retainedSessions)
        runtime.close()
    }
}
