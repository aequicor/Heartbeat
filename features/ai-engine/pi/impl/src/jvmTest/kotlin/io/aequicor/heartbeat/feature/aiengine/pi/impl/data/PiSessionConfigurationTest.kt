package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.AccessFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.ChangesSessionConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionConfigurationChange
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionContextUsage
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PiSessionConfigurationTest {
    @Test
    fun `live trust changes later tool calls and leaves outstanding permissions untouched`() = runTest {
        val fixture = fixture()
        fixture.runningTurn()
        fixture.connection.event(approval("already-pending"))
        val pending = assertIs<ActiveSessionState.AwaitingUserAction>(fixture.session.state.value).requests
        val capability = assertIs<FeatureAccess.Available<ChangesSessionConfiguration>>(
            fixture.session.features.resolve(ChangesSessionConfiguration),
        ).feature

        capability.apply("trust-full", SessionConfigurationChange.Trust(TrustLevel.Full))
        assertEquals(pending, assertIs<ActiveSessionState.AwaitingUserAction>(fixture.session.state.value).requests)
        assertEquals(emptyList(), fixture.connection.sent)
        fixture.connection.event(approval("later-allowed"))
        assertEquals(listOf(answer("later-allowed", "confirmed", true)), fixture.connection.sent)

        capability.apply("trust-ask", SessionConfigurationChange.Trust(TrustLevel.Ask))
        fixture.connection.event(approval("later-pending"))
        val waiting = assertIs<ActiveSessionState.AwaitingUserAction>(fixture.session.state.value)
        assertEquals(listOf("already-pending", "later-pending"), waiting.requests.map { it.id.value })
        assertEquals(TrustLevel.Ask, capability.configuration.value.trust)
        fixture.session.shutdown()
    }

    @Test
    fun `live model and effort are confirmed by Pi while the original turn keeps running`() = runTest {
        val fixture = fixture()
        val turn = fixture.runningTurn()
        fixture.connection.modelAck.complete(JsonObject(emptyMap()))
        val changed = fixture.session.apply("model", SessionConfigurationChange.Model(ModelId("anthropic/other")))
        assertEquals(ModelId("anthropic/other"), changed.model)
        val configured = fixture.session.apply("effort", SessionConfigurationChange.Effort("high"))
        assertEquals("high", configured.reasoningEffort)
        assertEquals(turn, assertIs<ActiveSessionState.Running>(fixture.session.state.value).turn.id)
        assertEquals(1, fixture.connection.commands.count { it == "prompt" })
        assertFalse("abort" in fixture.connection.commands)
        assertEquals(
            listOf("set_model", "get_state", "set_thinking_level", "get_state"),
            fixture.connection.commands.takeLast(4),
        )
        fixture.connection.event(record("""{"type":"agent_settled"}"""))
        assertEquals(configured, fixture.session.configuration.value)
        fixture.session.shutdown()
    }

    @Test
    fun `a rejected live model keeps the actual configuration and running turn`() = runTest {
        val fixture = fixture()
        val turn = fixture.runningTurn()
        val previous = fixture.session.configuration.value
        val failure = assertFailsWith<EngineException> {
            fixture.session.apply("missing", SessionConfigurationChange.Model(ModelId("anthropic/missing")))
        }
        assertIs<EngineFailure.Request>(failure.failure)
        assertEquals(previous, fixture.session.configuration.value)
        assertEquals(turn, assertIs<ActiveSessionState.Running>(fixture.session.state.value).turn.id)
        val denied = assertFailsWith<EngineException> {
            fixture.session.apply("foreign", SessionConfigurationChange.Model(ModelId("openai/model")))
        }
        assertEquals(EngineFailure.Access(AccessFailureReason.ModelAccessDenied), denied.failure)
        assertEquals(previous, fixture.session.configuration.value)
        fixture.session.shutdown()
    }

    @Test
    fun `live model refreshes usage capacity while effort preserves the latest measurement`() = runTest {
        val fixture = fixture(usageEnabled = true)
        fixture.runningTurn()
        val usage = assertIs<FeatureAccess.Available<SessionContextUsage>>(
            fixture.session.features.resolve(SessionContextUsage),
        ).feature
        fixture.connection.event(usageMessage("test"))
        assertEquals(1000L, usage.state.value?.capacityTokens)
        val measured = usage.state.value
        fixture.session.apply("effort", SessionConfigurationChange.Effort("high"))
        assertEquals(measured, usage.state.value)

        fixture.connection.contextWindow = 2000L
        fixture.connection.modelAck.complete(JsonObject(emptyMap()))
        fixture.session.apply("model", SessionConfigurationChange.Model(ModelId("anthropic/other")))
        assertNull(usage.state.value)
        fixture.connection.event(usageMessage("other"))
        assertEquals(2000L, usage.state.value?.capacityTokens)
        assertEquals(42L, usage.state.value?.usedTokens)
        fixture.session.shutdown()
    }

    @Test
    fun `a lost model confirmation preserves the last known selection until explicit synchronization`() = runTest {
        val fixture = fixture()
        val turn = fixture.runningTurn()
        val previous = fixture.session.configuration.value
        val transport = EngineFailure.Transport(TransportFailureReason.Timeout)
        fixture.connection.modelAck.complete(JsonObject(emptyMap()))
        fixture.connection.stateFailure = EngineException(transport)

        val failure = assertFailsWith<EngineException> {
            fixture.session.apply("model", SessionConfigurationChange.Model(ModelId("anthropic/other")))
        }
        assertEquals(transport, failure.failure)
        assertEquals("other", fixture.connection.model)
        assertEquals(previous, fixture.session.configuration.value)
        assertEquals(transport, assertIs<ActiveSessionState.Unavailable>(fixture.session.state.value).failure)
        val commands = fixture.connection.commands.toList()
        assertFailsWith<EngineException> {
            fixture.session.apply("unavailable", SessionConfigurationChange.Trust(TrustLevel.Full))
        }
        assertEquals(commands, fixture.connection.commands)

        fixture.connection.stateFailure = null
        fixture.connection.isStreaming = true
        fixture.session.synchronize()
        assertEquals(ModelId("anthropic/other"), fixture.session.configuration.value.model)
        assertEquals(turn, assertIs<ActiveSessionState.Running>(fixture.session.state.value).turn.id)
        assertEquals(1, fixture.connection.commands.count { it == "prompt" })
        assertEquals(2, fixture.connection.commands.count { it == "set_model" })
        assertFalse("abort" in fixture.connection.commands)
        assertEquals(listOf("set_model", "get_state", "get_state"), fixture.connection.commands.takeLast(3))
        fixture.session.shutdown()
    }

    @Test
    fun `live effort reports the level Pi clamps and a null change restores the native default`() = runTest {
        val fixture = fixture()
        fixture.runningTurn()
        fixture.connection.thinkingClamp = "low"
        val clamped = fixture.session.apply("clamp", SessionConfigurationChange.Effort("xhigh"))
        assertEquals("low", clamped.reasoningEffort)
        fixture.connection.thinkingClamp = null
        val reset = fixture.session.apply("reset", SessionConfigurationChange.Effort(null))
        assertEquals("medium", reset.reasoningEffort)
        fixture.session.shutdown()
    }

    @Test
    fun `invalid and refused live effort leave the native configuration unchanged`() = runTest {
        val fixture = fixture()
        fixture.runningTurn()
        val previous = fixture.session.configuration.value
        val commands = fixture.connection.commands.toList()
        assertFailsWith<EngineException> {
            fixture.session.apply("invalid", SessionConfigurationChange.Effort("turbo"))
        }
        assertEquals(commands, fixture.connection.commands)
        fixture.connection.thinkingFailure = EngineException(EngineFailure.Request(RequestFailureReason.Invalid))
        assertFailsWith<EngineException> {
            fixture.session.apply("refused", SessionConfigurationChange.Effort("high"))
        }
        assertEquals(previous, fixture.session.configuration.value)
        assertIs<ActiveSessionState.Running>(fixture.session.state.value)
        fixture.session.shutdown()
    }

    @Test
    fun `prompt overrides publish confirmed effort and trust without resetting them on completion`() = runTest {
        val fixture = fixture()
        fixture.connection.promptAck.complete(JsonObject(emptyMap()))
        fixture.session.send(prompt("configured").copy(reasoningEffort = "high", trust = TrustLevel.AutoEdits))
        val configured = fixture.session.configuration.value
        assertEquals("high", configured.reasoningEffort)
        assertEquals(TrustLevel.AutoEdits, configured.trust)
        fixture.connection.event(record("""{"type":"agent_settled"}"""))
        assertEquals(configured, fixture.session.configuration.value)
        fixture.session.shutdown()
    }

    private fun usageMessage(model: String) = record(
        """{"type":"message_end","message":{"role":"assistant","model":"$model",
            "provider":"anthropic","stopReason":"stop","usage":{"totalTokens":42}}}""",
    )
}
