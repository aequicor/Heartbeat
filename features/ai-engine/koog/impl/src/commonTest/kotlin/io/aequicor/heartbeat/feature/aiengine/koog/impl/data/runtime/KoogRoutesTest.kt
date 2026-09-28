package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailureReason
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.AccessFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogEngineId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class KoogRoutesTest {
    @Test
    fun `binding an unchanged route performs no write and unbind removes only known routes`() = runTest {
        val fixture = KoogTestFixture(this)
        fixture.adapter.bind(fixture.binding.id, fixture.source)
        assertEquals(0, fixture.connections.puts)

        val other = EngineBindingId("second")
        fixture.adapter.bind(other, fixture.source)
        fixture.adapter.bind(other, fixture.source)
        assertEquals(1, fixture.connections.puts)

        fixture.adapter.unbind(other)
        fixture.adapter.unbind(other)
        assertTrue(fixture.connections.values.none { it.binding.id == other })
    }

    @Test
    fun `rejected source metadata is an authentication mismatch and a disabled engine is not written`() = runTest {
        val fixture = KoogTestFixture(this)
        fixture.connections.rejection = IllegalArgumentException("metadata changed")
        val mismatch = assertFailsWith<EngineException> {
            fixture.adapter.bind(EngineBindingId("second"), fixture.source)
        }
        assertEquals(
            AuthFailureReason.AuthMismatch,
            assertIs<EngineFailure.Authentication>(mismatch.failure).reason.reason,
        )

        fixture.connections.rejection = null
        fixture.isEnabled = false
        val disabled = assertFailsWith<EngineException> {
            fixture.adapter.bind(EngineBindingId("third"), fixture.source)
        }
        assertEquals(EngineFailure.Access(AccessFailureReason.OperationNotAllowed), disabled.failure)
        assertEquals(0, fixture.connections.puts)
    }

    @Test
    fun `keyless local route without a known revision still creates runtimes`() = runTest {
        val fixture = KoogTestFixture(this)
        val local = fixture.source.copy(
            info = fixture.source.info.copy(id = AuthSourceId("ollama"), revision = AuthRevision.Unknown),
        )
        fixture.adapter.bind(EngineBindingId("ollama"), local)
        val runtime = fixture.adapter.createRuntime(RuntimeIdentity(KoogEngineId, local.info.id, AuthRevision.Unknown))
        assertIs<KoogRuntime>(runtime)
    }
}
