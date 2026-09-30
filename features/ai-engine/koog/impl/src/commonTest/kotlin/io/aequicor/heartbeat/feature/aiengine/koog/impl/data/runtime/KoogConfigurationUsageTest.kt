package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthScope
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSecretId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.ChangesSessionConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionConfigurationChange
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionContextUsage
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogConnection
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class KoogConfigurationUsageTest {
    @Test
    fun `usage belongs to its request model when a new model is selected before the response ends`() = runTest {
        val fixture = KoogTestFixture(this)
        val provider = KoogProvider.OpenAI
        val connection = KoogConnection(
            fixture.binding,
            AuthSource.ManagedKey(
                fixture.source.info,
                AuthScope(provider.id, provider.origin),
                AuthSecretId("test-key"),
            ),
        )
        fixture.connections.put(connection)
        fixture.usageEnabled.value = true
        fixture.availableModels = listOf("test-model", "next-model")
        fixture.access.contextWindows.record(
            connection,
            listOf(
                LLModel(provider.llmProvider, "test-model", emptyList(), contextLength = 1000),
                LLModel(provider.llmProvider, "next-model", emptyList(), contextLength = 2000),
            ),
        )
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.beforeSearch = {
            started.complete(Unit)
            release.await()
        }
        val session = fixture.session()
        val configuration = session.features.require(ChangesSessionConfiguration)
        val usage = session.features.require(SessionContextUsage).state
        session.features.require(SendsPrompts).send(fixture.request())
        configuration.apply("model", SessionConfigurationChange.Model(ModelId("next-model")))
        fixture.executor.frames.trySend(StreamFrame.ToolCallComplete("search", "web_search", """{"query":"x"}""", 0))
        fixture.executor.frames.trySend(usageEnd(300, 20))
        started.await()
        assertEquals(ModelId("next-model"), configuration.configuration.value.model)
        assertEquals(listOf("test-model"), fixture.executor.models.map { it.id })
        assertEquals(320, usage.value?.usedTokens)
        assertEquals(1000, usage.value?.capacityTokens)
        release.complete(Unit)
        runCurrent()
        assertEquals(listOf("test-model", "next-model"), fixture.executor.models.map { it.id })
        fixture.executor.frames.trySend(usageEnd(400, 30))
        runCurrent()
        assertEquals(430, usage.value?.usedTokens)
        assertEquals(2000, usage.value?.capacityTokens)
        assertEquals(TurnOutcome.Completed, assertIs<ActiveSessionState.Ready>(session.state.value).lastTurn?.outcome)
    }
}

private fun usageEnd(input: Int, output: Int) = StreamFrame.End(
    "stop",
    ResponseMetaInfo(Instant.fromEpochSeconds(0), inputTokensCount = input, outputTokensCount = output),
)
