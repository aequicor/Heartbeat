package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthScope
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSecretId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionContextUsage
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineContext
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogConnection
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class KoogUsageTest {
    @Test
    fun `vendor model ids on other endpoints cannot borrow vendor catalog capacities`() = runTest {
        for (provider in KoogProvider.entries) {
            val f = KoogTestFixture(this)
            val connection = if (provider == KoogProvider.Ollama) {
                f.connections.values.single()
            } else {
                f.cloudRoute(provider)
            }
            f.usageEnabled.value = true
            f.modelContextLength = 128000
            val advertised = LLModel(provider.llmProvider, "gpt-4o", emptyList(), contextLength = 128000)
            f.access.contextWindows.record(connection, listOf(advertised))
            var reads = 0
            val client = KoogClient(f.executor) {
                reads++
                listOf(advertised)
            }
            val isFixedVendor = provider == KoogProvider.OpenAI || provider == KoogProvider.Anthropic
            val expected = if (isFixedVendor) 128000L else null
            assertEquals(expected, f.access.contextWindows.resolve(connection, "gpt-4o", client))
            assertEquals(0, reads)
            val models = f.adapter.discoverModels(connection.source, EngineContext(f.binding.engine, f.binding.id))
            assertEquals(expected, models.single().contextLimitTokens)
        }
    }

    @Test
    fun `last tool round replaces native occupancy and zero remains known`() = runTest {
        val f = KoogTestFixture(this)
        f.cloudRoute()
        f.usageEnabled.value = true
        f.modelContextLength = 1000
        var discoveries = 0
        f.beforeModels = { discoveries++ }
        val session = f.session()
        val usage = session.features.require(SessionContextUsage).state
        session.features.require(SendsPrompts).send(f.request())
        f.executor.frames.trySend(StreamFrame.ToolCallComplete("tool", "web_search", """{"query":"x"}""", 0))
        f.executor.frames.trySend(end(300, 20))
        runCurrent()
        assertEquals(320, usage.value?.usedTokens)
        f.executor.frames.trySend(end(400, 30))
        runCurrent()
        assertEquals(430, usage.value?.usedTokens)
        assertEquals(1000, usage.value?.capacityTokens)
        assertEquals(1, discoveries)
        assertIs<ActiveSessionState.Ready>(session.state.value)

        session.features.require(SendsPrompts).send(f.request("zero"))
        f.executor.frames.trySend(end(0, 0))
        runCurrent()
        assertEquals(0, usage.value?.usedTokens)
        assertEquals(1, discoveries)
    }

    @Test
    fun `disabled telemetry performs no model query and switching off clears the observation`() = runTest {
        val f = KoogTestFixture(this)
        f.cloudRoute()
        f.modelContextLength = 1000
        var discoveries = 0
        f.beforeModels = { discoveries++ }
        val session = f.session()
        val usage = session.features.require(SessionContextUsage).state
        session.features.require(SendsPrompts).send(f.request())
        f.executor.frames.trySend(end(50, 10))
        runCurrent()
        assertNull(usage.value)
        assertEquals(0, discoveries)
        f.usageEnabled.value = true
        runCurrent()
        session.features.require(SendsPrompts).send(f.request("enabled"))
        f.executor.frames.trySend(end(50, 10))
        runCurrent()
        assertEquals(60, usage.value?.usedTokens)
        f.usageEnabled.value = false
        runCurrent()
        assertNull(usage.value)
        f.usageEnabled.value = true
        runCurrent()
        assertNull(usage.value)
    }

    @Test
    fun `unknown metadata or capacity remains hidden and Ollama fallback is never a capacity`() = runTest {
        for (cloud in listOf(false, true)) {
            val f = KoogTestFixture(this)
            if (cloud) f.cloudRoute()
            f.usageEnabled.value = true
            f.modelContextLength = if (cloud) null else 4096
            val session = f.session()
            session.features.require(SendsPrompts).send(f.request())
            f.executor.frames.trySend(end(50, 10))
            runCurrent()
            assertNull(session.features.require(SessionContextUsage).state.value)
            assertIs<ActiveSessionState.Ready>(session.state.value)
        }
    }

    @Test
    fun `capacity cache isolates credential revisions and failed telemetry does not fail the turn`() = runTest {
        val f = KoogTestFixture(this)
        val connection = f.cloudRoute()
        f.usageEnabled.value = true
        f.modelContextLength = 1000
        val client = f.access.open(connection)
        assertEquals(1000, f.access.contextWindows.resolve(connection, "test-model", client))
        val changed = connection.copy(
            source = (connection.source as AuthSource.ManagedKey).copy(
                info = connection.source.info.copy(revision = AuthRevision.Known("2")),
            ),
        )
        f.beforeModels = { error("no catalog") }
        assertNull(f.access.contextWindows.resolve(changed, "test-model", client))
        val session = f.session()
        session.features.require(SendsPrompts).send(f.request())
        f.executor.frames.trySend(StreamFrame.End("stop"))
        runCurrent()
        assertNull(session.features.require(SessionContextUsage).state.value)
        assertIs<ActiveSessionState.Ready>(session.state.value)
    }
}

private suspend fun KoogTestFixture.cloudRoute(provider: KoogProvider = KoogProvider.OpenAI): KoogConnection {
    val cloud = AuthSource.ManagedKey(
        source.info,
        AuthScope(provider.id, provider.origin),
        AuthSecretId("test-key"),
    )
    return KoogConnection(binding, cloud).also { connections.put(it) }
}

private fun end(input: Int, output: Int) = StreamFrame.End(
    "stop",
    ResponseMetaInfo(Instant.fromEpochSeconds(0), inputTokensCount = input, outputTokensCount = output),
)
