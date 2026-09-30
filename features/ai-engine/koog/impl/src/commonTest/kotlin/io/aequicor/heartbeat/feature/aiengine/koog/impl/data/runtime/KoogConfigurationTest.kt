package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import ai.koog.http.client.KoogHttpClientException
import ai.koog.prompt.executor.ollama.client.OllamaParams
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.streaming.StreamFrame
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.ChangesSessionConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionConfigurationChange
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionConfigurationUpdate
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogProvider
import io.aequicor.heartbeat.feature.searchengine.api.SearchResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class KoogConfigurationTest {
    @Test
    fun `model and effort change after the in-flight tool without losing its result`() = runTest {
        val fixture = KoogTestFixture(this)
        fixture.availableModels = listOf("test-model", "next-model")
        fixture.toolsByModel = mapOf("next-model" to false)
        fixture.reasoning.discover(
            KoogProvider.Ollama,
            fixture.availableModels,
            fixture.availableModels.associateWith { KoogToggleLevels },
        )
        fixture.searchResults = listOf(SearchResult("https://example.com", "Example", "Result"))
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.beforeSearch = {
            started.complete(Unit)
            release.await()
        }
        val session = fixture.session()
        val changes = session.features.require(ChangesSessionConfiguration)
        session.features.require(SendsPrompts).send(fixture.request())
        fixture.executor.frames.trySend(StreamFrame.TextComplete("Before tool"))
        fixture.executor.frames.trySend(
            StreamFrame.ToolCallComplete("search", "web_search", """{"query":"topic"}""", 0),
        )
        fixture.executor.frames.trySend(StreamFrame.End("tool_calls"))
        started.await()
        changes.apply("model", SessionConfigurationChange.Model(ModelId("next-model")))
        changes.apply("effort", SessionConfigurationChange.Effort("on"))
        assertEquals(listOf("test-model"), fixture.executor.models.map { it.id })
        assertEquals(LLMParams(), fixture.executor.prompts.first().params)
        release.complete(Unit)
        runCurrent()
        assertEquals(listOf("test-model", "next-model"), fixture.executor.models.map { it.id })
        assertEquals(OllamaParams(think = true), fixture.executor.prompts.last().params)
        assertEquals(emptyList(), fixture.executor.tools.last())
        val prompt = fixture.executor.prompts.last()
        assertTrue(prompt.messages.any { it is Message.User })
        assertTrue(prompt.messages.any { it is Message.Assistant && it.textContent() == "Before tool" })
        val parts = prompt.messages.flatMap { it.parts }
        assertTrue(parts.any { it is MessagePart.Tool.Call && it.id == "search" })
        assertTrue(parts.any { it is MessagePart.Tool.Result && it.id == "search" && "Result" in it.output })
        fixture.executor.complete()
        runCurrent()
        assertEquals(TurnOutcome.Completed, assertIs<ActiveSessionState.Ready>(session.state.value).lastTurn?.outcome)
        assertEquals(ModelId("next-model"), fixture.records.get(session.ref)?.model)
        session.close()
        val resumed = fixture.runtime().attach(
            session.ref,
            ResumeSessionRequest(fixture.target.copy(model = ModelId("next-model"))),
        )
        assertEquals("on", resumed.features.require(ChangesSessionConfiguration).configuration.value.reasoningEffort)
    }

    @Test
    fun `live effort leaves the ongoing stream intact and applies to the next request`() = runTest {
        val fixture = KoogTestFixture(this)
        fixture.reasoning.discover(KoogProvider.Ollama, listOf("test-model"), mapOf("test-model" to KoogToggleLevels))
        val session = fixture.session()
        session.features.require(SendsPrompts).send(fixture.request())
        runCurrent()
        session.features.require(ChangesSessionConfiguration).apply("effort", SessionConfigurationChange.Effort("on"))
        assertEquals(LLMParams(), fixture.executor.prompts.single().params)
        fixture.executor.frames.trySend(
            StreamFrame.ToolCallComplete("search", "web_search", """{"query":"topic"}""", 0),
        )
        fixture.executor.frames.trySend(StreamFrame.End("tool_calls"))
        runCurrent()
        assertEquals(OllamaParams(think = true), fixture.executor.prompts.last().params)
        fixture.executor.complete()
        runCurrent()
    }

    @Test
    fun `unsupported trust and invalid effort preserve the previous configuration`() = runTest {
        val fixture = KoogTestFixture(this)
        fixture.reasoning.discover(KoogProvider.Ollama, listOf("test-model"), mapOf("test-model" to KoogToggleLevels))
        val session = fixture.session()
        val changes = session.features.require(ChangesSessionConfiguration)
        changes.apply("valid", SessionConfigurationChange.Effort("on"))
        val invalid = assertFailsWith<EngineException> {
            changes.apply("invalid", SessionConfigurationChange.Effort("high"))
        }
        assertEquals(EngineFailure.Request(RequestFailureReason.Invalid), invalid.failure)
        val trust = assertFailsWith<EngineException> {
            changes.apply("trust", SessionConfigurationChange.Trust(TrustLevel.Full))
        }
        assertEquals(EngineFailure.Engine(EngineFailureReason.UnsupportedCapability), trust.failure)
        assertEquals("on", changes.configuration.value.reasoningEffort)
        assertEquals(null, changes.configuration.value.trust)
        assertEquals(0, fixture.opens)
    }

    @Test
    fun `provider rejection corrects actual effort and reports the original operation`() = runTest {
        val fixture = KoogTestFixture(this)
        fixture.reasoning.discover(KoogProvider.Ollama, listOf("test-model"), mapOf("test-model" to KoogToggleLevels))
        val session = fixture.session()
        val changes = session.features.require(ChangesSessionConfiguration)
        changes.apply("effort-op", SessionConfigurationChange.Effort("on"))
        val correction = async(start = CoroutineStart.UNDISPATCHED) { changes.updates.first() }
        fixture.executor.nextFailure = KoogHttpClientException(statusCode = 400, errorBody = "unknown field think")
        session.features.require(SendsPrompts).send(fixture.request())
        runCurrent()
        fixture.executor.complete()
        runCurrent()
        assertEquals("effort-op", correction.await().operationId)
        assertEquals(null, changes.configuration.value.reasoningEffort)
        assertEquals(null, fixture.records.get(session.ref)?.reasoningEffort)
        assertEquals(LLMParams(), fixture.executor.prompts.last().params)
        assertEquals(TurnOutcome.Completed, assertIs<ActiveSessionState.Ready>(session.state.value).lastTurn?.outcome)
    }

    @Test
    fun `late rejection reports the original operation without rolling back a newer effort selection`() = runTest {
        val fixture = KoogTestFixture(this)
        fixture.reasoning.discover(KoogProvider.Ollama, listOf("test-model"), mapOf("test-model" to KoogToggleLevels))
        val session = fixture.session()
        val changes = session.features.require(ChangesSessionConfiguration)
        val corrections = mutableListOf<SessionConfigurationUpdate>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { changes.updates.collect { corrections += it } }
        changes.apply("old-op", SessionConfigurationChange.Effort("on"))
        fixture.executor.nextFailure = KoogHttpClientException(statusCode = 400, errorBody = "unknown field think")
        session.features.require(SendsPrompts).send(fixture.request())
        runCurrent()
        assertEquals(2, fixture.executor.prompts.size)
        changes.apply("new-op", SessionConfigurationChange.Effort("off"))
        fixture.executor.complete()
        runCurrent()
        assertEquals("off", changes.configuration.value.reasoningEffort)
        assertEquals("off", fixture.records.get(session.ref)?.reasoningEffort)
        val correction = corrections.single()
        assertEquals("old-op", correction.operationId)
        assertEquals(EngineFailure.Request(RequestFailureReason.Invalid), correction.failure)
        assertEquals(changes.configuration.value, correction.configuration)
        assertEquals(KoogToggleLevels, fixture.reasoning.levels(KoogProvider.Ollama, "test-model"))
        assertEquals(TurnOutcome.Completed, assertIs<ActiveSessionState.Ready>(session.state.value).lastTurn?.outcome)
    }
}
