package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailureReason
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthScope
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceInfo
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.EndpointOrigin
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.ProviderId
import io.aequicor.heartbeat.feature.aiengine.codex.api.CodexEngine
import io.aequicor.heartbeat.feature.aiengine.codex.api.CodexLocalConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAvailability
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class CodexRoutesTest {
    private val userSource = AuthSourceId("user-codex")

    private val source = AuthSource.CliLogin(
        AuthSourceInfo(userSource, "Codex", AuthRevision.Unknown),
        AuthScope(ProviderId("openai"), EndpointOrigin("https://api.openai.com")),
        CodexEngine.AuthOwner,
        CodexLocalConfiguration().location,
    )

    private val transport = object : CodexTransport {
        override suspend fun open(): CodexWire = error("transport reached")

        override suspend fun available(): EngineAvailability = EngineAvailability.Available
    }

    @Test
    fun `routed non-default source creates runtimes until it is unbound`() = runTest {
        val factory = CodexFactory(Fixture(this).environment, transport)
        val identity = RuntimeIdentity(CodexEngine.Id, userSource, AuthRevision.Unknown)
        assertMismatch { factory.createRuntime(identity) }

        factory.bind(EngineBindingId("binding"), source)
        factory.bind(EngineBindingId("binding"), source)
        val reached = assertFailsWith<IllegalStateException> { factory.createRuntime(identity) }
        assertEquals("transport reached", reached.message)

        factory.unbind(EngineBindingId("binding"))
        assertMismatch { factory.createRuntime(identity) }
    }

    private suspend fun assertMismatch(block: suspend () -> Unit) {
        val failure = assertFailsWith<EngineException> { block() }.failure
        assertEquals(AuthFailureReason.AuthMismatch, assertIs<EngineFailure.Authentication>(failure).reason.reason)
    }
}
