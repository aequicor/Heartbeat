package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailure
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailureReason
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.claude.api.ClaudeEngine
import io.aequicor.heartbeat.feature.aiengine.facade.api.AccessFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAvailability
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.LifecycleFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ClaudeBackendTest {
    private val context = EngineContext(ClaudeEngine.Id, testTarget.binding)

    private fun ClaudeFixture.backend(scope: CoroutineScope) =
        JvmClaudeBackend(transport, account, toggles, TestProfileHandle(scope), catalog)

    private suspend fun ClaudeFixture.identity() =
        RuntimeIdentity(ClaudeEngine.Id, ClaudeEngine.AuthSource, account.inspect().check.revision)

    @Test
    fun `saving a different CLI home keeps the existing runtime on its pinned account`() = runTest {
        val fixture = ClaudeFixture(backgroundScope)
        val retained = FakeClaudeTransport()
        fixture.transport.pinnedTransport = retained
        val identity = fixture.identity()
        val backend = fixture.backend(backgroundScope)
        val first = backend.createRuntime(identity)
        fixture.transport.account = "new-home@example.test"
        assertSame(first, backend.createRuntime(identity))
        val session = first.features.available(io.aequicor.heartbeat.feature.aiengine.facade.api.CreatesSessions)
            .create(CreateSessionRequest(testTarget))
        session.features.available(SendsPrompts).send(prompt())
        runCurrent()
        assertIs<ActiveSessionState.Ready>(session.state.value)
        assertTrue(retained.calls.any { args -> args.any { it.startsWith("--session-id=") } })
        first.close()
        fixture.transport.pinnedTransport = FakeClaudeTransport().also { it.account = fixture.transport.account }
        val second = backend.createRuntime(fixture.identity())
        assertNotSame(first, second)
        second.close()
    }

    @Test
    fun `disabled toggle stops login inspection before the CLI runs`() = runTest {
        val fixture = ClaudeFixture(backgroundScope)
        fixture.toggles.isEnabled = false
        val error = assertFailsWith<EngineException> { fixture.backend(backgroundScope).inspect() }
        assertEquals(EngineFailure.Access(AccessFailureReason.OperationNotAllowed), error.failure)
        assertTrue(fixture.transport.calls.isEmpty())
    }

    @Test
    fun `installation probe reports failures as availability`() = runTest {
        val fixture = ClaudeFixture(backgroundScope)
        val backend = fixture.backend(backgroundScope)
        fixture.transport.generation = { _, line ->
            line("2.1.0 (Claude Code)")
            0
        }
        assertEquals(EngineAvailability.Available, backend.checkRequirements())

        val missing = EngineFailure.Engine(EngineFailureReason.RequirementsNotMet)
        fixture.transport.generation = { _, _ -> throw EngineException(missing) }
        assertEquals(EngineAvailability.Unavailable(missing), backend.checkRequirements())

        fixture.transport.beforeRun = { awaitCancellation() }
        assertEquals(
            EngineAvailability.Unavailable(EngineFailure.Transport(TransportFailureReason.Timeout)),
            backend.checkRequirements(),
        )

        fixture.toggles.isEnabled = false
        val calls = fixture.transport.calls.size
        assertEquals(
            EngineAvailability.Unavailable(EngineFailure.Access(AccessFailureReason.OperationNotAllowed)),
            backend.checkRequirements(),
        )
        assertEquals(calls, fixture.transport.calls.size)
    }

    @Test
    fun `runtime is pooled per identity and retired when the account revision changes`() = runTest {
        val fixture = ClaudeFixture(backgroundScope)
        val backend = fixture.backend(backgroundScope)
        val identity = fixture.identity()
        val first = assertIs<ClaudeRuntime>(backend.createRuntime(identity))
        assertSame(first, backend.createRuntime(identity))
        val session = first.create(CreateSessionRequest(testTarget))
        assertEquals(session.ref, backend.session(session.ref).summary.value.ref)

        fixture.transport.account = "someone-else@example.test"
        val second = assertIs<ClaudeRuntime>(backend.createRuntime(fixture.identity()))
        assertNotSame(first, second)
        assertTrue(first.isClosed)
        val retired = assertIs<ActiveSessionState.Unavailable>(session.state.value)
        assertEquals(
            AuthFailureReason.SourceChanged,
            assertIs<EngineFailure.Authentication>(retired.failure).reason.reason,
        )
        val rejected = assertFailsWith<EngineException> { first.create(CreateSessionRequest(testTarget)) }
        assertEquals(retired.failure, rejected.failure)
        val command = assertFailsWith<EngineException> { session.features.available(SendsPrompts).send(prompt()) }
        assertEquals(retired.failure, command.failure)
        val unavailable = assertFailsWith<EngineException> { backend.session(session.ref) }
        assertEquals(retired.failure, unavailable.failure)
        second.close()
    }

    @Test
    fun `bound non-default source creates runtimes until it is unbound`() = runTest {
        val fixture = ClaudeFixture(backgroundScope)
        val backend = fixture.backend(backgroundScope)
        val login = fixture.account.inspect().source
        val routed = login.copy(info = login.info.copy(id = AuthSourceId("user-claude")))
        val identity = fixture.identity().copy(source = routed.info.id)
        val binding = EngineBindingId("user-binding")
        assertFailsWith<EngineException> { backend.createRuntime(identity) }

        backend.bind(binding, routed)
        backend.bind(binding, routed)
        assertIs<ClaudeRuntime>(backend.createRuntime(identity)).close()

        backend.unbind(binding)
        val error = assertFailsWith<EngineException> { backend.createRuntime(identity) }
        assertEquals(
            AuthFailureReason.AuthMismatch,
            assertIs<EngineFailure.Authentication>(error.failure).reason.reason,
        )
    }

    @Test
    fun `runtime creation rejects a foreign identity, a stale revision and a closed profile`() = runTest {
        val fixture = ClaudeFixture(backgroundScope)
        val backend = fixture.backend(backgroundScope)
        val identity = fixture.identity()
        val mismatch = EngineFailure.Authentication(
            AuthFailure(AuthFailureReason.AuthMismatch, ClaudeEngine.AuthSource),
        )
        val foreign = assertFailsWith<EngineException> {
            backend.createRuntime(identity.copy(engine = EngineId("other")))
        }
        assertEquals(mismatch, foreign.failure)

        fixture.transport.account = "someone-else@example.test"
        val stale = assertFailsWith<EngineException> { backend.createRuntime(identity) }
        assertEquals(
            AuthFailureReason.SourceChanged,
            assertIs<EngineFailure.Authentication>(stale.failure).reason.reason,
        )
        val unknown = SessionRef(ClaudeEngine.Id, ClaudeEngine.SessionSource, "none")
        val missing = assertFailsWith<EngineException> { backend.session(unknown) }
        assertEquals(EngineFailure.Session(SessionFailureReason.NotFound), missing.failure)

        val profile = Job()
        val closed = fixture.backend(CoroutineScope(coroutineContext + profile))
        profile.cancel()
        val error = assertFailsWith<EngineException> { closed.createRuntime(fixture.identity()) }
        assertEquals(EngineFailure.Lifecycle(LifecycleFailureReason.ProfileClosed), error.failure)
    }

    @Test
    fun `models are parsed from the initialize control response`() = runTest {
        val fixture = ClaudeFixture(backgroundScope)
        val backend = fixture.backend(backgroundScope)
        val source = fixture.account.inspect().source
        fixture.transport.generation = { _, line ->
            line(modelsFrame("models"))
            0
        }
        val models = backend.discoverModels(source, context)
        assertEquals(listOf("sonnet", "opus"), models.map { it.target.model.value })
        assertEquals("Sonnet", models.first().title)
        assertEquals(listOf("low", "high"), models.first().reasoningEfforts)
        assertEquals(emptyList(), models.last().reasoningEfforts)

        fixture.transport.generation = { _, line ->
            line(modelsFrame("other"))
            0
        }
        val error = assertFailsWith<EngineException> { backend.discoverModels(source, context) }
        assertEquals(EngineFailure.Transport(TransportFailureReason.ProtocolViolation), error.failure)
    }
}

private fun modelsFrame(request: String) = """{"type":"control_response","response":{"request_id":"$request",
    "subtype":"success","response":{"models":[{"value":"sonnet","displayName":"Sonnet",
    "supportsEffort":true,"supportedEffortLevels":["low","high","future"]},{"value":"opus"}]}}}"""
    .replace("\n", "")
