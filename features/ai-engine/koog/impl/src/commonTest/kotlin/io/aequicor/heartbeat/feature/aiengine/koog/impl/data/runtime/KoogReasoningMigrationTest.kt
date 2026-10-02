package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.EndpointOrigin
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogProvider
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class KoogReasoningMigrationTest {
    @Test
    fun `saved effort can resume without rediscovering legacy levels`() = runTest {
        val fixture = KoogTestFixture(this)
        val initial = fixture.session()
        val ref = initial.ref
        initial.close()
        fixture.runtime().close()
        fixture.records.values[ref] = fixture.records.values.getValue(ref).copy(reasoningEffort = "on")
        fixture.reasoningStore.state = KoogReasoningState(mapOf("ollama/test-model" to KoogToggleLevels))
        val resumed = fixture.runtime().attach(ref, ResumeSessionRequest(fixture.target))
        resumed.features.require(SendsPrompts).send(fixture.request())
        fixture.executor.complete("Answer")
        runCurrent()
        assertEquals(TurnOutcome.Completed, assertIs<ActiveSessionState.Ready>(resumed.state.value).lastTurn?.outcome)
        assertFalse("ollama/test-model" in fixture.reasoningStore.state.levels)
    }

    @Test
    fun `fixed routes retain legacy levels and rejection until the api confirms support`() = runTest {
        val fixture = KoogTestFixture(this)
        val provider = KoogProvider.AlibabaQwen
        val model = "vendor/model"
        val oldKey = "${provider.id.value}/$model"
        fixture.reasoningStore.state = KoogReasoningState(mapOf(oldKey to listOf("high")), setOf(oldKey))
        assertEquals(emptyList(), fixture.reasoning.levels(provider, provider.origin, model))
        assertFalse(oldKey in fixture.reasoningStore.state.rejected)
        fixture.reasoning.discover(provider, provider.origin, listOf(model), mapOf(model to listOf("low")))
        assertEquals(listOf("low"), fixture.reasoning.levels(provider, provider.origin, model))
    }

    @Test
    fun `explicit route levels override legacy levels and rejection`() = runTest {
        val fixture = KoogTestFixture(this)
        val provider = KoogProvider.AlibabaQwen
        val oldKey = "${provider.id.value}/model"
        val newKey = "${provider.id.value}/${provider.origin.value}/model"
        fixture.reasoningStore.state = KoogReasoningState(
            mapOf(oldKey to listOf("high"), newKey to listOf("low")),
            setOf(oldKey),
        )
        assertEquals(listOf("low"), fixture.reasoning.levels(provider, provider.origin, "model"))
    }

    @Test
    fun `legacy compatible entries cannot be assigned to a server`() = runTest {
        val fixture = KoogTestFixture(this)
        val provider = KoogProvider.OpenAICompatible
        fixture.reasoningStore.state = KoogReasoningState(mapOf("${provider.id.value}/model" to listOf("high")))
        listOf(provider.origin, EndpointOrigin("https://example.com")).forEach { origin ->
            assertEquals(emptyList(), fixture.reasoning.levels(provider, origin, "model"))
        }
    }
}
