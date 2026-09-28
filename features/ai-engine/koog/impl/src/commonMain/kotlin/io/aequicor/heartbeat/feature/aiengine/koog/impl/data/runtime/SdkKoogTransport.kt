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
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthScope
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.EndpointOrigin
import io.aequicor.heartbeat.feature.aiengine.facade.api.CompatibleProtocol
import io.aequicor.heartbeat.feature.aiengine.facade.api.isCompatibleOriginAllowed
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogProvider
import io.ktor.client.HttpClient

@ContributesBinding(ProfileScope::class)
@Inject
internal class SdkKoogTransport(private val httpClient: HttpClient) : KoogTransport {
    private val log = Log.tag("KoogTransport")

    override fun open(
        provider: KoogProvider,
        key: String?,
        model: String?,
        origin: EndpointOrigin,
        basePath: String?,
    ): KoogClient {
        log.d { "Creating ${provider.name} transport" }
        // Derived clients retain core network logging. Redirects cannot forward provider credentials.
        val base = httpClient.config { followRedirects = false }
        val factory = KtorKoogHttpClient.Factory(baseClient = base)
        // The origin is passed explicitly, so credentials follow the source scope rather than SDK defaults.
        require(if (provider.isOriginEditable) isCompatibleOriginAllowed(origin) else origin == provider.origin)
        require(provider.isOriginEditable || basePath == null)
        val baseUrl = origin.value
        val paths = KoogPaths(provider, AuthScope(provider.id, origin, basePath))
        val client = try {
            when (provider) {
                KoogProvider.OpenAI, KoogProvider.OpenAICompatible -> OpenAILLMClient(
                    apiKey = requireNotNull(key),
                    settings = OpenAIClientSettings(
                        baseUrl = baseUrl,
                        chatCompletionsPath = paths.openAi("chat/completions"),
                        modelsPath = paths.openAi("models"),
                    ),
                    httpClientFactory = factory,
                )

                KoogProvider.AlibabaQwen -> AlibabaKoogClient(
                    apiKey = requireNotNull(key),
                    settings = OpenAIClientSettings(
                        baseUrl = baseUrl,
                        chatCompletionsPath = "compatible-mode/v1/chat/completions",
                        modelsPath = "compatible-mode/v1/models",
                    ),
                    httpClientFactory = factory,
                )

                KoogProvider.Anthropic, KoogProvider.AnthropicCompatible -> AnthropicLLMClient(
                    apiKey = requireNotNull(key),
                    settings = paths.anthropicSettings(baseUrl, provider, model),
                    httpClientFactory = factory,
                )

                KoogProvider.Ollama -> OllamaClient(httpClientFactory = factory, baseUrl = baseUrl)
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
        KoogProvider.OpenAI, KoogProvider.AlibabaQwen, KoogProvider.OpenAICompatible -> LLMProvider.OpenAI
        KoogProvider.Anthropic, KoogProvider.AnthropicCompatible -> LLMProvider.Anthropic
        KoogProvider.Ollama -> LLMProvider.Ollama
    }

/** SDK-relative request paths; compatible routes use the user's base path, vendor routes the SDK defaults. */
private class KoogPaths(provider: KoogProvider, scope: AuthScope) {
    private val openAiPrefix =
        if (provider == KoogProvider.OpenAICompatible) CompatibleProtocol.OpenAI.apiBase(scope) else "/v1"
    private val anthropicPrefix =
        if (provider == KoogProvider.AnthropicCompatible) CompatibleProtocol.Anthropic.apiBase(scope) else ""

    fun openAi(endpoint: String): String = "$openAiPrefix/$endpoint".trimStart('/')

    fun anthropic(endpoint: String): String = "$anthropicPrefix/v1/$endpoint".trimStart('/')

    /** Maps [model] to itself so arbitrary provider ids pass the SDK's model version lookup. */
    fun anthropicSettings(baseUrl: String, provider: KoogProvider, model: String?): AnthropicClientSettings =
        AnthropicClientSettings(
            modelVersionsMap = model?.let {
                mapOf(provider.textModel(it) to it, provider.textModel(it, attachments = true) to it)
            } ?: AnthropicClientSettings().modelVersionsMap,
            baseUrl = baseUrl,
            messagesPath = anthropic("messages"),
            modelsPath = anthropic("models"),
        )
}
