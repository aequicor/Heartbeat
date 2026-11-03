package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import io.aequicor.heartbeat.core.network.OfflineHttpResponse
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class OllamaSessionStreamTest {
    @Test
    fun `actual SDK stream completes the native session only after valid terminal response`() = runTest {
        val fixture = SdkProbeHttpFixture()
        val exchange = sdkExchange(KoogProvider.Ollama, KoogSdkCheck.StreamStop)
        val offline = fixture.open(listOf(exchange))
        val client = SdkKoogTransport(offline.client, TestKoogPlatform).open(
            KoogProvider.Ollama,
            null,
            "test-model",
            KoogProvider.Ollama.origin,
        )
        try {
            val sessionFixture = KoogTestFixture(this)
            sessionFixture.openClient = { client }
            val session = sessionFixture.session()
            val sender = assertIs<FeatureAccess.Available<SendsPrompts>>(session.features.resolve(SendsPrompts)).feature
            val turn = sender.send(sessionFixture.request())
            val ready = withContext(Dispatchers.Default) {
                withTimeout(5_000) { session.state.first { it is ActiveSessionState.Ready && it.lastTurn?.id == turn } }
            } as ActiveSessionState.Ready
            assertEquals(TurnOutcome.Completed, ready.lastTurn?.outcome)
        } finally {
            client.closeAndAwait()
            offline.closeAndAwait()
        }
    }

    @Test
    fun `malformed SDK stream fails the native session instead of completing`() = runTest {
        val fixture = SdkProbeHttpFixture()
        val exchange = sdkExchange(KoogProvider.Ollama, KoogSdkCheck.StreamStop)
        val response = exchange.response as OfflineHttpResponse.Reply
        val offline = fixture.open(listOf(exchange.copy(response = response.copy(body = "{broken-json\n"))))
        val client = SdkKoogTransport(offline.client, TestKoogPlatform).open(
            KoogProvider.Ollama,
            null,
            "test-model",
            KoogProvider.Ollama.origin,
        )
        try {
            val sessionFixture = KoogTestFixture(this)
            sessionFixture.openClient = { client }
            val session = sessionFixture.session()
            val sender = assertIs<FeatureAccess.Available<SendsPrompts>>(session.features.resolve(SendsPrompts)).feature
            val turn = sender.send(sessionFixture.request())
            val ready = withContext(Dispatchers.Default) {
                withTimeout(5_000) { session.state.first { it is ActiveSessionState.Ready && it.lastTurn?.id == turn } }
            } as ActiveSessionState.Ready
            assertIs<TurnOutcome.Failed>(ready.lastTurn?.outcome)
        } finally {
            client.closeAndAwait()
            offline.closeAndAwait()
        }
    }
}
