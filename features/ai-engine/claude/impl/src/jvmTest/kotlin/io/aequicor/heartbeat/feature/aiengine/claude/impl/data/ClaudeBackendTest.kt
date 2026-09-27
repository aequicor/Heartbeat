package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import io.aequicor.heartbeat.feature.aiengine.claude.api.ClaudeEngine
import io.aequicor.heartbeat.feature.aiengine.facade.api.AccessFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAvailability
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ClaudeBackendTest {
    private val context = EngineContext(ClaudeEngine.Id, testTarget.binding)

    private fun ClaudeFixture.backend(scope: CoroutineScope) =
        JvmClaudeBackend(transport, account, toggles, TestProfileHandle(scope))

    @Test
    fun `hung login probe fails as a transport timeout, not as cancellation`() = runTest {
        val fixture = ClaudeFixture(backgroundScope)
        fixture.transport.beforeRun = { awaitCancellation() }
        val error = assertFailsWith<EngineException> { fixture.account.inspect() }
        assertEquals(EngineFailure.Transport(TransportFailureReason.Timeout), error.failure)
    }

    @Test
    fun `disabled toggle stops login inspection before the CLI runs`() = runTest {
        val fixture = ClaudeFixture(backgroundScope)
        fixture.toggles.enabled = false
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

        fixture.toggles.enabled = false
        val calls = fixture.transport.calls.size
        assertEquals(
            EngineAvailability.Unavailable(EngineFailure.Access(AccessFailureReason.OperationNotAllowed)),
            backend.checkRequirements(),
        )
        assertEquals(calls, fixture.transport.calls.size)
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

        fixture.transport.generation = { _, line ->
            line(modelsFrame("other"))
            0
        }
        val error = assertFailsWith<EngineException> { backend.discoverModels(source, context) }
        assertEquals(EngineFailure.Transport(TransportFailureReason.ProtocolViolation), error.failure)
    }
}

private fun modelsFrame(request: String) = """{"type":"control_response","response":{"request_id":"$request",
    "subtype":"success","response":{"models":[{"value":"sonnet","displayName":"Sonnet"},{"value":"opus"}]}}}"""
    .replace("\n", "")
