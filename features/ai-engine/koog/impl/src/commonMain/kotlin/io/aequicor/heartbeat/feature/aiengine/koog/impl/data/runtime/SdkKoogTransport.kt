package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicClientSettings
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.executor.ollama.client.toLLModel
import ai.koog.prompt.llm.LLMProvider
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogProvider
import io.ktor.client.HttpClient

@ContributesBinding(ProfileScope::class)
@Inject
internal class SdkKoogTransport(private val httpClient: HttpClient) : KoogTransport {
    private val log = Log.tag("KoogTransport")

    override fun open(provider: KoogProvider, key: String?, model: String?): KoogClient {
        log.d { "Creating ${provider.name} transport" }
        // Derived clients retain core network logging. Redirects cannot forward provider credentials.
        val base = httpClient.config { followRedirects = false }
        val factory = KtorKoogHttpClient.Factory(baseClient = base)
        // The fixed origin is passed explicitly, so credentials follow KoogProvider rather than SDK defaults.
        val origin = provider.origin.value
        val client = try {
            when (provider) {
                KoogProvider.OpenAI -> OpenAILLMClient(
                    apiKey = requireNotNull(key),
                    settings = OpenAIClientSettings(baseUrl = origin),
                    httpClientFactory = factory,
                )

                KoogProvider.AlibabaQwen -> AlibabaKoogClient(
                    apiKey = requireNotNull(key),
                    settings = OpenAIClientSettings(
                        baseUrl = origin,
                        chatCompletionsPath = "compatible-mode/v1/chat/completions",
                        modelsPath = "compatible-mode/v1/models",
                    ),
                    httpClientFactory = factory,
                )

                KoogProvider.Anthropic -> AnthropicLLMClient(
                    apiKey = requireNotNull(key),
                    settings = if (model == null) {
                        AnthropicClientSettings(baseUrl = origin)
                    } else {
                        AnthropicClientSettings(
                            modelVersionsMap = mapOf(
                                provider.textModel(model) to model,
                                provider.textModel(model, attachments = true) to model,
                            ),
                            baseUrl = origin,
                        )
                    },
                    httpClientFactory = factory,
                )

                KoogProvider.Ollama -> OllamaClient(httpClientFactory = factory, baseUrl = origin)
            }
        } finally {
            // SDK clients own a configured child; release the intermediate configuration on every path.
            base.close()
        }
        val executor = MultiLLMPromptExecutor(mapOf(provider.llmProvider to client))
        return KoogClient(executor) {
            if (client is OllamaClient) client.getModels().map { it.toLLModel() } else client.models()
        }
    }
}

internal val KoogProvider.llmProvider: LLMProvider
    get() = when (this) {
        KoogProvider.OpenAI, KoogProvider.AlibabaQwen -> LLMProvider.OpenAI
        KoogProvider.Anthropic -> LLMProvider.Anthropic
        KoogProvider.Ollama -> LLMProvider.Ollama
    }
