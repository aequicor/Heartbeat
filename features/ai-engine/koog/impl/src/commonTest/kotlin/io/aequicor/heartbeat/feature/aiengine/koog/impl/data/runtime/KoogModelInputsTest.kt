package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthScope
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSecretId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceInfo
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.EndpointOrigin
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptInputSupport
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogConnection
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogEngineId
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogProvider
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KoogModelInputsTest {
    @Test
    fun `arbitrary compatible origins cannot inherit static vendor binary capabilities`() = runTest {
        var lookups = 0
        val inputs = KoogModelInputs(object : KoogReasoningCatalog {
            override suspend fun levels(provider: KoogProvider, model: String): List<String>? = null
            override suspend fun inputSupport(provider: KoogProvider, model: String): PromptInputSupport {
                lookups++
                return PromptInputSupport(
                    imageMediaTypes = setOf("image/png"),
                    resourceMediaTypes = setOf("application/pdf"),
                )
            }
        })
        listOf(KoogProvider.OpenAICompatible, KoogProvider.AnthropicCompatible).forEach { provider ->
            val connection = connection(provider, EndpointOrigin("https://custom.example"))
            val model = binaryModel(provider)
            val support = inputs.remember(connection, model)
            assertTrue(support.imageMediaTypes.isEmpty())
            assertEquals(PromptInputSupport.TextDocuments.resourceMediaTypes, support.resourceMediaTypes)
            assertEquals(support, inputs.cached(connection, model.id))
        }
        assertEquals(0, lookups)
    }

    @Test
    fun `compatible routes at exact official origins retain vendor capabilities and catalog lookup`() = runTest {
        val lookups = mutableListOf<KoogProvider>()
        val inputs = KoogModelInputs(object : KoogReasoningCatalog {
            override suspend fun levels(provider: KoogProvider, model: String): List<String>? = null
            override suspend fun inputSupport(provider: KoogProvider, model: String): PromptInputSupport {
                lookups += provider
                return PromptInputSupport.TextDocuments
            }
        })
        listOf(
            KoogProvider.OpenAICompatible to KoogProvider.OpenAI,
            KoogProvider.AnthropicCompatible to KoogProvider.Anthropic,
        ).forEach { (provider, vendor) ->
            val support = inputs.remember(connection(provider, vendor.origin), binaryModel(provider))
            assertTrue("image/png" in support.imageMediaTypes)
            assertTrue("application/pdf" in support.resourceMediaTypes)
        }
        assertEquals(listOf(KoogProvider.OpenAI, KoogProvider.Anthropic), lookups)
    }

    @Test
    fun `Ollama actual model metadata is retained but Qwen cannot inherit OpenAI SDK capabilities`() = runTest {
        val inputs = KoogModelInputs()
        val ollama = inputs.remember(connection(KoogProvider.Ollama), binaryModel(KoogProvider.Ollama))
        assertTrue("image/png" in ollama.imageMediaTypes)
        assertTrue("application/pdf" in ollama.resourceMediaTypes)
        val qwen = inputs.remember(connection(KoogProvider.AlibabaQwen), binaryModel(KoogProvider.AlibabaQwen))
        assertTrue(qwen.imageMediaTypes.isEmpty())
        assertEquals(PromptInputSupport.TextDocuments.resourceMediaTypes, qwen.resourceMediaTypes)
    }

    private fun binaryModel(provider: KoogProvider): LLModel = LLModel(
        provider.llmProvider,
        if (provider == KoogProvider.AnthropicCompatible) "claude-sonnet-4-5-20250929" else "gpt-4o",
        listOf(LLMCapability.Vision.Image, LLMCapability.Document),
    )

    private fun connection(provider: KoogProvider, origin: EndpointOrigin = provider.origin): KoogConnection {
        val info = AuthSourceInfo(AuthSourceId("source"), "Source", AuthRevision.Known("1"))
        val scope = AuthScope(provider.id, origin)
        val source = if (provider == KoogProvider.Ollama) {
            AuthSource.NoAuth(info, scope)
        } else {
            AuthSource.ManagedKey(info, scope, AuthSecretId("key"))
        }
        return KoogConnection(EngineBinding(EngineBindingId("binding"), KoogEngineId, info.id), source)
    }
}
