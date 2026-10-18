package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.AccessFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ReconcilesSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ClaudeRuntimeFailuresTest {
    @Test
    fun `a crash before any frame resumes the conversation when the history holds it`() = runTest {
        val fixture = ClaudeFixture(backgroundScope)
        val runtime = fixture.runtime()
        val session = runtime.create(CreateSessionRequest(testTarget))
        fixture.transport.generation = { _, _ -> 1 }
        val rejected = async {
            assertFailsWith<EngineException> { session.features.available(SendsPrompts).send(prompt()) }
        }
        runCurrent()
        rejected.await()
        fixture.transport.transcript = true

        session.features.available(ReconcilesSession).synchronize()

        val ready = assertIs<ActiveSessionState.Ready>(session.state.value)
        assertEquals(TurnOutcome.Unknown, ready.lastTurn?.outcome)
        fixture.transport.generation = FakeClaudeTransport().generation
        session.features.available(SendsPrompts).send(prompt("again"))
        runCurrent()
        assertTrue(fixture.transport.calls.last().any { it.startsWith("--resume=") })
        runtime.close()
    }

    @Test
    fun `a crash before any frame lets the next prompt start the conversation when the history has none`() = runTest {
        val fixture = ClaudeFixture(backgroundScope)
        val runtime = fixture.runtime()
        val session = runtime.create(CreateSessionRequest(testTarget))
        fixture.transport.generation = { _, _ -> 1 }
        val rejected = async {
            assertFailsWith<EngineException> { session.features.available(SendsPrompts).send(prompt()) }
        }
        runCurrent()
        rejected.await()
        fixture.transport.transcript = false

        session.features.available(ReconcilesSession).synchronize()

        assertEquals(TurnOutcome.Unknown, assertIs<ActiveSessionState.Ready>(session.state.value).lastTurn?.outcome)
        fixture.transport.generation = FakeClaudeTransport().generation
        session.features.available(SendsPrompts).send(prompt("again"))
        runCurrent()
        val arguments = fixture.transport.calls.last()
        assertTrue(arguments.any { it.startsWith("--session-id=") })
        assertTrue(arguments.none { it.startsWith("--resume=") })
        assertEquals(TurnOutcome.Completed, assertIs<ActiveSessionState.Ready>(session.state.value).lastTurn?.outcome)
        runtime.close()
    }

    @Test
    fun `any failure before the first frame is decided by the history, not only a crash`() = runTest {
        val failures = listOf(
            EngineFailure.Engine(EngineFailureReason.Unavailable),
            EngineFailure.Transport(TransportFailureReason.Timeout),
        )
        for (failure in failures) {
            for (isCreated in listOf(true, false)) {
                val fixture = ClaudeFixture(backgroundScope)
                val runtime = fixture.runtime()
                val session = runtime.create(CreateSessionRequest(testTarget))
                fixture.transport.generation = { _, _ -> throw EngineException(failure) }
                val sender = session.features.available(SendsPrompts)
                assertEquals(failure, assertFailsWith<EngineException> { sender.send(prompt()) }.failure)
                fixture.transport.transcript = isCreated

                session.features.available(ReconcilesSession).synchronize()

                assertIs<ActiveSessionState.Ready>(session.state.value)
                fixture.transport.generation = FakeClaudeTransport().generation
                session.features.available(SendsPrompts).send(prompt("again"))
                runCurrent()
                val arguments = fixture.transport.calls.last()
                val prefix = if (isCreated) "--resume=" else "--session-id="
                assertTrue(arguments.any { it.startsWith(prefix) }, "failure=$failure isCreated=$isCreated")
                runtime.close()
            }
        }
    }

    @Test
    fun `a conversation the CLI already holds stays confirmed while a later turn starts`() = runTest {
        val fixture = ClaudeFixture(backgroundScope)
        val runtime = fixture.runtime()
        val session = runtime.create(CreateSessionRequest(testTarget))
        session.features.available(SendsPrompts).send(prompt("first"))
        runCurrent()
        assertEquals(ClaudeLaunch.Confirmed, fixture.catalog.find(session.ref)?.launch)

        // A restart in the window before the second turn's first frame must not reopen the question.
        var launchWhileRunning: ClaudeLaunch? = null
        val defaultGeneration = fixture.transport.generation
        fixture.transport.generation = { args, line ->
            launchWhileRunning = fixture.catalog.find(session.ref)?.launch
            defaultGeneration(args, line)
        }
        session.features.available(SendsPrompts).send(prompt("second"))
        runCurrent()

        assertEquals(ClaudeLaunch.Confirmed, launchWhileRunning)
        runtime.close()
    }

    @Test
    fun `after a restart an attempted launch without a conversation in the history starts it again`() = runTest {
        val fixture = ClaudeFixture(backgroundScope)
        val first = fixture.runtime()
        val session = first.create(CreateSessionRequest(testTarget))
        fixture.transport.generation = { _, _ -> 1 }
        val rejected = async {
            assertFailsWith<EngineException> { session.features.available(SendsPrompts).send(prompt()) }
        }
        runCurrent()
        rejected.await()
        val ref = session.ref
        first.close()

        fixture.transport.generation = FakeClaudeTransport().generation
        fixture.transport.transcript = false
        val second = fixture.runtime()
        val reopened = second.attach(ref, ResumeSessionRequest(testTarget))
        assertIs<ActiveSessionState.Ready>(reopened.state.value)
        reopened.features.available(SendsPrompts).send(prompt("after the restart"))
        runCurrent()

        val arguments = fixture.transport.calls.last()
        assertTrue(arguments.any { it.startsWith("--session-id=") })
        assertTrue(arguments.none { it.startsWith("--resume=") })
        second.close()
    }

    @Test
    fun `after a restart an attempted launch with an unreadable history keeps resuming`() = runTest {
        val fixture = ClaudeFixture(backgroundScope)
        val first = fixture.runtime()
        val session = first.create(CreateSessionRequest(testTarget))
        fixture.transport.generation = { _, _ -> 1 }
        val rejected = async {
            assertFailsWith<EngineException> { session.features.available(SendsPrompts).send(prompt()) }
        }
        runCurrent()
        rejected.await()
        val ref = session.ref
        first.close()

        fixture.transport.generation = FakeClaudeTransport().generation
        val second = fixture.runtime()
        val reopened = second.attach(ref, ResumeSessionRequest(testTarget))
        reopened.features.available(SendsPrompts).send(prompt("after the restart"))
        runCurrent()

        assertTrue(fixture.transport.calls.last().any { it.startsWith("--resume=") })
        second.close()
    }

    @Test
    fun `an error result is classified only by an established API cause`() = runTest {
        val cases = listOf(
            Triple("model_not_found", "404", EngineFailure.Access(AccessFailureReason.ModelAccessDenied)),
            Triple(null, "404", EngineFailure.Unknown()),
            Triple(null, "529", EngineFailure.Transport(TransportFailureReason.ServiceUnavailable)),
            Triple(null, "401", EngineFailure.Unknown()),
            Triple(null, "429", EngineFailure.Unknown()),
            Triple(null, null, EngineFailure.Unknown()),
        )
        for ((error, status, expected) in cases) {
            val fixture = ClaudeFixture(backgroundScope)
            fixture.transport.generation = { args, line ->
                val id = args.last().substringAfter('=')
                line(initFrame(id))
                line(
                    """{"type":"assistant","session_id":"$id",${error?.let { "\"error\":\"$it\"," }.orEmpty()}
                    "message":{"model":"claude-actual","content":[{"type":"text","text":"Reason"}]}}""",
                )
                line(
                    """{"type":"result","session_id":"$id","subtype":"success","is_error":true,
                    ${status?.let { "\"api_error_status\":$it," }.orEmpty()}"result":"Reason"}""",
                )
                1
            }
            val runtime = fixture.runtime()
            val session = runtime.create(CreateSessionRequest(testTarget))
            val send = async { session.features.available(SendsPrompts).send(prompt()) }
            runCurrent()
            send.await()
            val outcome = assertIs<ActiveSessionState.Ready>(session.state.value).lastTurn?.outcome
            assertEquals(TurnOutcome.Failed(expected), outcome, "error=$error status=$status")
            runtime.close()
        }
    }

    @Test
    fun `an effort the model does not advertise is rejected before generation`() = runTest {
        val fixture = ClaudeFixture(backgroundScope)
        fixture.efforts = { model -> if (model == testTarget.model) listOf("low", "high") else emptyList() }
        val runtime = fixture.runtime()
        val session = runtime.create(CreateSessionRequest(testTarget))
        val sender = session.features.available(SendsPrompts)

        val request = prompt().copy(reasoningEffort = "max")
        val error = assertFailsWith<EngineException> { sender.send(request) }
        assertEquals(EngineFailure.Request(RequestFailureReason.Invalid, request.id), error.failure)
        assertTrue(fixture.transport.calls.none { args -> args.any { it.startsWith("--effort") } })
        assertIs<ActiveSessionState.Ready>(session.state.value)

        sender.send(prompt("advertised").copy(reasoningEffort = "high"))
        runCurrent()
        assertTrue("--effort=high" in fixture.transport.calls.last())

        val plain = runtime.create(CreateSessionRequest(testTarget.copy(model = ModelId("no-efforts"))))
        val unsupported = assertFailsWith<EngineException> {
            plain.features.available(SendsPrompts).send(prompt("none").copy(reasoningEffort = "low"))
        }
        assertEquals(RequestFailureReason.Invalid, assertIs<EngineFailure.Request>(unsupported.failure).reason)
        runtime.close()
    }
}
