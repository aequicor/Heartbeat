package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionContextUsage
import io.aequicor.heartbeat.feature.aiengine.pi.api.PiActiveSession
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

class PiSessionUsageTest {
    @Test
    fun `new and resumed Pi sessions expose required telemetry enabled by default`() = runTest {
        val fixture = runtimeFixture(this)
        val created: PiActiveSession = fixture.runtime.create(CreateSessionRequest(RuntimeTarget))
        val access = assertIs<FeatureAccess.Available<SessionContextUsage>>(
            created.features.resolve(SessionContextUsage),
        )
        assertSame(created.contextUsage, access.feature)
        fixture.processes.connections.single().event(measurement("anthropic", "test"))
        assertEquals(610L, created.contextUsage.state.value?.usedTokens)
        created.close()

        val resumed: PiActiveSession = fixture.runtime.attach(RuntimeRef, RuntimeRequest)
        assertSame(
            resumed.contextUsage,
            assertIs<FeatureAccess.Available<SessionContextUsage>>(
                resumed.features.resolve(SessionContextUsage),
            ).feature,
        )
        fixture.processes.connections.last().event(measurement("anthropic", "test"))
        assertEquals(1000L, resumed.contextUsage.state.value?.capacityTokens)
        fixture.runtime.close()
    }

    @Test
    fun `compatible route publishes an explicit catalog window instead of hiding its measurement`() = runTest {
        val provider = PiProvider.OpenAiCompatible.id
        val fixture = fixture(this, targetModel = ModelId("$provider/test")) { _, connection ->
            connection.provider = provider
            connection.contextWindows = mapOf("$provider/test" to 200000L, "$provider/other" to 128000L)
            connection.contextWindow = 200000L
        }
        fixture.connection.event(measurement(provider, "test"))
        assertEquals(610L, fixture.session.contextUsage.state.value?.usedTokens)
        assertEquals(200000L, fixture.session.contextUsage.state.value?.capacityTokens)
        fixture.session.shutdown()
    }

    @Test
    fun `compatible fallback capacity and another model explicit window remain unknown`() = runTest {
        val provider = PiProvider.OpenAiCompatible.id
        val fixture = fixture(this, targetModel = ModelId("$provider/test")) { _, connection ->
            connection.provider = provider
            connection.contextWindow = 128000L
            connection.contextWindows = mapOf("$provider/other" to 200000L)
        }
        fixture.connection.event(measurement(provider, "test"))
        assertNull(fixture.session.contextUsage.state.value)
        fixture.session.shutdown()
    }

    @Test
    fun `process recovery uses the new process catalog and ignores old generation observations`() = runTest {
        val provider = PiProvider.OpenAiCompatible.id
        val fixture = fixture(this, targetModel = ModelId("$provider/test")) { generation, connection ->
            connection.provider = provider
            connection.contextWindows = mapOf("$provider/test" to if (generation == 0) 200000L else 128000L)
        }
        fixture.connection.event(measurement(provider, "test"))
        assertEquals(200000L, fixture.session.contextUsage.state.value?.capacityTokens)
        fixture.connection.isOpen = false
        fixture.connection.failed(EngineFailure.Engine(EngineFailureReason.Crashed))
        fixture.session.synchronize()
        assertNull(fixture.session.contextUsage.state.value)
        fixture.connection.event(measurement(provider, "test"))
        assertNull(fixture.session.contextUsage.state.value)
        fixture.connections.last().event(measurement(provider, "test"))
        assertEquals(128000L, fixture.session.contextUsage.state.value?.capacityTokens)
        fixture.session.shutdown()
    }

    @Test
    fun `explicitly disabled telemetry remains hidden`() = runTest {
        val fixture = fixture(this, isUsageEnabled = false)
        fixture.connection.event(measurement("anthropic", "test"))
        assertNull(fixture.session.contextUsage.state.value)
        fixture.session.shutdown()
    }

    private fun measurement(provider: String, model: String) = record(
        """{"type":"message_end","message":{"role":"assistant","provider":"$provider","model":"$model",
        "stopReason":"stop","usage":{"totalTokens":610}}}""",
    )
}
